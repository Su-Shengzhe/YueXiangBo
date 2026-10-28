package cn.community.parkingservice;
import cn.community.foundation.*;
import java.util.*;
import java.time.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.ApplicationRunner;
@RestController @Profile("business")
public class BusinessParking implements Events.Handler {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final BusinessClock clock;
 public BusinessParking(JdbcTemplate db,PlatformTransactionManager manager,BusinessClock clock){this.db=db;this.tx=new TransactionTemplate(manager);this.clock=clock;}
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="business.demo",havingValue="true") ApplicationRunner demoParking(){return args->{if(db.queryForObject("SELECT COUNT(*) FROM community",Integer.class)==0)tx.executeWithoutResult(s->{
  db.update("INSERT INTO community(id,name) VALUES(10,'青禾智慧社区')");
  db.update("INSERT INTO vehicle(id,user_id,plate) VALUES(40,1,'粤BDEMO01'),(41,2,'粤BDEMO02')");
  db.update("INSERT INTO parking_space(id,community_id,owner_id,name,space_code,hourly_fen,review_status) VALUES(20,10,3,'A区-020','A区-020',500,'APPROVED')");
  db.update("INSERT INTO share_window(id,space_id,start_at,end_at,hourly_fen) VALUES(30,20,?,?,500)",Data.ts(clock.now()),Data.ts(clock.now().plus(Duration.ofDays(30))));
 });};}
 private Map<String,Object> one(String sql,Object...args){var list=db.queryForList(sql,args);Problem.require(!list.isEmpty(),404,"NOT_FOUND","记录不存在");return list.getFirst();}
 private Map<String,Object> order(long id){return one("SELECT * FROM reservation_order WHERE id=?",id);}
 private void own(Map<String,Object> o,Actor a){Problem.require(((Number)o.get("user_id")).longValue()==a.id(),404,"NOT_FOUND","记录不存在");}
 private Map<String,Object> view(Map<String,Object> o){var v=Data.row(o);v.put("reservationId",v.remove("id"));return v;}
 private void event(String type,Map<String,Object> order){Events.add(db,"trade-service",type,Data.row(order));}
 private void lock(long vehicle,long space){var p=one("SELECT community_id FROM parking_space WHERE id=?",space);one("SELECT id FROM community WHERE id=? FOR UPDATE",p.get("community_id"));one("SELECT id FROM vehicle WHERE id=? FOR UPDATE",vehicle);one("SELECT id FROM parking_space WHERE id=? FOR UPDATE",space);}
 private void lock(Map<String,Object> o){lock(((Number)o.get("vehicle_id")).longValue(),((Number)o.get("space_id")).longValue());one("SELECT id FROM reservation_order WHERE id=? FOR UPDATE",o.get("id"));}
 private Instant[] interval(String start,String end){Instant a=Instant.parse(start),b=Instant.parse(end),now=clock.now();long secs=Duration.between(a,b).getSeconds();
  Problem.require(a.getNano()==0&&b.getNano()==0&&a.getEpochSecond()%900==0&&b.getEpochSecond()%900==0,422,"TIME_ALIGNMENT","时间需按15分钟对齐");
  Problem.require(!a.isBefore(now.plusSeconds(900))&&secs>=1800&&secs<=86400&&!b.isAfter(now.plus(Duration.ofDays(30))),422,"INVALID_INTERVAL","提前15分钟预约，时长30分钟至24小时，限未来30天");return new Instant[]{a,b};}
 @GetMapping("/api/v1/communities") public List<?> communities(){return db.queryForList("SELECT id,name,address FROM community WHERE enabled=TRUE ORDER BY id").stream().map(Data::row).toList();}
 @GetMapping("/api/v1/vehicles") public List<?> vehicles(HttpServletRequest req){Actor a=Actor.of(req);a.role("DRIVER");return db.queryForList("SELECT id,plate,version FROM vehicle WHERE user_id=? AND enabled=TRUE ORDER BY id",a.id()).stream().map(Data::row).toList();}
 @GetMapping("/api/v1/spaces/available") public List<?> spaces(@RequestParam long communityId,@RequestParam String startAt,@RequestParam String endAt){
  return (List<?>)search(communityId,startAt,endAt,"",null,1,100).get("items");
 }
 @GetMapping("/api/v1/spaces/search") public Map<String,Object> search(@RequestParam long communityId,@RequestParam String startAt,@RequestParam String endAt,@RequestParam(defaultValue="")String spaceType,@RequestParam(required=false)Long maxHourlyFen,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
  var t=interval(startAt,endAt);Problem.require(spaceType.isEmpty()||Set.of("STANDARD","COMPACT","EV").contains(spaceType),422,"INVALID_SPACE_TYPE","车位类型无效");Problem.require(maxHourlyFen==null||maxHourlyFen>=0,422,"INVALID_PRICE","最高价格必须非负");
  String from="FROM parking_space s JOIN community c ON c.id=s.community_id JOIN share_window w ON w.space_id=s.id WHERE s.community_id=? AND c.enabled=TRUE AND s.enabled=TRUE AND s.review_status='APPROVED' AND w.enabled=TRUE AND w.start_at<=? AND w.end_at>=? AND NOT EXISTS(SELECT 1 FROM reservation_order r WHERE r.space_id=s.id AND r.start_at<? AND r.end_at>? AND (r.status IN ('CONFIRMED','IN_USE') OR (r.status='WAIT_PAY' AND r.pay_deadline>?)))";
  var args=new ArrayList<Object>(List.of(communityId,Data.ts(t[0]),Data.ts(t[1]),Data.ts(t[1]),Data.ts(t[0]),Data.ts(clock.now())));if(!spaceType.isEmpty()){from+=" AND s.space_type=?";args.add(spaceType);}if(maxHourlyFen!=null){from+=" AND w.hourly_fen<=?";args.add(maxHourlyFen);}
  var result=Management.page(db,"s.id,s.name,s.community_id,s.location_desc,s.space_type,w.id AS window_id,w.version AS window_version,w.hourly_fen",from,"s.id,w.id",page,size,args.toArray());
  @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");for(var item:items)item.put("estimatedFen",Data.fee(((Number)item.get("hourlyFen")).longValue(),Duration.between(t[0],t[1]).getSeconds()));return result;
 }
 @PostMapping("/api/v1/reservations") public Map<String,Object> reserve(@RequestBody Map<String,Object> in,@RequestHeader("Idempotency-Key")String key,HttpServletRequest req){
  Actor a=Actor.of(req);a.role("DRIVER");Requests.key(key);long vehicle=Data.number(in,"vehicleId"),space=Data.number(in,"spaceId");
  String hash=Data.hash(vehicle+":"+space+":"+Data.str(in,"startAt")+":"+Data.str(in,"endAt")+(in.containsKey("windowId")?":"+Data.str(in,"windowId")+":"+Data.str(in,"windowVersion")+":"+Data.str(in,"expectedHourlyFen")+":"+Data.str(in,"expectedPrepayFen"):""));
  return tx.execute(s->{
   lock(vehicle,space);var previous=Requests.existing(db,a.id(),"RESERVE",key,hash);if(previous!=null)return previous;
   var v=one("SELECT * FROM vehicle WHERE id=?",vehicle);Problem.require(((Number)v.get("user_id")).longValue()==a.id(),403,"VEHICLE_FORBIDDEN","只能使用本人车辆");
   Problem.require(Boolean.TRUE.equals(v.get("enabled")),409,"VEHICLE_DISABLED","车辆已停用");
   var p=one("SELECT * FROM parking_space WHERE id=?",space);Problem.require(Boolean.TRUE.equals(p.get("enabled")),409,"SPACE_DISABLED","车位不可用");
   Problem.require(p.get("review_status").equals("APPROVED"),409,"SPACE_NOT_APPROVED","车位尚未审核通过");
   Problem.require(Boolean.TRUE.equals(one("SELECT enabled FROM community WHERE id=?",p.get("community_id")).get("enabled")),409,"COMMUNITY_DISABLED","社区已停用");
   var t=interval(Data.str(in,"startAt"),Data.str(in,"endAt"));Instant now=clock.now();
   var windows=db.queryForList("SELECT * FROM share_window WHERE space_id=? AND enabled=TRUE AND start_at<=? AND end_at>=? FOR UPDATE",space,Data.ts(t[0]),Data.ts(t[1]));
   Problem.require(windows.size()==1,409,"OUTSIDE_SHARE_WINDOW","需要唯一完整共享窗口覆盖预约");var window=windows.getFirst();long hourly=((Number)window.get("hourly_fen")).longValue(),prepay=Data.fee(hourly,Duration.between(t[0],t[1]).getSeconds());
   if(in.containsKey("windowId"))Problem.require(Data.number(in,"windowId")==((Number)window.get("id")).longValue()&&Data.number(in,"windowVersion")==((Number)window.get("version")).longValue()&&Data.number(in,"expectedHourlyFen")==hourly&&Data.number(in,"expectedPrepayFen")==prepay,409,"QUOTE_CHANGED","报价或窗口已变化，请重新查询并确认");
   int conflicts=db.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE (vehicle_id=? OR space_id=?) AND start_at<? AND end_at>? AND (status IN ('CONFIRMED','IN_USE') OR (status='WAIT_PAY' AND pay_deadline>?))",Integer.class,vehicle,space,Data.ts(t[1]),Data.ts(t[0]),Data.ts(now));
   Problem.require(conflicts==0,409,"RESERVATION_CONFLICT","车位或车辆在该时段已有预约");
   long id=Data.id(db);db.update("INSERT INTO reservation_order(id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,status,window_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,'WAIT_PAY',?)",id,a.id(),vehicle,space,p.get("community_id"),v.get("plate"),hourly,Data.ts(t[0]),Data.ts(t[1]),Data.ts(now.plusSeconds(600)),prepay,window.get("id"));
   var o=order(id);event("ReservationCreated",o);var result=view(o);Requests.save(db,a.id(),"RESERVE",key,hash,result);return result;
  });
 }
 @GetMapping("/api/v1/reservations") public List<?> list(HttpServletRequest req){return db.queryForList("SELECT * FROM reservation_order WHERE user_id=? ORDER BY id DESC LIMIT 100",Actor.of(req).id()).stream().map(this::view).toList();}
 @GetMapping("/api/v1/reservations/{id}") public Map<String,Object> detail(@PathVariable long id,HttpServletRequest req){var o=order(id);own(o,Actor.of(req));return view(o);}
 @PostMapping("/api/v1/reservations/{id}/cancel") public Map<String,Object> cancel(@PathVariable long id,HttpServletRequest req){
  Actor a=Actor.of(req);return tx.execute(s->{var o=order(id);own(o,a);lock(o);o=order(id);String status=Data.str(o,"status");if(List.of("CANCELLED","EXPIRED","NO_SHOW","SPACE_UNAVAILABLE").contains(status))return view(o);
   Problem.require(List.of("WAIT_PAY","CONFIRMED").contains(status)&&clock.now().isBefore(Data.instant(o.get("start_at"))),409,"CANCEL_NOT_ALLOWED","当前预约不能取消");
   db.update("UPDATE reservation_order SET status='CANCELLED',close_reason='USER_CANCELLED',version=version+1 WHERE id=?",id);o=order(id);event("ReservationClosed",o);return view(o);
  });
 }
 @GetMapping("/api/v1/property/reservations") public List<?> property(@RequestParam(defaultValue="10")long communityId,HttpServletRequest req){
  Actor.of(req).community(communityId);return db.queryForList("SELECT * FROM reservation_order WHERE community_id=? ORDER BY id DESC LIMIT 100",communityId).stream().map(this::view).toList();
 }
 @PostMapping("/api/v1/property/reservations/{id}/{action}") public Map<String,Object> gate(@PathVariable long id,@PathVariable String action,@RequestHeader("Idempotency-Key")String key,HttpServletRequest req){
  Actor a=Actor.of(req);Requests.key(key);Problem.require(action.equals("entry")||action.equals("exit"),404,"NOT_FOUND","操作不存在");
  var outcome=tx.execute(s->{var o=order(id);a.community(((Number)o.get("community_id")).longValue());lock(o);o=order(id);String hash=Data.hash(id+":"+action),op="GATE_"+action;var prior=Requests.existing(db,a.id(),op,key,hash);if(prior!=null)return prior;
   Instant now=clock.now();String status=Data.str(o,"status");
   if(action.equals("entry")){
    Problem.require(status.equals("CONFIRMED")&&!now.isBefore(Data.instant(o.get("start_at")))&&now.isBefore(Data.instant(o.get("start_at")).plusSeconds(900)),409,"ENTRY_NOT_ALLOWED","请在预约开始后15分钟内入场");
    int n=db.update("UPDATE parking_space SET occupied_reservation_id=? WHERE id=? AND occupied_reservation_id IS NULL",id,o.get("space_id"));if(n==0){
     db.update("UPDATE reservation_order SET entry_blocked_at=? WHERE id=?",Data.ts(now),id);
     db.update("INSERT INTO audit_log(actor_id,action,resource_id) VALUES(?,'ENTRY_BLOCKED',?)",a.id(),Long.toString(id));
     return Map.<String,Object>of("_error","SPACE_OCCUPIED");
    }
    db.update("UPDATE reservation_order SET status='IN_USE',entered_at=?,version=version+1 WHERE id=?",Data.ts(now),id);
    event("ParkingEntered",order(id));
   }else{
    Problem.require(status.equals("IN_USE"),409,"EXIT_NOT_ALLOWED","仅在场车辆可离场");long overtime=Data.fee(((Number)o.get("hourly_fen")).longValue(),Duration.between(Data.instant(o.get("end_at")),now.isBefore(Data.instant(o.get("end_at")))?Data.instant(o.get("end_at")):now));
    db.update("UPDATE reservation_order SET status='COMPLETED',exited_at=?,overtime_fen=?,version=version+1 WHERE id=?",Data.ts(now),overtime,id);
    db.update("UPDATE parking_space SET occupied_reservation_id=NULL WHERE id=? AND occupied_reservation_id=?",o.get("space_id"),id);
    event("ParkingCompleted",order(id));
   }
   db.update("INSERT INTO audit_log(actor_id,action,resource_id) VALUES(?,?,?)",a.id(),action,Long.toString(id));
   var result=view(order(id));Requests.save(db,a.id(),op,key,hash,result);return result;
  });
  // Commit the blocked-arrival fact before returning the conflict to the caller.
  if(outcome.containsKey("_error"))throw new Problem(409,"SPACE_OCCUPIED","车位仍被占用，已记录受阻到场");
  return outcome;
 }
 @GetMapping("/api/v1/demo/clock") public Map<String,Object> time(){return Map.of("now",clock.now().toString(),"mode",clock.isDemo()?"LOCAL_DEMO":"SYSTEM");}
 @PostMapping("/api/v1/demo/clock") public Map<String,Object> advance(@RequestBody Map<String,Object> in,HttpServletRequest req){
  Actor a=Actor.of(req);a.role("PLATFORM");Instant at=Instant.parse(Data.str(in,"now"));
  Problem.require(!at.isAfter(clock.now().plus(Duration.ofDays(30))),422,"CLOCK_RANGE","每次最多推进30天");clock.advance(at);
  db.update("INSERT INTO audit_log(actor_id,action,resource_id) VALUES(?,'DEMO_CLOCK',?)",a.id(),at.toString());return time();
 }
 @Scheduled(fixedDelay=2000) public void expire(){
  for(var candidate:db.queryForList("SELECT * FROM reservation_order WHERE (status='WAIT_PAY' AND pay_deadline<=?) OR (status='CONFIRMED' AND start_at<=?) ORDER BY vehicle_id,space_id LIMIT 100",Data.ts(clock.now()),Data.ts(clock.now().minusSeconds(900)))){
   tx.executeWithoutResult(s->{lock(candidate);var o=order(((Number)candidate.get("id")).longValue());String state=Data.str(o,"status");String next=null;
    if(state.equals("WAIT_PAY")&&!clock.now().isBefore(Data.instant(o.get("pay_deadline"))))next="EXPIRED";
    if(state.equals("CONFIRMED")&&!clock.now().isBefore(Data.instant(o.get("start_at")).plusSeconds(900))){
     var space=one("SELECT occupied_reservation_id FROM parking_space WHERE id=?",o.get("space_id"));
     next=o.get("entry_blocked_at")!=null||space.get("occupied_reservation_id")!=null?"SPACE_UNAVAILABLE":"NO_SHOW";
    }
    if(next!=null){db.update("UPDATE reservation_order SET status=?,close_reason=?,version=version+1 WHERE id=?",next,next.equals("EXPIRED")?"PAYMENT_TIMEOUT":next.equals("NO_SHOW")?"DRIVER_NO_SHOW":"SPACE_UNAVAILABLE",o.get("id"));event("ReservationClosed",order(((Number)o.get("id")).longValue()));}
   });
  }
 }
 public void accept(String type,Map<String,Object> p){
  Problem.require(type.equals("PaymentSucceeded"),422,"EVENT_TYPE","Unsupported parking event");long id=Data.number(p,"reservationId");var o=order(id);lock(o);o=order(id);
  if(!Data.str(p,"kind").equals("PREPAY"))return;
  Problem.require(Data.number(p,"amountFen")==((Number)o.get("prepay_fen")).longValue(),409,"PAYMENT_AMOUNT","Payment amount mismatch");
  String status=Data.str(o,"status");
  if(status.equals("WAIT_PAY")&&clock.now().isBefore(Data.instant(o.get("pay_deadline")))){
   db.update("UPDATE reservation_order SET status='CONFIRMED',version=version+1 WHERE id=?",id);event("ReservationConfirmed",order(id));
  }else if(List.of("WAIT_PAY","EXPIRED","CANCELLED","NO_SHOW","SPACE_UNAVAILABLE").contains(status)){
   if(status.equals("WAIT_PAY"))db.update("UPDATE reservation_order SET status='EXPIRED',close_reason='PAYMENT_TIMEOUT',version=version+1 WHERE id=?",id);
   event("ReservationClosed",order(id));
  }
 }
}
