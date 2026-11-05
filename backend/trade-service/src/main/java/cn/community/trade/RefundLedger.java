package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** All callers hold the order lock and a transaction. Mapping never calls the channel. */
final class RefundLedger {
    static long n(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    static void mapHistory(JdbcTemplate db,long reservation){
        var refunds=db.queryForList("SELECT * FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' ORDER BY id",reservation);
        for(var refund:refunds){
            long id=n(refund,"id");
            if(refund.get("application_id")==null){
                Object parent=null;
                if(refund.get("retry_of_id")!=null)parent=db.queryForObject("SELECT application_id FROM payment_attempt WHERE id=?",Long.class,refund.get("retry_of_id"));
                if(parent==null){
                    parent=Data.id(db);
                    db.update("INSERT INTO refund_application(id,reservation_id,user_id,amount_fen,reason,status) VALUES(?,?,?,?,'历史自动退款映射','APPROVED')",parent,reservation,refund.get("user_id"),refund.get("amount_fen"));
                }
                db.update("UPDATE payment_attempt SET application_id=? WHERE id=?",parent,id);
                db.update("INSERT INTO audit_log(actor_id,action,resource_id,reason) VALUES(NULL,'REFUND_HISTORY_MAP',?,'保留原通道号和结果，仅补关联')",Long.toString(id));
            }
            if(!"FAILED".equals(refund.get("status"))&&db.queryForObject("SELECT COUNT(*) FROM refund_receipt_allocation WHERE attempt_id=?",Integer.class,id)==0)
                allocate(db,reservation,id,n(refund,"amount_fen"));
        }
        validateAllocations(db,reservation);
        for(var parent:db.queryForList("SELECT id,amount_fen FROM refund_application WHERE reservation_id=? AND status IN ('APPROVED','PARTIAL_FAILED','COMPLETED')",reservation)){
            long used=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE application_id=? AND status<>'FAILED'",Long.class,parent.get("id"));
            Problem.require(used<=n(parent,"amount_fen"),409,"REFUND_HISTORY_CONFLICT","历史退款超过父目标，需核查");
            refresh(db,parent.get("id"));
        }
        if(!refunds.isEmpty()){
            var order=db.queryForMap("SELECT * FROM trade_order WHERE reservation_id=?",reservation);
            long received=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE reservation_id=? AND kind IN ('PREPAY','OVERTIME') AND status='SUCCESS'",Long.class,reservation);
            long returned=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' AND status='SUCCESS'",Long.class,reservation);
            Problem.require(received==n(order,"paid_prepay_fen")+n(order,"paid_overtime_fen")&&returned==n(order,"refund_fen"),409,"REFUND_HISTORY_CONFLICT","历史收退款与账单不一致，需核查");
        }
    }
    private static void validateAllocations(JdbcTemplate db,long reservation){
        int invalid=db.queryForObject("""
            SELECT COUNT(*) FROM refund_receipt_allocation a
            JOIN payment_attempt p ON p.id=a.attempt_id JOIN payment_attempt r ON r.id=a.receipt_id
            WHERE (p.reservation_id=? OR r.reservation_id=?)
            AND (p.kind<>'REFUND' OR r.kind NOT IN ('PREPAY','OVERTIME') OR r.status<>'SUCCESS'
                 OR p.reservation_id<>r.reservation_id OR a.amount_fen<=0)
            """,Integer.class,reservation,reservation);
        Problem.require(invalid==0,409,"REFUND_HISTORY_CONFLICT","退款分配的收款归属或状态异常");
        for(var refund:db.queryForList("SELECT id,amount_fen FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' AND status<>'FAILED'",reservation)){
            long allocated=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM refund_receipt_allocation WHERE attempt_id=?",Long.class,refund.get("id"));
            Problem.require(allocated==n(refund,"amount_fen"),409,"REFUND_HISTORY_CONFLICT","退款分配合计与退款金额不一致");
        }
        for(var receipt:db.queryForList("SELECT id,amount_fen FROM payment_attempt WHERE reservation_id=? AND kind IN ('PREPAY','OVERTIME') AND status='SUCCESS'",reservation)){
            long used=db.queryForObject("SELECT COALESCE(SUM(a.amount_fen),0) FROM refund_receipt_allocation a JOIN payment_attempt p ON p.id=a.attempt_id WHERE a.receipt_id=? AND p.status<>'FAILED'",Long.class,receipt.get("id"));
            Problem.require(used<=n(receipt,"amount_fen"),409,"REFUND_HISTORY_CONFLICT","成功收款额度被超额分配");
        }
    }
    static void allocate(JdbcTemplate db,long reservation,long attempt,long amount){
        long remaining=amount;
        for(var receipt:db.queryForList("SELECT id,amount_fen FROM payment_attempt WHERE reservation_id=? AND kind IN ('PREPAY','OVERTIME') AND status='SUCCESS' ORDER BY id FOR UPDATE",reservation)){
            long used=db.queryForObject("SELECT COALESCE(SUM(a.amount_fen),0) FROM refund_receipt_allocation a JOIN payment_attempt p ON p.id=a.attempt_id WHERE a.receipt_id=? AND p.status<>'FAILED'",Long.class,receipt.get("id"));
            long available=n(receipt,"amount_fen")-used;
            Problem.require(available>=0,409,"REFUND_HISTORY_CONFLICT","收款分配不一致，需核查");
            long part=Math.min(remaining,available);
            if(part>0){db.update("INSERT INTO refund_receipt_allocation VALUES(?,?,?)",attempt,receipt.get("id"),part);remaining-=part;}
            if(remaining==0)break;
        }
        Problem.require(remaining==0,409,"REFUND_HISTORY_CONFLICT","缺少可核验成功收款，未执行退款");
    }
    static void refresh(JdbcTemplate db,Object parent){
        int active=db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE application_id=? AND status NOT IN ('SUCCESS','FAILED')",Integer.class,parent);
        if(active>0){db.update("UPDATE refund_application SET status=? WHERE id=?",RefundContracts.Status.APPROVED.name(),parent);return;}
        int failed=db.queryForObject("SELECT COUNT(*) FROM payment_attempt p WHERE application_id=? AND status='FAILED' AND NOT EXISTS(SELECT 1 FROM payment_attempt s WHERE s.retry_of_id=p.id)",Integer.class,parent);
        db.update("UPDATE refund_application SET status=? WHERE id=?",(failed==0?RefundContracts.Status.COMPLETED:RefundContracts.Status.PARTIAL_FAILED).name(),parent);
    }
    static void automatic(JdbcTemplate db,long reservation){
        mapHistory(db,reservation);
        var order=db.queryForMap("SELECT * FROM trade_order WHERE reservation_id=?",reservation);
        if(!Set.of("CANCELLED","EXPIRED","NO_SHOW","SPACE_UNAVAILABLE").contains(order.get("status")))return;
        long target=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM refund_application WHERE reservation_id=? AND status NOT IN ('REJECTED','PENDING')",Long.class,reservation);
        long amount=n(order,"paid_prepay_fen")-target;
        if(amount<=0)return;
        long parent=Data.id(db),attempt=Data.id(db);
        db.update("INSERT INTO refund_application(id,reservation_id,user_id,amount_fen,reason,status) VALUES(?,?,?,?,'关闭订单自动退款','APPROVED')",parent,reservation,order.get("user_id"),amount);
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,application_id) VALUES(?,?,?,'REFUND',?,'CREATED',?,?)",attempt,reservation,order.get("user_id"),amount,"REFUND-AUTO-"+attempt,parent);
        allocate(db,reservation,attempt,amount);
        db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?",reservation);
    }
}
