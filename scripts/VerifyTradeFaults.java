import cn.community.trade.PaymentWorker;
import cn.community.trade.*;
import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.*;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class VerifyTradeFaults {
 static JdbcTemplate db;static DataSourceTransactionManager manager;
 static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
 static void seed(long id){db.update("INSERT INTO trade_order(reservation_id,user_id,prepay_fen) VALUES(?,1,500)",id);db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,next_retry_at) VALUES(?,?,1,'PREPAY',500,'CREATED',?,'2000-01-01')",id,id,"FAULT-"+id);}
 static PaymentWorker worker(String result,long delay,boolean lost){return new PaymentWorker(db,manager,5,1,5,60,true,result,delay,lost);}
 static String state(long id){return db.queryForObject("SELECT status FROM payment_attempt WHERE id=?",String.class,id);}
 static void due(long id){db.update("UPDATE payment_attempt SET next_retry_at='2000-01-01' WHERE id=?",id);}
 static int ledger(long id){return db.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE reservation_id=?",Integer.class,id);}
 static HttpServletRequest actor(String role){return (HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getAttribute")?new Actor(1,Set.of(role),Set.of()):null);}
 static List<String> race(Callable<?> a,Callable<?> b)throws Exception{
  var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){
   var futures=new ArrayList<Future<String>>();for(var action:List.of(a,b))futures.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);try{action.call();return "OK";}catch(Problem p){return p.code;}}));
   return List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));
  }
 }
 static void refundConcurrency()throws Exception{
  var api=new RefundApplications(db,manager);var trade=new BusinessTrade(db,manager);
  for(int round=0;round<5;round++){
   long order=100+round,receipt=200+round;seed(order);worker("SUCCESS",0,false).process();
   db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(?,?,1,'OVERTIME',500,'SUCCESS',?)",receipt,order,"EXTRA-"+order);
   db.update("UPDATE trade_order SET paid_overtime_fen=500,status='COMPLETED',completed_at=UTC_TIMESTAMP(3) WHERE reservation_id=?",order);
   long id=Long.parseLong(api.apply(Map.of("reservationId",order,"amountFen",700,"reason","mysql race"),"apply-"+order,actor("DRIVER")).get("id").toString());
   var decision=Map.<String,Object>of("decision","APPROVE","note","verified");
   var approved=race(()->api.decide(id,decision,"a-"+id,actor("PLATFORM")),()->api.decide(id,decision,"b-"+id,actor("PLATFORM")));
   check(Collections.frequency(approved,"OK")==1&&approved.contains("APPLICATION_DECIDED"),"Double decision "+approved);
   check(db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE application_id=?",Integer.class,id)==2,"Receipt split duplicated");
   worker("FAILED",0,false).process();
   long failed=db.queryForObject("SELECT MIN(id) FROM payment_attempt WHERE application_id=?",Long.class,id);
   var retry=race(()->trade.retryRefund(failed,"r1-"+id,Map.of("reason","verified"),actor("PLATFORM")),()->trade.retryRefund(failed,"r2-"+id,Map.of("reason","verified"),actor("PLATFORM")));
   check(retry.equals(List.of("OK","OK")),"Retry race "+retry);
   check(db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE retry_of_id=?",Integer.class,failed)==1,"Forked retry");
   var recovery=race(()->trade.mapHistory(0,actor("PLATFORM")),()->{worker("SUCCESS",0,false).process();return null;});
   check(recovery.equals(List.of("OK","OK")),"Mapping race "+recovery);
   long remainingFailed=db.queryForObject("SELECT MIN(p.id) FROM payment_attempt p WHERE application_id=? AND status='FAILED' AND NOT EXISTS(SELECT 1 FROM payment_attempt s WHERE s.retry_of_id=p.id)",Long.class,id);
   long competing=Long.parseLong(api.apply(Map.of("reservationId",order,"amountFen",400,"reason","competing approval"),"competing-"+order,actor("DRIVER")).get("id").toString());
   var competition=race(()->api.decide(competing,decision,"approve-competing-"+order,actor("PLATFORM")),()->trade.retryRefund(remainingFailed,"retry-competing-"+order,Map.of("reason","verified"),actor("PLATFORM")));
   check(Collections.frequency(competition,"OK")==1&&competition.contains("REFUND_AMOUNT_CONFLICT"),"Approval/retry quota race "+competition);
   worker("SUCCESS",0,false).process();
   check(db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE application_id=? AND status<>'FAILED'",Long.class,id)<=700,"Parent quota exceeded");
   check(db.queryForObject("SELECT refund_fen FROM trade_order WHERE reservation_id=?",Long.class,order)<=1000,"Order quota exceeded");
   check(db.queryForObject("SELECT COUNT(*) FROM (SELECT a.receipt_id FROM refund_receipt_allocation a JOIN payment_attempt p ON p.id=a.attempt_id JOIN payment_attempt r ON r.id=a.receipt_id WHERE p.reservation_id=? AND p.status<>'FAILED' GROUP BY a.receipt_id,r.amount_fen HAVING SUM(a.amount_fen)>r.amount_fen) exceeded",Integer.class,order)==0,"Receipt quota exceeded");
  }
  System.out.println("PASS mysql-double-approval-five-rounds");
  System.out.println("PASS mysql-retry-single-successor-five-rounds");
  System.out.println("PASS mysql-history-worker-race-five-rounds");
  System.out.println("PASS mysql-three-level-multiple-receipt-quota-five-rounds");
  System.out.println("PASS mysql-approval-versus-retry-quota-five-rounds");
 }
 static void tests()throws Exception{
  seed(1);worker("FAILED",0,false).process();check(state(1).equals("FAILED")&&ledger(1)==0,"Failed receipt posted");System.out.println("PASS definitive-failure-no-ledger");
  seed(2);worker("UNKNOWN",0,false).process();for(int i=0;i<5;i++){due(2);worker("SUCCESS",0,false).process();}
  check(state(2).equals("REVIEW")&&ledger(2)==0,"Unknown budget not retained");check(db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE channel_no='FAULT-2' AND operation='SUBMIT'",Integer.class)==1,"Unknown resubmitted");check(db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE channel_no='FAULT-2' AND operation='QUERY'",Integer.class)==5,"Query budget mismatch");System.out.println("PASS unknown-five-queries-review-no-resubmit");
  seed(3);db.update("UPDATE payment_attempt SET status='SUBMITTED',retry_count=1,lease_until='2000-01-01',lease_token='crashed' WHERE id=3");worker("SUCCESS",0,false).process();check(state(3).equals("UNKNOWN")&&ledger(3)==0,"Claim crash charged");check(db.queryForObject("SELECT COUNT(*) FROM channel_operation WHERE channel_no='FAULT-3'",Integer.class)==0,"Query created receipt");db.update("UPDATE payment_attempt SET status='REVIEW' WHERE id=3");System.out.println("PASS claim-before-submit-crash-query-only");
  seed(4);worker("SUCCESS",0,true).process();check(state(4).equals("UNKNOWN")&&ledger(4)==0,"Lost response not unknown");due(4);worker("SUCCESS",0,false).process();check(state(4).equals("SUCCESS")&&ledger(4)==1,"Lost response not recovered");System.out.println("PASS committed-receipt-lost-response-recovered-once");
  seed(5);try(var executor=Executors.newSingleThreadExecutor()){
   var slow=executor.submit(()->worker("SUCCESS",3000,false).process());
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(db.queryForObject("SELECT COUNT(*) FROM channel_operation WHERE channel_no='FAULT-5'",Integer.class)==0){check(System.nanoTime()<deadline,"Slow worker did not submit");Thread.sleep(20);}
   Thread.sleep(1200);worker("SUCCESS",0,false).process();slow.get(10,TimeUnit.SECONDS);
  }
  check(state(5).equals("SUCCESS")&&ledger(5)==1,"Expired lease double posted");check(db.queryForObject("SELECT paid_prepay_fen FROM trade_order WHERE reservation_id=5",Long.class)==500,"Fencing amount mismatch");System.out.println("PASS delayed-old-worker-fenced-after-lease-takeover");
  boolean rejected=false;try{new PaymentWorker(db,manager,5,30,5,60,false,"UNKNOWN",0,false);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Non-demo fault enabled");System.out.println("PASS non-demo-fault-rejected");
 }
 static void mappingIsolation(){
  for(long id=3000;id<3020;id++){seed(id);db.update("UPDATE payment_attempt SET kind='REFUND' WHERE id=?",id);}
  seed(3020);worker("SUCCESS",0,false).process();worker("SUCCESS",0,false).process();
  check(db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE id BETWEEN 3000 AND 3019 AND status='REVIEW' AND last_error='REFUND_HISTORY_CONFLICT'",Integer.class)==20,"Bad tasks not isolated");
  check(state(3020).equals("SUCCESS"),"Normal payment starved");
  System.out.println("PASS mysql-twenty-invalid-mappings-do-not-starve-payment");
  seed(4000);worker("SUCCESS",0,false).process();
  db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(4001,4000,1,'REFUND',200,'CREATED','BAD-ALLOCATION')");
  db.update("INSERT INTO refund_receipt_allocation VALUES(4001,4000,100)");
  new BusinessTrade(db,manager).mapHistory(3999,actor("PLATFORM"));worker("SUCCESS",0,false).process();
  check(state(4001).equals("REVIEW"),"Incomplete mapping executed");
  check(db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE channel_no='BAD-ALLOCATION'",Integer.class)==0,"Invalid mapping called channel");
  check(db.queryForObject("SELECT COUNT(*) FROM refund_application WHERE reservation_id=4000",Integer.class)==0,"Mapping transaction did not roll back");
  System.out.println("PASS mysql-incomplete-allocation-rollback-no-channel-call");
 }
 public static void main(String[] args)throws Exception{
  String schema=args[0];check(schema.matches("w78_parking_[0-9]{14}"),"Unsafe schema");String server="jdbc:mysql://127.0.0.1:23306/",password=System.getenv("MYSQL_ROOT_PASSWORD");boolean created=false;
  try(var c=DriverManager.getConnection(server,"root",password);var sql=c.createStatement()){
   try{sql.executeUpdate("CREATE DATABASE "+schema);created=true;String url=server+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    Flyway.configure().dataSource(url,"root",password).locations("filesystem:backend/trade-service/src/main/resources/db/business").load().migrate();
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword(password);config.setMaximumPoolSize(10);config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    try(var ds=new HikariDataSource(config)){db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tests();refundConcurrency();mappingIsolation();}
   }finally{if(created){sql.executeUpdate("DROP DATABASE "+schema);System.out.println("CLEANUP isolated-schema-dropped");}}
  }
 }
}
