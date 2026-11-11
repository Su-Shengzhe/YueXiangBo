package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Order locks serialize settlement admission, refund approvals and financial posting. */
@RestController @Profile("business")
public class Settlements {
 final JdbcTemplate db; final TransactionTemplate tx;
 public Settlements(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);}
 static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 static long ownerShare(long net,long basis){
  if(net<0||basis<0||basis>10000)throw new IllegalArgumentException("Invalid settlement balance");
  return net-Math.multiplyExact(net,basis)/10000;
 }
 static void refundAllowed(Map<String,Object> bill){Problem.require(!"SETTLING".equals(bill.get("settlement_status")),409,"SETTLEMENT_IN_PROGRESS","结算结果尚未确定，暂不能执行退款");}
 Map<String,Object> order(long id){return Management.one(db,"SELECT * FROM trade_order WHERE reservation_id=? FOR UPDATE",id);}
 List<String> blockers(Map<String,Object> b){
  long id=n(b,"reservation_id");var reasons=new ArrayList<String>();
  if(!"COMPLETED".equals(b.get("status")))reasons.add("ORDER_NOT_COMPLETED");
  if(b.get("owner_id")==null)reasons.add("OWNER_SNAPSHOT_MISSING");
  if(!"UNSETTLED".equals(b.get("settlement_status")))reasons.add("SETTLEMENT_ACTIVE_OR_DONE");
  if(n(b,"paid_prepay_fen")<n(b,"prepay_fen")||n(b,"paid_overtime_fen")<n(b,"overtime_fen"))reasons.add("PAYMENT_DUE");
  if(db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE reservation_id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN','REVIEW')",Integer.class,id)>0)reasons.add("FUNDS_UNRESOLVED");
  if(db.queryForObject("SELECT COUNT(*) FROM refund_application WHERE reservation_id=? AND status IN ('PENDING','APPROVED')",Integer.class,id)>0)reasons.add("REFUND_UNRESOLVED");
  if(db.queryForObject("SELECT COUNT(*) FROM settlement_hold WHERE reservation_id=?",Integer.class,id)>0)reasons.add("RECONCILIATION_HOLD");
  long paid=n(b,"paid_prepay_fen")+n(b,"paid_overtime_fen"),refunded=n(b,"refund_fen");
  long ledgerPaid=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM ledger_entry WHERE reservation_id=? AND kind IN ('PREPAY','OVERTIME')",Long.class,id);
  long ledgerRefund=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM ledger_entry WHERE reservation_id=? AND kind='REFUND'",Long.class,id);
  if(paid!=ledgerPaid||refunded!=ledgerRefund||refunded>paid)reasons.add("LEDGER_DIFFERENCE");
  return reasons;
 }
 List<Long> ids(Map<String,Object> input){
  Problem.require(input.get("reservationIds") instanceof List<?>,422,"INVALID_ARGUMENT","请指定订单列表");
  var raw=(List<?>)input.get("reservationIds");Problem.require(!raw.isEmpty()&&raw.size()<=100,422,"INVALID_ARGUMENT","每批1至100个订单");
  try{return raw.stream().map(x->Long.parseLong(x.toString())).distinct().sorted().toList();}catch(RuntimeException e){throw new Problem(422,"INVALID_ARGUMENT","订单编号无效");}
 }
 @PostMapping("/api/v1/admin/settlements/preview") public Map<String,Object> preview(@RequestBody Map<String,Object> input,HttpServletRequest request){
  Actor.of(request).permission("SETTLEMENT_EXECUTE");var ids=ids(input);
  return tx.execute(s->{var rows=new ArrayList<Map<String,Object>>();for(long id:ids){var b=order(id);var reasons=blockers(b);long net=n(b,"paid_prepay_fen")+n(b,"paid_overtime_fen")-n(b,"refund_fen");rows.add(Map.of("reservationId",Long.toString(id),"billVersion",b.get("bill_version"),"blockers",reasons,"requestedFen",net<0?0:ownerShare(net,n(b,"platform_basis_points"))));}return Map.of("items",rows);});
 }
 @PostMapping("/api/v1/admin/settlement-batches") public Map<String,Object> execute(@RequestBody Map<String,Object> input,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
  Actor actor=Actor.of(request);actor.permission("SETTLEMENT_EXECUTE");Requests.key(key);var ids=ids(input);String hash=Data.hash(Data.json(ids));
  return tx.execute(s->{var orders=ids.stream().map(this::order).toList();var prior=Requests.existing(db,actor.id(),"SETTLEMENT_BATCH",key,hash);if(prior!=null)return prior;
   for(var b:orders){Problem.require(blockers(b).isEmpty(),409,"SETTLEMENT_INELIGIBLE","订单结算资格已变化，请重新预览");Problem.require(db.queryForObject("SELECT COUNT(*) FROM settlement_item WHERE reservation_id=?",Integer.class,b.get("reservation_id"))==0,409,"SETTLEMENT_EXISTS","失败项请使用重核重试入口");}
   long batch=Data.id(db);db.update("INSERT INTO settlement_batch(id,actor_id) VALUES(?,?)",batch,actor.id());
   for(var b:orders){long item=Data.id(db);db.update("INSERT INTO settlement_item(id,batch_id,reservation_id,owner_id,status,requested_fen,platform_amount_fen,basis_bill_version) VALUES(?,?,?,?,'CREATED',0,0,?)",item,batch,b.get("reservation_id"),b.get("owner_id"),b.get("bill_version"));createAttempt(item,b);}
   var result=Map.<String,Object>of("batchId",Long.toString(batch));Requests.save(db,actor.id(),"SETTLEMENT_BATCH",key,hash,result);Management.audit(db,actor,"SETTLEMENT_EXECUTE",batch,input);return result;
  });
 }
 Map<String,Object> createAttempt(long item,Map<String,Object> b){
  long net=n(b,"paid_prepay_fen")+n(b,"paid_overtime_fen")-n(b,"refund_fen"),owner=ownerShare(net,n(b,"platform_basis_points")),id=Data.id(db);String status=owner==0?"SUCCESS":"CREATED";
  db.update("INSERT INTO settlement_attempt(id,item_id,reservation_id,requested_fen,platform_amount_fen,basis_bill_version,payout_no,status,last_error) VALUES(?,?,?,?,?,?,?,?,?)",id,item,b.get("reservation_id"),owner,net-owner,b.get("bill_version"),"SETTLEMENT-"+id,status,owner==0?"ZERO_AMOUNT":null);
  db.update("UPDATE settlement_item SET status=?,active_attempt_id=?,requested_fen=?,platform_amount_fen=?,basis_bill_version=? WHERE id=?",status,owner==0?null:id,owner,net-owner,b.get("bill_version"),item);
  db.update("UPDATE trade_order SET settlement_status=?,bill_version=bill_version+1 WHERE reservation_id=?",owner==0?"SETTLED":"SETTLING",b.get("reservation_id"));
  return Data.row(Management.one(db,"SELECT * FROM settlement_attempt WHERE id=?",id));
 }
 @GetMapping("/api/v1/admin/settlement-batches/{id}") public Map<String,Object> batch(@PathVariable long id,HttpServletRequest request){
  Actor.of(request).permission("SETTLEMENT_EXECUTE");Management.one(db,"SELECT id FROM settlement_batch WHERE id=?",id);
  return Map.of("batchId",Long.toString(id),"items",db.queryForList("SELECT * FROM settlement_item WHERE batch_id=? ORDER BY id",id).stream().map(row->{var r=Data.row(row);r.put("attempts",db.queryForList("SELECT * FROM settlement_attempt WHERE item_id=? ORDER BY id",row.get("id")).stream().map(Data::row).toList());r.put("adjustments",db.queryForList("SELECT * FROM settlement_adjustment WHERE item_id=?",row.get("id")).stream().map(Data::row).toList());return r;}).toList());
 }
 @PostMapping("/api/v1/admin/settlement-items/{id}/retry") public Map<String,Object> retry(@PathVariable long id,@RequestBody Map<String,Object> input,@RequestHeader("Idempotency-Key")String key,HttpServletRequest request){
  Actor actor=Actor.of(request);actor.permission("SETTLEMENT_EXECUTE");Requests.key(key);String reason=Management.text(input,"reason",500),hash=Data.hash(id+":"+reason);
  return tx.execute(s->{var initial=Management.one(db,"SELECT * FROM settlement_item WHERE id=?",id);var b=order(n(initial,"reservation_id"));var prior=Requests.existing(db,actor.id(),"SETTLEMENT_RETRY",key,hash);if(prior!=null)return prior;
   var item=Management.one(db,"SELECT * FROM settlement_item WHERE id=? FOR UPDATE",id);Problem.require("FAILED".equals(item.get("status"))&&item.get("active_attempt_id")==null,409,"SETTLEMENT_NOT_FAILED","仅确定失败的结算可重试");Problem.require(blockers(b).isEmpty(),409,"SETTLEMENT_INELIGIBLE","须先解决退款或账务差异");
   var result=createAttempt(id,b);Requests.save(db,actor.id(),"SETTLEMENT_RETRY",key,hash,result);Management.audit(db,actor,"SETTLEMENT_RETRY",id,Map.of("reason",reason,"attempt",result.get("id")));return result;
  });
 }
 /** Cumulative rounding: prior successful payout remains immutable; append only the new receivable. */
 static void refundPosted(JdbcTemplate db,long reservation,long refundAttempt){
  var b=Management.one(db,"SELECT * FROM trade_order WHERE reservation_id=?",reservation);
  if(!"SETTLED".equals(b.get("settlement_status")))return;
  var item=Management.one(db,"SELECT * FROM settlement_item WHERE reservation_id=?",reservation);long itemId=n(item,"id");
  long paid=n(b,"paid_prepay_fen")+n(b,"paid_overtime_fen"),net=paid-n(b,"refund_fen");
  long totalOwner=n(item,"requested_fen")-ownerShare(net,n(b,"platform_basis_points"));
  long totalPlatform=n(item,"platform_amount_fen")-(net-ownerShare(net,n(b,"platform_basis_points")));
  long priorOwner=db.queryForObject("SELECT COALESCE(SUM(owner_recovery_fen),0) FROM settlement_adjustment WHERE item_id=?",Long.class,itemId),priorPlatform=db.queryForObject("SELECT COALESCE(SUM(platform_reversal_fen),0) FROM settlement_adjustment WHERE item_id=?",Long.class,itemId);
  db.update("INSERT INTO settlement_adjustment(refund_attempt_id,item_id,owner_id,owner_recovery_fen,platform_reversal_fen) VALUES(?,?,?,?,?)",refundAttempt,itemId,item.get("owner_id"),totalOwner-priorOwner,totalPlatform-priorPlatform);
 }
}
