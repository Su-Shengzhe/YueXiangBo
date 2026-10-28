package cn.community.parkingservice;

import cn.community.foundation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationRecoveryTest {
 ResourceManagementTest f;
 @BeforeEach void setup() throws Exception {f=new ResourceManagementTest();f.setup();f.createWindow();}
 Map<String,Object> body(){return Map.of("vehicleId",40,"spaceId",20,"startAt","2030-01-01T01:00:00Z","endAt","2030-01-01T02:00:00Z");}
 long reserve(){return Long.parseLong(f.parking.reserve(body(),UUID.randomUUID().toString(),f.actor(1,"DRIVER")).get("reservationId").toString());}
 void confirmed(long id){f.db.update("UPDATE reservation_order SET status='CONFIRMED' WHERE id=?",id);}
 Map<String,Object> gate(long id,String type,String event){return f.parking.parkingEvent(Map.of("reservationId",id,"type",type,"eventId",event),f.actor(4,"PROPERTY",10L));}
 String state(long id){return f.db.queryForObject("SELECT status FROM reservation_order WHERE id=?",String.class,id);}
 @Test void sameKeyReplaysAfterDeadlineAndFindsOnlyOwnResult(){
  String key=UUID.randomUUID().toString();var original=f.parking.reserve(body(),key,f.actor(1,"DRIVER"));
  when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T03:00:00Z"));f.parking.expire();
  assertEquals(original.get("reservationId"),f.parking.reserve(body(),key,f.actor(1,"DRIVER")).get("reservationId"));
  assertEquals(original.get("reservationId"),f.parking.byRequest(key,f.actor(1,"DRIVER")).get("reservationId"));
  assertEquals(404,assertThrows(Problem.class,()->f.parking.byRequest(key,f.actor(2,"DRIVER"))).status);
  assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM reservation_order",Integer.class));
 }
 @Test void failedReservationRollsBackGuardAndEvents(){var invalid=new HashMap<>(body());invalid.put("vehicleId",41);String key=UUID.randomUUID().toString();assertThrows(Problem.class,()->f.parking.reserve(invalid,key,f.actor(1,"DRIVER")));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM parking_request_guard",Integer.class));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class));}
 @Test void concurrentDifferentParametersHaveStableIdempotencyConflict() throws Exception {
  f.db.update("INSERT INTO parking_space(id,community_id,owner_id,name,hourly_fen) VALUES(21,11,3,'B',600)");
  f.db.update("INSERT INTO vehicle(id,user_id,plate) VALUES(42,1,'粤B12347')");
  f.db.update("INSERT INTO share_window(id,space_id,start_at,end_at,hourly_fen) VALUES(31,21,?,?,600)",Data.ts(Instant.parse("2030-01-01T01:00:00Z")),Data.ts(Instant.parse("2030-01-01T04:00:00Z")));
  var other=new HashMap<>(body());other.put("spaceId",21);other.put("vehicleId",42);String key=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
  try(var pool=Executors.newFixedThreadPool(2)){var futures=new ArrayList<Future<String>>();for(var input:List.of(body(),other))futures.add(pool.submit(()->{barrier.await();try{f.parking.reserve(input,key,f.actor(1,"DRIVER"));return "OK";}catch(Problem p){return p.code;}}));var results=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));assertTrue(results.contains("OK"),results.toString());assertTrue(results.contains("IDEMPOTENCY_CONFLICT"),results.toString());}
  assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM reservation_order",Integer.class));
 }
 @Test void immutableEventReplayDoesNotReplayBusinessEffect(){long id=reserve();confirmed(id);when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:00:00Z"));assertEquals("IN_USE",gate(id,"ENTRY","event-entry-1").get("status"));when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T02:20:00Z"));assertEquals(300L,gate(id,"EXIT","event-exit-1").get("overtimeFen"));assertEquals("IN_USE",gate(id,"ENTRY","event-entry-1").get("status"));assertEquals("COMPLETED",state(id));assertEquals(2,f.db.queryForObject("SELECT COUNT(*) FROM parking_event",Integer.class));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->gate(id,"EXIT","event-entry-1")).code);assertEquals(403,assertThrows(Problem.class,()->f.parking.parkingEvent(Map.of("reservationId",id,"type","ENTRY","eventId","event-entry-1"),f.actor(6,"PROPERTY",11L))).status);}
 @Test void blockedEventCommitsOnceAndNewEventCanEnter(){long id=reserve();confirmed(id);when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:00:00Z"));f.db.update("UPDATE parking_space SET occupied_reservation_id=999 WHERE id=20");for(int i=0;i<2;i++)assertEquals("SPACE_OCCUPIED",assertThrows(Problem.class,()->gate(id,"ENTRY","blocked-event")).code);assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENTRY_BLOCKED'",Integer.class));f.db.update("UPDATE parking_space SET occupied_reservation_id=NULL WHERE id=20");assertEquals("SPACE_OCCUPIED",assertThrows(Problem.class,()->gate(id,"ENTRY","blocked-event")).code);assertEquals("IN_USE",gate(id,"ENTRY","retry-event").get("status"));}
 @Test void deadlineUsesEvidenceNotTransientOccupancy(){long id=reserve();confirmed(id);f.db.update("UPDATE parking_space SET occupied_reservation_id=999 WHERE id=20");when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:15:00Z"));assertEquals("ENTRY_NOT_ALLOWED",assertThrows(Problem.class,()->gate(id,"ENTRY","deadline-event")).code);f.parking.expire();assertEquals("NO_SHOW",state(id));assertEquals(999L,f.db.queryForObject("SELECT occupied_reservation_id FROM parking_space WHERE id=20",Long.class));}
 @Test void blockedEvidenceSurvivesPreviousCarExit(){long id=reserve();confirmed(id);when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:00:00Z"));f.db.update("UPDATE parking_space SET occupied_reservation_id=999 WHERE id=20");assertThrows(Problem.class,()->gate(id,"ENTRY","blocked-event"));f.db.update("UPDATE parking_space SET occupied_reservation_id=NULL WHERE id=20");when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:15:00Z"));f.parking.expire();assertEquals("SPACE_UNAVAILABLE",state(id));}
 @Test void explicitUnavailableIsScopedAuditedAndIdempotent(){long id=reserve();confirmed(id);when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:01:00Z"));var input=Map.<String,Object>of("reason","前车无法离场");assertEquals(403,assertThrows(Problem.class,()->f.parking.unavailable(id,input,"unavailable-key",f.actor(4,"PROPERTY",11L))).status);assertEquals("CANCEL_NOT_ALLOWED",assertThrows(Problem.class,()->f.parking.cancel(id,f.actor(1,"DRIVER"))).code);for(int i=0;i<2;i++)assertEquals("SPACE_UNAVAILABLE",f.parking.unavailable(id,input,"unavailable-key",f.actor(4,"PROPERTY",10L)).get("status"));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SPACE_UNAVAILABLE'",Integer.class));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationClosed'",Integer.class));}
 @Test void exactPaymentDeadlineCannotConfirmEvenWithoutExpiryScan(){long id=reserve();when(f.clock.now()).thenReturn(f.now.plusSeconds(600));new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.db.getDataSource())).executeWithoutResult(s->f.parking.accept("PaymentSucceeded",Map.of("reservationId",id,"kind","PREPAY","amountFen",600)));assertEquals("EXPIRED",state(id));}
 @Test void legacyGateStillReplaysItsOriginalResult(){long id=reserve();confirmed(id);when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:00:00Z"));for(int i=0;i<2;i++)assertEquals("IN_USE",f.parking.gate(id,"entry","legacy-event",f.actor(4,"PROPERTY",10L)).get("status"));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM parking_event",Integer.class));}
 @Test void rejectedEventCannotTurnIntoSuccessWhenTimeAdvances(){
  long id=reserve();confirmed(id);
  assertEquals("ENTRY_NOT_ALLOWED",assertThrows(Problem.class,()->gate(id,"ENTRY","early-entry-event")).code);
  when(f.clock.now()).thenReturn(Instant.parse("2030-01-01T01:00:00Z"));
  assertEquals("ENTRY_NOT_ALLOWED",assertThrows(Problem.class,()->gate(id,"ENTRY","early-entry-event")).code);
  assertEquals("CONFIRMED",state(id));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->gate(id,"EXIT","early-entry-event")).code);
  assertEquals("IN_USE",gate(id,"ENTRY","new-entry-event").get("status"));
 }
 @Test void failedFullBatchBacksOffAndDoesNotStarveFollowingOrders(){
  long good=reserve();
  for(int i=0;i<100;i++)f.db.update("INSERT INTO reservation_order(id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,status,created_at) SELECT ?,user_id,999,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,status,? FROM reservation_order WHERE id=?",500000+i,Data.ts(Instant.now().minusSeconds(120)),good);
  when(f.clock.now()).thenReturn(f.now.plusSeconds(600));f.parking.expire();
  assertEquals("WAIT_PAY",state(good));assertEquals(100,f.db.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE expiry_attempts=1 AND expiry_retry_at IS NOT NULL",Integer.class));
  f.parking.expire();assertEquals("EXPIRED",state(good));assertEquals(100,f.db.queryForObject("SELECT SUM(expiry_attempts) FROM reservation_order",Integer.class));
  f.db.update("INSERT INTO vehicle(id,user_id,plate) VALUES(999,1,'粤B12349')");f.db.update("UPDATE reservation_order SET expiry_retry_at=? WHERE vehicle_id=999",Data.ts(Instant.now().minusSeconds(1)));f.parking.expire();
  assertEquals(101,f.db.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE status='EXPIRED' AND expiry_retry_at IS NULL AND expiry_last_error IS NULL",Integer.class));
 }
}
