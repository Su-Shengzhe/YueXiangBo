package cn.community.parkingservice;

import cn.community.foundation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceManagementTest {
 @Test void allPartialQuoteCombinationsFailWhileLegacyRequestsRemainCompatible(){
  createWindow();String[] fields={"windowId","windowVersion","expectedHourlyFen","expectedPrepayFen"};
  var body=new HashMap<String,Object>(Map.of("vehicleId",40,"spaceId",20,"startAt","2030-01-01T01:00:00Z","endAt","2030-01-01T02:00:00Z"));
  for(int mask=1;mask<15;mask++){var partial=new HashMap<>(body);for(int i=0;i<4;i++)if((mask&(1<<i))!=0)partial.put(fields[i],1);assertEquals("INCOMPLETE_QUOTE",assertThrows(Problem.class,()->parking.reserve(partial,UUID.randomUUID().toString(),actor(1,"DRIVER"))).code);}
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM reservation_order",Integer.class));
  assertEquals(600L,parking.reserve(body,UUID.randomUUID().toString(),actor(1,"DRIVER")).get("prepayFen"));
 }
 @Test void legacyNullWindowReferenceStillProtectsWindow(){var w=createWindow();reserve(40,w);db.update("UPDATE reservation_order SET window_id=NULL");assertEquals("ACTIVE_RESERVATION",assertThrows(Problem.class,()->resources.editWindow(Long.parseLong(w.get("id").toString()),Map.of("version",1,"enabled",false),actor(3,"OWNER"))).code);}
 JdbcTemplate db;ResourceManagement resources;BusinessParking parking;BusinessClock clock;
 final Instant now=Instant.parse("2030-01-01T00:00:00Z");
 MockHttpServletRequest actor(long id,String role,Long...communities){var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(id,Set.of(role),Set.of(communities)));return r;}
 @BeforeEach void setup() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");db=new JdbcTemplate(ds);
  // H2 index names are schema-scoped; MySQL names are table-scoped. Do not alter applied migrations.
  String baseline=new ClassPathResource("db/business/V001__business_baseline.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("plate VARCHAR(20) NOT NULL UNIQUE,INDEX ix_owner","plate VARCHAR(20) NOT NULL UNIQUE,INDEX ix_vehicle_owner");
  // H2 JSON encodes JDBC strings as JSON strings; use TEXT for the equivalent request-result fixture.
  for(String statement:baseline.replace("response_json JSON","response_json TEXT").split(";"))if(!statement.isBlank())db.execute(statement);
  db.execute("ALTER TABLE reservation_order ADD entry_blocked_at DATETIME(3)");db.execute("ALTER TABLE reservation_order ADD close_reason VARCHAR(40)");
  new ResourceDatabasePopulator(new ClassPathResource("db/business/V003__reliability.sql")).execute(ds);
  String[] alterations={"community ADD address VARCHAR(255) DEFAULT ''","community ADD enabled BOOLEAN DEFAULT TRUE","community ADD version INT DEFAULT 1","vehicle ADD enabled BOOLEAN DEFAULT TRUE","vehicle ADD version INT DEFAULT 1","parking_space ADD space_code VARCHAR(64)","parking_space ADD location_desc VARCHAR(255) DEFAULT ''","parking_space ADD space_type VARCHAR(20) DEFAULT 'STANDARD'","parking_space ADD proof_text VARCHAR(1000) DEFAULT ''","parking_space ADD review_status VARCHAR(20) DEFAULT 'APPROVED'","parking_space ADD review_comment VARCHAR(500) DEFAULT ''","parking_space ADD version INT DEFAULT 1","share_window ADD hourly_fen BIGINT DEFAULT 500","share_window ADD enabled BOOLEAN DEFAULT TRUE","share_window ADD version INT DEFAULT 1","reservation_order ADD window_id BIGINT","audit_log ADD detail_json TEXT"};
  for(String sql:alterations)db.execute("ALTER TABLE "+sql);
  new ResourceDatabasePopulator(new ClassPathResource("db/business/V005__reservation_recovery.sql")).execute(ds);
  // H2 does not accept MySQL's comma-separated ALTER ADD clauses. MySQL verifies the original migration separately.
  db.execute("ALTER TABLE reservation_order ADD expiry_retry_at DATETIME(3) NULL");
  db.execute("ALTER TABLE reservation_order ADD expiry_attempts INT NOT NULL DEFAULT 0");
  db.execute("ALTER TABLE reservation_order ADD expiry_last_error VARCHAR(100) NULL");
  db.execute("CREATE INDEX ix_expiry_payment ON reservation_order(status,pay_deadline,expiry_retry_at)");
  db.execute("CREATE INDEX ix_expiry_arrival ON reservation_order(status,start_at,expiry_retry_at)");
  db.execute("CREATE TABLE space_review(id BIGINT PRIMARY KEY,space_id BIGINT,actor_id BIGINT,decision VARCHAR(20),comment VARCHAR(500))");
  db.update("INSERT INTO community(id,name) VALUES(10,'测试社区'),(11,'另一社区')");db.update("INSERT INTO vehicle(id,user_id,plate) VALUES(40,1,'粤B12345'),(41,2,'粤B12346')");
  db.update("INSERT INTO parking_space(id,community_id,owner_id,name,space_code,hourly_fen,proof_text) VALUES(20,10,3,'A001','A001',500,'PRIVATE_PROOF')");
  clock=mock(BusinessClock.class);when(clock.now()).thenReturn(now);var manager=new DataSourceTransactionManager(ds);resources=new ResourceManagement(db,manager,clock);parking=new BusinessParking(db,manager,clock);
 }
 Map<String,Object> window(String start,String end,long price){return Map.of("startAt",start,"endAt",end,"hourlyFen",price);}
 Map<String,Object> createWindow(){return resources.createWindow(20,window("2030-01-01T01:00:00Z","2030-01-01T04:00:00Z",600),actor(3,"OWNER"));}
 Map<String,Object> reserve(long vehicle,Map<String,Object> w){return parking.reserve(Map.of("vehicleId",vehicle,"spaceId",20,"startAt","2030-01-01T01:00:00Z","endAt","2030-01-01T02:00:00Z","windowId",w.get("id"),"windowVersion",w.get("version"),"expectedHourlyFen",600,"expectedPrepayFen",600),UUID.randomUUID().toString(),actor(vehicle==40?1:2,"DRIVER"));}
 @Test void windowsUseHalfOpenIntervalsAndSerializeConcurrentCreation() throws Exception {
  createWindow();assertEquals("WINDOW_OVERLAP",assertThrows(Problem.class,()->resources.createWindow(20,window("2030-01-01T03:00:00Z","2030-01-01T05:00:00Z",500),actor(3,"OWNER"))).code);
  resources.createWindow(20,window("2030-01-01T04:00:00Z","2030-01-01T05:00:00Z",500),actor(3,"OWNER"));
  try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> work=()->{try{resources.createWindow(20,window("2030-01-01T06:00:00Z","2030-01-01T07:00:00Z",500),actor(3,"OWNER"));return true;}catch(Problem e){assertEquals("WINDOW_OVERLAP",e.code);return false;}};var a=pool.submit(work);var b=pool.submit(work);assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
 }
 @Test void publicSearchUsesWindowPriceAndNeverExposesProofOrOwner(){createWindow();var result=parking.search(10,"2030-01-01T01:00:00Z","2030-01-01T02:00:00Z","STANDARD",600L,1,20);@SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");assertEquals(1,items.size());assertEquals(600L,items.getFirst().get("estimatedFen"));assertFalse(items.getFirst().containsKey("proofText"));assertFalse(items.getFirst().containsKey("ownerId"));assertEquals(0L,parking.search(10,"2030-01-01T01:00:00Z","2030-01-01T02:00:00Z","",599L,1,20).get("total"));}
 @Test void activeReservationProtectsWindowAndVehicleWithoutChangingSnapshot(){var w=createWindow();var order=reserve(40,w);assertEquals(600L,order.get("prepayFen"));assertEquals("ACTIVE_RESERVATION",assertThrows(Problem.class,()->resources.editWindow(Long.parseLong(w.get("id").toString()),Map.of("enabled",false,"version",1),actor(3,"OWNER"))).code);assertEquals("ACTIVE_RESERVATION",assertThrows(Problem.class,()->resources.editVehicle(40,Map.of("enabled",false,"version",1),actor(1,"DRIVER"))).code);assertEquals(600L,db.queryForObject("SELECT hourly_fen FROM reservation_order",Long.class));}
 @Test void staleQuoteCannotSilentlyChargeChangedPrice(){var w=createWindow();resources.editWindow(Long.parseLong(w.get("id").toString()),new HashMap<>(Map.of("version",1,"startAt","2030-01-01T01:00:00Z","endAt","2030-01-01T04:00:00Z","hourlyFen",800)),actor(3,"OWNER"));assertEquals("QUOTE_CHANGED",assertThrows(Problem.class,()->reserve(40,w)).code);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM reservation_order",Integer.class));}
 @Test void ownerAndCommunityAuthorizationCannotBeForged(){createWindow();assertEquals(404,assertThrows(Problem.class,()->resources.windows(20,1,20,actor(4,"OWNER"))).status);assertEquals(403,assertThrows(Problem.class,()->resources.reviews(10,1,20,actor(4,"PROPERTY",11L))).status);assertEquals(403,assertThrows(Problem.class,()->resources.createCommunity(Map.of("name","bad","address","bad"),actor(1,"DRIVER"))).status);}
 @Test void lastVersionPreventsDoubleReviewAndRejectionCanBeResubmitted(){db.update("UPDATE parking_space SET review_status='PENDING' WHERE id=20");resources.review(20,Map.of("version",1,"decision","REJECT","comment","说明不足"),actor(4,"PROPERTY",10L));assertEquals("STALE_VERSION",assertThrows(Problem.class,()->resources.review(20,Map.of("version",1,"decision","APPROVE","comment","通过"),actor(4,"PROPERTY",10L))).code);resources.submit(20,Map.of("version",2),actor(3,"OWNER"));assertEquals("PENDING",db.queryForObject("SELECT review_status FROM parking_space WHERE id=20",String.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM space_review",Integer.class));}
 @Test void communityDisableStopsNewReservationsButPreservesExistingOrder(){var w=createWindow();var order=reserve(40,w);resources.editCommunity(10,Map.of("version",1,"enabled",false),actor(5,"PLATFORM"));assertEquals("COMMUNITY_DISABLED",assertThrows(Problem.class,()->reserve(41,w)).code);assertEquals(order.get("reservationId"),parking.detail(Long.parseLong(order.get("reservationId").toString()),actor(1,"DRIVER")).get("reservationId"));}
 @Test void reservationAndWindowChangeHaveOnlyConsistentOutcomes() throws Exception {var w=createWindow();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{try{reserve(40,w);return "reserved";}catch(Problem e){return e.code;}});var b=pool.submit(()->{try{resources.editWindow(Long.parseLong(w.get("id").toString()),Map.of("enabled",false,"version",1),actor(3,"OWNER"));return "disabled";}catch(Problem e){return e.code;}});String first=a.get(10,TimeUnit.SECONDS),second=b.get(10,TimeUnit.SECONDS);assertTrue(first.equals("reserved")&&second.equals("ACTIVE_RESERVATION")||first.equals("OUTSIDE_SHARE_WINDOW")&&second.equals("disabled"),first+" / "+second);}}
 @Test void normalizedPlateUniquenessAndDisabledBindingArePreserved(){assertThrows(org.springframework.dao.DuplicateKeyException.class,()->resources.createVehicle(Map.of("plate","粤b12345"),actor(2,"DRIVER")));resources.editVehicle(40,Map.of("enabled",false,"version",1),actor(1,"DRIVER"));assertThrows(org.springframework.dao.DuplicateKeyException.class,()->resources.createVehicle(Map.of("plate","粤B12345"),actor(2,"DRIVER")));assertTrue(parking.vehicles(actor(1,"DRIVER")).isEmpty());}
}
