package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies authorization, idempotency and refund retry invariants in one transaction. */
final class RefundRecoveryService {
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 RefundRecoveryService(JdbcTemplate db,PlatformTransactionManager manager){
  this.db=db;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 private Map<String,Object> one(String sql,Object...args){return Management.one(db,sql,args);}
 private Map<String,Object> bill(long id){return one("SELECT * FROM trade_order WHERE reservation_id=?",id);}
 private void lock(long id){one("SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE",id);}
 private boolean closed(Map<String,Object> bill){return Set.of("CANCELLED","EXPIRED","NO_SHOW","SPACE_UNAVAILABLE").contains(Data.str(bill,"status"));}
 Map<String,Object> retry(long id,String key,Map<String,Object> input,Actor actor){
  actor.permission("FUNDS_RECOVER");Requests.key(key);
  String reason=Data.str(input,"reason").trim();
  Problem.require(!reason.isEmpty()&&reason.length()<=500,422,"INVALID_ARGUMENT","请填写500字以内的重试原因");
  return tx.execute(s->{
   var initial=one("SELECT * FROM payment_attempt WHERE id=?",id);
   long reservation=((Number)initial.get("reservation_id")).longValue();lock(reservation);RefundLedger.mapHistory(db,reservation);
   var prior=Requests.existing(db,actor.id(),"REFUND_RETRY",key,Data.hash(id+":"+reason));if(prior!=null)return prior;
   var failed=one("SELECT * FROM payment_attempt WHERE id=?",id);
   Problem.require("REFUND".equals(Data.str(failed,"kind"))&&"FAILED".equals(Data.str(failed,"status")),409,"REFUND_NOT_FAILED","仅确定失败的退款可以重试");
   var successors=db.queryForList("SELECT * FROM payment_attempt WHERE retry_of_id=?",id);
   Map<String,Object> result;
   if(!successors.isEmpty())result=Data.row(successors.getFirst());
   else {
    var bill=bill(reservation);
    long amount=((Number)failed.get("amount_fen")).longValue();
    Object application=failed.get("application_id"),receipt=failed.get("receipt_attempt_id");
    if(application!=null){
     var parent=one("SELECT * FROM refund_application WHERE id=? FOR UPDATE",application);

     long orderUsed=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' AND status<>'FAILED'",Long.class,reservation);
     long parentUsed=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE application_id=? AND status<>'FAILED'",Long.class,application);
     long paidTotal=((Number)bill.get("paid_prepay_fen")).longValue()+((Number)bill.get("paid_overtime_fen")).longValue();
     Problem.require(amount>0&&amount<=paidTotal-orderUsed&&amount<=((Number)parent.get("amount_fen")).longValue()-parentUsed,409,"REFUND_AMOUNT_CONFLICT","订单、收款或批准目标额度已变化");
    }else{
    long remaining=((Number)bill.get("paid_prepay_fen")).longValue()-((Number)bill.get("refund_fen")).longValue();
    Problem.require(closed(bill)&&amount>0&&amount<=remaining,409,"REFUND_AMOUNT_CONFLICT","订单状态或可退余额已变化");
    Problem.require(db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' AND status IN ('CREATED','SUBMITTED','UNKNOWN','REVIEW')",Integer.class,reservation)==0,409,"REFUND_ACTIVE","已有退款处理中");
    }
    long next=Data.id(db);
    db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,retry_of_id) VALUES(?,?,?,'REFUND',?,'CREATED',?,?)",next,reservation,bill.get("user_id"),amount,"REFUND-RETRY-"+next,id);
    RefundLedger.allocate(db,reservation,next,amount);
    if(application!=null){db.update("UPDATE payment_attempt SET application_id=?,receipt_attempt_id=? WHERE id=?",application,receipt,next);db.update("UPDATE refund_application SET status='APPROVED' WHERE id=?",application);}
    db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?",reservation);
    db.update("INSERT INTO audit_log(actor_id,action,resource_id,reason) VALUES(?,'REFUND_RETRY',?,?)",actor.id(),Long.toString(next),reason);
    result=Data.row(one("SELECT * FROM payment_attempt WHERE id=?",next));
   }
   Requests.save(db,actor.id(),"REFUND_RETRY",key,Data.hash(id+":"+reason),result);return result;
  });
 }
}

