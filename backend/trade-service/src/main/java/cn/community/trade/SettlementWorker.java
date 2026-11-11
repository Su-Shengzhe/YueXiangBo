package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable dispatch intent precedes I/O. Takeovers query the original number, never resubmit. */
@Component @Profile("business")
public class SettlementWorker {
 private final JdbcTemplate db;private final TransactionTemplate tx;final SimulatedChannel channel;
 public SettlementWorker(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);channel=new SimulatedChannel(db,manager);}
 @Scheduled(fixedDelayString="${business.settlement.poll-millis:1000}") public void process(){
  var candidates=db.queryForList("SELECT id,reservation_id FROM settlement_attempt WHERE status IN ('CREATED','SUBMITTED','UNKNOWN') AND next_retry_at<=CURRENT_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 20");
  for(var c:candidates){long id=Settlements.n(c,"id"),reservation=Settlements.n(c,"reservation_id");String token=UUID.randomUUID().toString();
   try{
    var attempt=tx.execute(s->{lock(reservation);var a=Management.one(db,"SELECT * FROM settlement_attempt WHERE id=? FOR UPDATE",id);
     if(!Set.of("CREATED","SUBMITTED","UNKNOWN").contains(a.get("status")))return null;
     var now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
     if(a.get("next_retry_at")==null||Data.instant(a.get("next_retry_at")).isAfter(now)||(a.get("lease_until")!=null&&Data.instant(a.get("lease_until")).isAfter(now)))return null;
     if(Settlements.n(a,"query_count")>=5){db.update("UPDATE settlement_attempt SET status='REVIEW',next_retry_at=NULL,last_error='RECOVERY_LIMIT',lease_token=NULL,lease_until=NULL WHERE id=?",id);return null;}
     db.update("UPDATE settlement_attempt SET status='SUBMITTED',lease_token=?,lease_until=?,query_count=query_count+CASE WHEN dispatch_count>0 THEN 1 ELSE 0 END,dispatch_count=dispatch_count+1 WHERE id=?",token,Data.ts(now.plusSeconds(30)),id);
     return Management.one(db,"SELECT * FROM settlement_attempt WHERE id=?",id);
    });
    if(attempt==null)continue;
    String number=Data.str(attempt,"payout_no");long amount=Settlements.n(attempt,"requested_fen");
    String result=Settlements.n(attempt,"dispatch_count")==1?channel.submit(number,"SETTLEMENT",amount):channel.query(number,"SETTLEMENT",amount);
    if(!Set.of("SUCCESS","FAILED").contains(result))throw new IllegalStateException("CHANNEL_RESULT_UNKNOWN");
    post(id,reservation,token,result);
   }catch(RuntimeException failure){
    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Settlement {} pending recovery ({})",id,failure.getClass().getSimpleName());
    tx.executeWithoutResult(s->{lock(reservation);var rows=db.queryForList("SELECT query_count FROM settlement_attempt WHERE id=? AND lease_token=?",id,token);if(rows.isEmpty())return;
     long count=Settlements.n(rows.getFirst(),"query_count");var now=db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
     db.update("UPDATE settlement_attempt SET status=?,lease_token=NULL,lease_until=NULL,next_retry_at=?,last_error=? WHERE id=? AND lease_token=?",count>=5?"REVIEW":"UNKNOWN",count>=5?null:Data.ts(now.plusSeconds(Math.min(60,5L<<count))),failure.getClass().getSimpleName(),id,token);
    });
   }
  }
 }
 private void lock(long reservation){Management.one(db,"SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE",reservation);}
 void post(long id,long reservation,String token,String result){
  Problem.require(Set.of("SUCCESS","FAILED").contains(result),422,"INVALID_RESULT","必须是确定结果");
  tx.executeWithoutResult(s->{lock(reservation);var a=Management.one(db,"SELECT * FROM settlement_attempt WHERE id=? FOR UPDATE",id);
   if(!token.equals(a.get("lease_token"))||!Set.of("SUBMITTED","UNKNOWN").contains(a.get("status")))return;
   var item=Management.one(db,"SELECT * FROM settlement_item WHERE id=? FOR UPDATE",a.get("item_id"));
   Problem.require(Objects.equals(item.get("active_attempt_id"),a.get("id")),409,"STALE_ATTEMPT","结算尝试已变化");
   db.update("UPDATE settlement_attempt SET status=?,lease_token=NULL,lease_until=NULL,next_retry_at=NULL,last_error=NULL WHERE id=?",result,id);
   db.update("UPDATE settlement_item SET status=?,active_attempt_id=NULL WHERE id=?",result,a.get("item_id"));
   db.update("UPDATE trade_order SET settlement_status=?,bill_version=bill_version+1 WHERE reservation_id=?",result.equals("SUCCESS")?"SETTLED":"UNSETTLED",reservation);
   if(result.equals("SUCCESS"))db.update("INSERT INTO ledger_entry(reservation_id,channel_no,kind,amount_fen) VALUES(?,?,'SETTLEMENT',?)",reservation,a.get("payout_no"),a.get("requested_fen"));
  });
 }
}
