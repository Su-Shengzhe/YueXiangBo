package cn.community.trade;
import cn.community.foundation.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.ResponseEntity;
@RestController @Profile("business")
public class BusinessTrade implements Events.Handler {
 private final JdbcTemplate db;private final TransactionTemplate tx;
 public BusinessTrade(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;this.tx=new TransactionTemplate(manager);}
 private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);Problem.require(!rows.isEmpty(),404,"BILL_NOT_READY","账单准备中或记录不存在");return rows.getFirst();}
 private Map<String,Object> bill(long id){return one("SELECT * FROM trade_order WHERE reservation_id=?",id);}
 private void lock(long id){one("SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE",id);}
 private void own(Map<String,Object> b,Actor a){Problem.require(((Number)b.get("user_id")).longValue()==a.id(),404,"NOT_FOUND","记录不存在");}
 private boolean closed(Map<String,Object> b){return List.of("CANCELLED","EXPIRED","NO_SHOW","SPACE_UNAVAILABLE").contains(Data.str(b,"status"));}
 @GetMapping("/api/v1/bills/{id}") public Map<String,Object> get(@PathVariable long id,HttpServletRequest req){
  return tx.execute(s->{lock(id);var b=bill(id);own(b,Actor.of(req));var result=Data.row(b);
  result.put("attempts",db.queryForList("SELECT id,kind,amount_fen,status,channel_no FROM payment_attempt WHERE reservation_id=? ORDER BY id DESC",id).stream().map(Data::row).toList());
  result.put("reservationVersion",result.get("version"));
  result.put("settlementStatus","NOT_IMPLEMENTED");return result;});
 }
 @PostMapping("/api/v1/payments") public ResponseEntity<?> pay(@RequestBody Map<String,Object> in,@RequestHeader("Idempotency-Key")String key,HttpServletRequest req){
  Actor a=Actor.of(req);a.role("DRIVER");Requests.key(key);long id=Data.number(in,"reservationId");String kind=Data.str(in,"kind");
  Problem.require(List.of("PREPAY","OVERTIME").contains(kind),422,"INVALID_ARGUMENT","支付类型无效");String hash=Data.hash(id+":"+kind);
  var result=tx.execute(s->{
   lock(id);var b=bill(id);own(b,a);var prior=Requests.existing(db,a.id(),"PAY",key,hash);if(prior!=null)return prior;
   Problem.require(!closed(b),409,"ORDER_CLOSED","订单已关闭");
   long amount=kind.equals("PREPAY")?((Number)b.get("prepay_fen")).longValue()-((Number)b.get("paid_prepay_fen")).longValue():((Number)b.get("overtime_fen")).longValue()-((Number)b.get("paid_overtime_fen")).longValue();
   Problem.require(kind.equals("PREPAY")||Data.str(b,"status").equals("COMPLETED"),409,"BILL_NOT_FINAL","离场后才可支付超时费");
   Problem.require(amount>0,409,"NOTHING_TO_PAY","已支付或无需付款");
   var active=db.queryForList("SELECT * FROM payment_attempt WHERE reservation_id=? AND kind=? AND status IN ('CREATED','SUBMITTED','UNKNOWN','REVIEW') ORDER BY id LIMIT 1",id,kind);
   Map<String,Object> attempt;
   if(!active.isEmpty())attempt=Data.row(active.getFirst());else{
    long attemptId=Data.id(db);db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(?,?,?,?,?,'CREATED',?)",attemptId,id,a.id(),kind,amount,"SIM-"+attemptId);
     db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?",id);
     attempt=Data.row(one("SELECT * FROM payment_attempt WHERE id=?",attemptId));
   }
   Requests.save(db,a.id(),"PAY",key,hash,attempt);return attempt;
  });return ResponseEntity.accepted().body(result);
 }
 @GetMapping("/api/v1/payments/{id}") public Map<String,Object> attempt(@PathVariable long id,HttpServletRequest req){
  var p=one("SELECT * FROM payment_attempt WHERE id=?",id);own(p,Actor.of(req));return Data.row(p);
 }
 public void accept(String type,Map<String,Object> p){
  Problem.require(List.of("ReservationCreated","ReservationConfirmed","ReservationClosed","ParkingEntered","ParkingCompleted").contains(type),422,"EVENT_TYPE","Unsupported trade event");
  long id=Data.number(p,"id");db.update("INSERT IGNORE INTO trade_order(reservation_id,user_id,prepay_fen) VALUES(?,?,?)",id,Data.number(p,"userId"),Data.number(p,"prepayFen"));lock(id);var b=bill(id);
  long version=Data.number(p,"version");
  if(version>((Number)b.get("version")).longValue())db.update("UPDATE trade_order SET status=?,version=?,overtime_fen=?,bill_version=bill_version+1 WHERE reservation_id=?",Data.str(p,"status"),version,Data.number(p,"overtimeFen"),id);
  // Financial reconciliation is independent of projection version.
  refundIfClosed(id);
 }
 private void refundIfClosed(long id){TradeAccounting.refundIfClosed(db,id);}
}
