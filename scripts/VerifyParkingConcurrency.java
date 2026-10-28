import cn.community.parkingservice.*;
import cn.community.foundation.*;
import com.zaxxer.hikari.*;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated real-MySQL verification of the packaged business code; deliberately excludes HTTP/auth. */
class VerifyParkingConcurrency {
 static class Clock extends BusinessClock {volatile Instant at=Instant.parse("2030-01-01T00:00:00Z");Clock(){super("",false);}public Instant now(){return at;}}
 static Clock clock=new Clock();static JdbcTemplate db;static BusinessParking api;static ResourceManagement resources;static TransactionTemplate tx;
 static HttpServletRequest actor(long id,String role){return (HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getAttribute")&&a[0].equals("actor")?new Actor(id,Set.of(role),Set.of(10L)):null);}
 static void require(boolean v,String reason){if(!v)throw new IllegalStateException(reason);}
 static String key(){return UUID.randomUUID().toString();}
 static Map<String,Object> body(int vehicle,int space){return Map.of("vehicleId",vehicle,"spaceId",space,"startAt","2030-01-01T01:00:00Z","endAt","2030-01-01T02:00:00Z");}
 static long reserve(int vehicle,int space,String key){return Long.parseLong(api.reserve(body(vehicle,space),key,actor(vehicle,"DRIVER")).get("reservationId").toString());}
 static String state(long id){return db.queryForObject("SELECT status FROM reservation_order WHERE id=?",String.class,id);}
 static int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
 static void reset(int n,boolean separate){
  clock.at=Instant.parse("2030-01-01T00:00:00Z");
  for(String t:List.of("parking_event","parking_request_guard","idempotency_record","audit_log","outbox_event","reservation_order","share_window","parking_space","vehicle","community"))db.update("DELETE FROM "+t);
  for(int i=1;i<=n;i++){
   if(separate||i==1)db.update("INSERT INTO community(id,name) VALUES(?,?)",separate?10+i:10,"Test "+i);
   db.update("INSERT INTO vehicle(id,user_id,plate) VALUES(?,?,?)",i,i,"TEST"+i);
   db.update("INSERT INTO parking_space(id,community_id,owner_id,name,space_code,hourly_fen,review_status) VALUES(?,?,3,?,?,600,'APPROVED')",i,separate?10+i:10,"Space"+i,"S"+i);
   db.update("INSERT INTO share_window(id,space_id,start_at,end_at,hourly_fen) VALUES(?,?,?,?,600)",i,i,Data.ts(Instant.parse("2030-01-01T01:00:00Z")),Data.ts(Instant.parse("2030-01-01T04:00:00Z")));
  }
 }
 static String outcome(Callable<?> c)throws Exception{try{c.call();return "OK";}catch(Problem p){return p.code;}}
 static List<String> race(List<Callable<?>> actions)throws Exception{
  var start=new CyclicBarrier(actions.size());var results=new ArrayList<String>();
  try(var pool=Executors.newFixedThreadPool(actions.size())){var futures=new ArrayList<Future<String>>();for(var a:actions)futures.add(pool.submit(()->{start.await(30,TimeUnit.SECONDS);return outcome(a);}));for(var f:futures)results.add(f.get(60,TimeUnit.SECONDS));}return results;
 }
 static void gate(long id,String type,String event){api.parkingEvent(Map.of("reservationId",id,"type",type,"eventId",event),actor(4,"PROPERTY"));}
 static void pay(long id){tx.executeWithoutResult(s->api.accept("PaymentSucceeded",Map.of("reservationId",id,"kind","PREPAY","amountFen",600)));}
 static void correctness()throws Exception{
  for(int round=1;round<=3;round++){
   reset(100,false);var actions=new ArrayList<Callable<?>>();for(int i=1;i<=100;i++){int v=i;actions.add(()->reserve(v,1,key()));}
   var r=race(actions);require(Collections.frequency(r,"OK")==1&&Collections.frequency(r,"RESERVATION_CONFLICT")==99,"100 race "+r);
   require(count("reservation_order")==1&&count("outbox_event")==1&&count("idempotency_record")==1&&count("parking_request_guard")==1,"Race rollback invariants");
   System.out.println("PASS contest round="+round+" requests=100 success=1 RESERVATION_CONFLICT=99 orders=1 outbox=1 guards=1");
  }
  for(String mode:List.of("same-key","different-params","same-vehicle","window-disable","payment-cancel","payment-expiry","entry-expiry","entry-unavailable","duplicate-entry","duplicate-exit")){
   for(int round=0;round<10;round++){
    reset(2,false);String k=key();List<String> r;
    if(mode.equals("same-key")){r=race(List.of(()->reserve(1,1,k),()->reserve(1,1,k)));require(r.equals(List.of("OK","OK"))&&count("reservation_order")==1&&count("outbox_event")==1,"Same key "+r);}
    else if(mode.equals("different-params")){r=race(List.of(()->api.reserve(body(1,1),k,actor(1,"DRIVER")),()->api.reserve(body(1,2),k,actor(1,"DRIVER"))));require(r.contains("OK")&&r.contains("IDEMPOTENCY_CONFLICT")&&count("reservation_order")==1,"Changed key "+r);}
    else if(mode.equals("same-vehicle")){r=race(List.of(()->reserve(1,1,key()),()->reserve(1,2,key())));require(r.contains("OK")&&r.contains("RESERVATION_CONFLICT")&&count("reservation_order")==1,"Vehicle "+r);}
    else if(mode.equals("window-disable")){r=race(List.of(()->reserve(1,1,k),()->resources.editWindow(1,Map.of("version",1,"enabled",false),actor(3,"OWNER"))));require(r.equals(List.of("OK","ACTIVE_RESERVATION"))||r.equals(List.of("OUTSIDE_SHARE_WINDOW","OK")),"Window "+r);}
    else {
     long id=reserve(1,1,k);
     if(mode.equals("payment-cancel")){r=race(List.of(()->{pay(id);return null;},()->api.cancel(id,actor(1,"DRIVER"))));require(r.equals(List.of("OK","OK"))&&state(id).equals("CANCELLED"),"Cancel/payment "+r);}
     else if(mode.equals("payment-expiry")){clock.at=clock.at.plusSeconds(600);r=race(List.of(()->{pay(id);return null;},()->{api.expire();return null;}));require(r.equals(List.of("OK","OK"))&&state(id).equals("EXPIRED"),"Expiry/payment "+r);}
     else {
      pay(id);clock.at=Instant.parse("2030-01-01T01:00:00Z");
      if(mode.equals("entry-expiry")){clock.at=clock.at.plusSeconds(900);r=race(List.of(()->{gate(id,"ENTRY",k);return null;},()->{api.expire();return null;}));require(r.equals(List.of("ENTRY_NOT_ALLOWED","OK"))&&state(id).equals("NO_SHOW"),"Entry expiry "+r);}
      else if(mode.equals("entry-unavailable")){r=race(List.of(()->{gate(id,"ENTRY",k);return null;},()->api.unavailable(id,Map.of("reason","test obstruction"),key(),actor(4,"PROPERTY"))));require(r.equals(List.of("OK","UNAVAILABLE_NOT_ALLOWED"))&&state(id).equals("IN_USE")||r.equals(List.of("ENTRY_NOT_ALLOWED","OK"))&&state(id).equals("SPACE_UNAVAILABLE"),"Unavailable "+r);}
      else {String type=mode.equals("duplicate-entry")?"ENTRY":"EXIT";if(type.equals("EXIT"))gate(id,"ENTRY",key());int before=count("outbox_event");r=race(List.of(()->{gate(id,type,k);return null;},()->{gate(id,type,k);return null;}));require(r.equals(List.of("OK","OK"))&&count("outbox_event")==before+1&&state(id).equals(type.equals("ENTRY")?"IN_USE":"COMPLETED"),"Duplicate gate "+r);}
      Long occupied=db.queryForObject("SELECT occupied_reservation_id FROM parking_space WHERE id=1",Long.class);require(state(id).equals("IN_USE")?Objects.equals(occupied,id):occupied==null,"Occupancy inconsistent");
     }
    }
   }
   System.out.println("PASS race="+mode+" rounds=10 state-and-side-effects-verified");
  }
 }
 static void load()throws Exception{
  for(boolean separate:List.of(false,true))for(int concurrency:List.of(10,25,50)){
   reset(concurrency,separate);
   System.out.println("START load communities="+(separate?"different":"same")+" concurrency="+concurrency+" warmupSeconds=30 measureSeconds=120");System.out.flush();
   long begin=System.nanoTime(),measure=begin+TimeUnit.SECONDS.toNanos(30),end=measure+TimeUnit.SECONDS.toNanos(120);
   var latencies=new ConcurrentLinkedQueue<Long>();var errors=new ConcurrentHashMap<String,AtomicInteger>();var warmErrors=new AtomicInteger();
   try(var pool=Executors.newFixedThreadPool(concurrency)){
    var futures=new ArrayList<Future<?>>();for(int i=1;i<=concurrency;i++){int v=i;futures.add(pool.submit(()->{while(System.nanoTime()<end){long start=System.nanoTime();boolean measured=start>=measure;try{long id=reserve(v,v,key());api.cancel(id,actor(v,"DRIVER"));if(measured)latencies.add(System.nanoTime()-start);}catch(Exception e){if(measured)errors.computeIfAbsent(e instanceof Problem p?p.code:e.getClass().getSimpleName(),x->new AtomicInteger()).incrementAndGet();else warmErrors.incrementAndGet();}}}));}for(var f:futures)f.get(180,TimeUnit.SECONDS);
   }
   var sorted=new ArrayList<>(latencies);Collections.sort(sorted);require(!sorted.isEmpty(),"No load samples");
   int active=db.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE status<>'CANCELLED'",Integer.class);
   System.out.printf(Locale.ROOT,"METRIC communities=%s concurrency=%d cycles=%d cyclesPerSecond=%.2f p50Ms=%.2f p95Ms=%.2f p99Ms=%.2f errors=%s warmErrors=%d active=%d%n",separate?"different":"same",concurrency,sorted.size(),sorted.size()/120.0,percentile(sorted,.50),percentile(sorted,.95),percentile(sorted,.99),errors,warmErrors.get(),active);
   require(errors.isEmpty()&&warmErrors.get()==0&&active==0,"Load failure");require(count("outbox_event")==2*count("reservation_order"),"Load missing outbox");
   System.out.println("PASS load communities="+(separate?"different":"same")+" concurrency="+concurrency+" no-errors no-active-orders outbox-atomic");System.out.flush();
  }
 }
 static double percentile(List<Long> values,double p){return values.get((int)Math.ceil(values.size()*p)-1)/1e6;}
 public static void main(String[] args)throws Exception{
  String schema=args[0];require(schema.matches("w78_parking_[0-9]{14}"),"Unsafe schema");require(Set.of("correctness","load").contains(args[1]),"Invalid mode");
  String server="jdbc:mysql://127.0.0.1:23306/",password=System.getenv("MYSQL_ROOT_PASSWORD");boolean created=false;
  try(var c=DriverManager.getConnection(server,"root",password);var sql=c.createStatement()){
   try{sql.executeUpdate("CREATE DATABASE "+schema);created=true;String url=server+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    Flyway.configure().dataSource(url,"root",password).locations("filesystem:backend/parking-service/src/main/resources/db/business").load().migrate();
    var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("root");config.setPassword(password);config.setMaximumPoolSize(100);config.setMinimumIdle(10);config.setConnectionTimeout(30000);
    var properties=new Properties();try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("backend/parking-service/src/main/resources/application-business.properties"))){properties.load(reader);}
    config.setTransactionIsolation(properties.getProperty("spring.datasource.hikari.transaction-isolation"));
    try(var ds=new HikariDataSource(config)){db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);api=new BusinessParking(db,manager,clock);resources=new ResourceManagement(db,manager,clock);
     try(var check=ds.getConnection()){require(check.getTransactionIsolation()==Connection.TRANSACTION_READ_COMMITTED,"Isolation differs from application");System.out.println("ENV mysql="+c.getMetaData().getDatabaseProductVersion()+" java="+System.getProperty("java.version")+" isolation=READ_COMMITTED poolMax=100");}if(args[1].equals("load"))load();else correctness();
    }
   }finally{if(created){sql.executeUpdate("DROP DATABASE "+schema);System.out.println("CLEANUP isolated-schema-dropped");}}
  }
 }
}
