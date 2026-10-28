package cn.community.parkingservice;
import cn.community.foundation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DemoWindowsTest {
 JdbcTemplate db; BusinessClock clock; DemoWindows api;
 MockHttpServletRequest request(String role){var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(5,Set.of(role),Set.of()));return r;}
 @BeforeEach void setup(){
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
  db=new JdbcTemplate(ds);clock=mock(BusinessClock.class);
  when(clock.isDemo()).thenReturn(true);when(clock.now()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
  db.execute("CREATE TABLE community(id BIGINT PRIMARY KEY,enabled BOOLEAN)");db.execute("INSERT INTO community VALUES(10,TRUE)");
  db.execute("CREATE TABLE parking_space(id BIGINT PRIMARY KEY,enabled BOOLEAN,hourly_fen BIGINT DEFAULT 500,community_id BIGINT DEFAULT 10,review_status VARCHAR(20) DEFAULT 'APPROVED')");db.execute("INSERT INTO parking_space(id,enabled) VALUES(20,TRUE)");
  db.execute("CREATE TABLE share_window(id BIGINT PRIMARY KEY,space_id BIGINT,start_at TIMESTAMP,end_at TIMESTAMP,hourly_fen BIGINT DEFAULT 500)");
  db.execute("CREATE TABLE business_sequence(name VARCHAR(30) PRIMARY KEY,last_id BIGINT)");db.execute("INSERT INTO business_sequence VALUES('business',1000)");
  db.execute("CREATE TABLE audit_log(actor_id BIGINT,action VARCHAR(50),resource_id VARCHAR(80))");
  api=new DemoWindows(db,clock,new DataSourceTransactionManager(ds));
 }
 @Test void expiredWindowRemainsAndFutureWindowIsAddedOnce(){
  db.update("INSERT INTO share_window(id,space_id,start_at,end_at) VALUES(30,20,?,?)",Data.ts(Instant.parse("2029-01-01T00:00:00Z")),Data.ts(Instant.parse("2029-02-01T00:00:00Z")));
  var first=api.prepare(20,request("PLATFORM"));assertEquals(true,first.get("created"));assertEquals("2030-01-01T00:15:00Z",first.get("startAt"));
  assertEquals(false,api.prepare(20,request("PLATFORM")).get("created"));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM share_window",Integer.class));
  assertEquals(Instant.parse("2029-02-01T00:00:00Z"),Data.instant(db.queryForMap("SELECT end_at FROM share_window WHERE id=30").get("end_at")));
 }
 @Test void activeWindowIsNeverShortenedOrOverlapped(){
  Instant end=Instant.parse("2030-01-02T00:00:00.001Z");
  db.update("INSERT INTO share_window(id,space_id,start_at,end_at) VALUES(30,20,?,?)",Data.ts(Instant.parse("2029-12-31T00:00:00Z")),Data.ts(end));
  var result=api.prepare(20,request("PLATFORM"));assertEquals("2030-01-02T00:15:00Z",result.get("startAt"));
  assertEquals(end,Data.instant(db.queryForMap("SELECT end_at FROM share_window WHERE id=30").get("end_at")));
 }
 @Test void concurrentPreparationCreatesOneWindow() throws Exception {
  try(var pool=Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->api.prepare(20,request("PLATFORM")));var b=pool.submit(()->api.prepare(20,request("PLATFORM")));
   a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
  }
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM share_window",Integer.class));
 }
 @Test void driverAndDisabledDemoCannotMutateWindows(){
  assertThrows(Problem.class,()->api.prepare(20,request("DRIVER")));
  when(clock.isDemo()).thenReturn(false);assertThrows(Problem.class,()->api.prepare(20,request("PLATFORM")));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM share_window",Integer.class));
 }
 @Test void preparationCannotBypassOwnerReviewOrDisabledCommunity(){
  assertEquals("DEMO_RESOURCE_ONLY",assertThrows(Problem.class,()->api.prepare(21,request("PLATFORM"))).code);
  db.update("UPDATE parking_space SET review_status='DRAFT' WHERE id=20");assertEquals("SPACE_NOT_APPROVED",assertThrows(Problem.class,()->api.prepare(20,request("PLATFORM"))).code);
  db.update("UPDATE parking_space SET review_status='APPROVED' WHERE id=20");db.update("UPDATE community SET enabled=FALSE WHERE id=10");assertEquals("COMMUNITY_DISABLED",assertThrows(Problem.class,()->api.prepare(20,request("PLATFORM"))).code);
 }
}
