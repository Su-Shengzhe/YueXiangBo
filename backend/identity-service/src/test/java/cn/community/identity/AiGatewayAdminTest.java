package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class AiGatewayAdminTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path secrets;
 JdbcTemplate db;AiGatewayAdmin api;
 @BeforeEach void setup()throws Exception{var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");db=new JdbcTemplate(ds);
  new ResourceDatabasePopulator(new ClassPathResource("db/business/V007__ai_gateway.sql"),new ClassPathResource("db/business/V008__ai_sessions_knowledge.sql")).execute(ds);
  db.execute("CREATE TABLE account_permission(user_id BIGINT,permission_code VARCHAR(80),granted BOOLEAN)");
  new ResourceDatabasePopulator(new ClassPathResource("db/business/V009__ai_permissions_pricing.sql"),new ClassPathResource("db/business/V010__ai_shared_admission.sql"),new ClassPathResource("db/business/V011__ai_provider_validation.sql"),new ClassPathResource("db/business/V012__ai_paid_budget.sql"),new ClassPathResource("db/business/V013__ai_call_message.sql"),new ClassPathResource("db/business/V014__paid_supplier_hosts.sql"),new ClassPathResource("db/business/V015__model_output_limit.sql")).execute(ds);
  db.update("UPDATE ai_budget SET spent_microyuan=0");
  db.execute("CREATE TABLE audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action VARCHAR(60),resource_id VARCHAR(60),detail_json JSON)");
  api=new AiGatewayAdmin(db,new DataSourceTransactionManager(ds));api.credentialsDirectory=secrets.toString();java.nio.file.Files.writeString(secrets.resolve("agnes.properties"),"api-key=test-only-key");}
 @Test void sharedAdmissionCoordinatesInstancesAndPreservesUnknownBudget(){
  var second=new AiGatewayAdmin(db,new DataSourceTransactionManager(db.getDataSource()));String account="a".repeat(64);
  api.admit("shared-a",1,100,account,16896);
  assertEquals("AI_PROVIDER_CONCURRENCY",assertThrows(Problem.class,()->second.admit("shared-b",1,100,account,16896)).code);
  assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
  api.releaseAdmission("shared-a");second.admit("shared-b",1,100,account,16896);second.releaseAdmission("shared-b");
  api.sharedCooldown(account,java.time.Instant.now().plusSeconds(60));
  assertEquals("AI_PROVIDER_COOLDOWN",assertThrows(Problem.class,()->second.admit("shared-c",1,100,account,16896)).code);
  assertEquals(200L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
 }
 @Test void rateLimitRejectsBeforeBudgetReservation(){String account="b".repeat(64);api.admit("rpm-a",1,100,account,16896);api.releaseAdmission("rpm-a");db.update("UPDATE ai_provider_admission SET rpm_limit=1");assertEquals("AI_PROVIDER_RATE_LIMIT",assertThrows(Problem.class,()->api.admit("rpm-b",1,100,account,16896)).code);assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));}
 MockHttpServletRequest actor(boolean authorized){var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(1,Set.of("PLATFORM"),Set.of(),authorized?Set.of("MODEL_MANAGE"):Set.of()));return r;}
 long provider(String model){long id=Long.parseLong(api.create(Map.of("providerName","Test","protocol","OPENAI_CHAT","baseUrl","https://example.com/v1","modelName",model,"credentialRef","agnes.properties","priceVersion","test-v1","freeAccessApproved",true),actor(true)).get("id").toString());api.checked(id,api.credentialFingerprint(api.provider(id)),new Actor(1,Set.of("PLATFORM"),Set.of()));return id;}
 @Test void paidBudgetSharesLimitAcrossRevisionsAndUnknownCalls(){long a=provider("a"),b=provider("b");db.update("UPDATE ai_provider_revision SET paid_scope='DEEPSEEK_ACCEPTANCE',free_access_approved=FALSE");api.reserve("paid-one",-a,3000000);assertEquals("AI_PAID_BUDGET_EXHAUSTED",assertThrows(Problem.class,()->api.reserve("paid-two",-b,3000000)).code);assertEquals(3000000L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));api.settle("paid-one",1000000);api.reserve("paid-two",-b,3000000);assertEquals(1000000L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));}
 @Test void refusesUnverifiedPublication(){long id=provider("a");db.update("UPDATE ai_provider_revision SET checked_at=NULL WHERE id=?",id);assertEquals("PROVIDER_NOT_VERIFIED",assertThrows(Problem.class,()->api.publish(Map.of("version",0,"providerRevisionId",id),actor(true))).code);assertEquals(0L,db.queryForObject("SELECT version FROM ai_route_state",Long.class));}
 @Test void publishRollbackAndHistoricalLookup(){long a=provider("a"),b=provider("b");api.publish(Map.of("version",0,"providerRevisionId",a),actor(true));api.publish(Map.of("version",1,"providerRevisionId",b),actor(true));assertEquals("a",api.route(1L).get("modelName"));assertEquals("b",api.route(null).get("modelName"));api.rollback(Map.of("version",2),actor(true));assertEquals("a",api.route(null).get("modelName"));assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM ai_route_history",Integer.class));}
 @Test void rejectsUnauthorizedAndStalePublication(){long a=provider("a");assertEquals(403,assertThrows(Problem.class,()->api.state(actor(false))).status);api.publish(Map.of("version",0,"providerRevisionId",a),actor(true));assertEquals("STALE_VERSION",assertThrows(Problem.class,()->api.publish(Map.of("version",0,"providerRevisionId",a),actor(true))).code);}
 @Test void concurrentBudgetReservationsDoNotOverspend()throws Exception{db.update("UPDATE ai_budget SET limit_microyuan=100 WHERE id=1");try(var pool=Executors.newFixedThreadPool(2)){Callable<String> call=()->{try{api.reserve(UUID.randomUUID().toString(),1,60);return "OK";}catch(Problem p){return p.code;}};var a=pool.submit(call);var b=pool.submit(call);assertEquals(Set.of("OK","AI_BUDGET_EXHAUSTED"),Set.of(a.get(),b.get()));}assertEquals(60L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));}
 @Test void usageSettlementIsIdempotentAndUnknownRetainsReservation(){api.reserve("x",1,100);api.reserve("x",1,100);assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));api.settle("x",40);api.settle("x",40);assertEquals(40L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));assertEquals(0L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));assertEquals("USAGE_CONFLICT",assertThrows(Problem.class,()->api.settle("x",41)).code);}
 @Test void paymentRequiredPausesAdmissionAndPreservesUnknownCharge(){api.reserve("paid",1,100);api.callFailure("paid","AI_PAYMENT_REQUIRED");assertEquals("AI_BUDGET_PAUSED",assertThrows(Problem.class,()->api.reserve("next",1,1)).code);assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));assertEquals(Boolean.FALSE,db.queryForObject("SELECT free_access_confirmed FROM ai_budget",Boolean.class));}
 @Test void actualUsageOverEstimateIsRecordedAndStopsFurtherCalls(){db.update("UPDATE ai_budget SET limit_microyuan=100");api.reserve("over",1,80);api.settle("over",120);assertEquals(120L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));assertEquals(0L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));assertEquals("AI_BUDGET_EXHAUSTED",assertThrows(Problem.class,()->api.reserve("next",1,1)).code);}
 @Test void failedLedgerWriteRollsBackUsageAndBudget(){
  api.reserve("atomic",1,100);
  String originalPrice=db.queryForObject("SELECT price_version FROM ai_call_reservation WHERE request_id='atomic'",String.class);
  db.execute("ALTER TABLE ai_call_reservation ADD CONSTRAINT test_failure CHECK (charged_microyuan IS NULL OR charged_microyuan<>40)");
  assertThrows(org.springframework.dao.DataAccessException.class,()->api.settleUsage("atomic",10,20,"v1",40));
  var row=db.queryForMap("SELECT * FROM ai_call_reservation WHERE request_id='atomic'");
  assertNull(row.get("input_tokens"));assertEquals(originalPrice,row.get("price_version"));assertEquals("RESERVED",row.get("status"));
  assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
  assertEquals(0L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));
  db.execute("ALTER TABLE ai_call_reservation DROP CONSTRAINT test_failure");
  api.settleUsage("atomic",10,20,"v1",40);api.settleUsage("atomic",10,20,"v1",40);
  assertEquals(40L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));
 }
 @Test void manualEvidenceReplayOnlyReleasesOneReservation(){
  api.reserve("unknown",1,100);api.callFailure("unknown","USAGE_MISSING");
  assertEquals(100L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
  var evidence=Map.<String,Object>of("evidence","test statement","equivalentMicroyuan",30,"actualChargeMicroyuan",0);
  api.reconcile("unknown",evidence,actor(true));api.reconcile("unknown",evidence,actor(true));
  assertEquals(30L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));
  assertEquals(0L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"normal","zero","missing","incomplete","invalid","timeout"})
 void loopbackSupplierAndLedger(String scenario)throws Exception{
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  var response=new HashMap<String,Object>();
  response.put("choices",List.of(Map.of("message",Map.of("content","ok"))));
  switch(scenario){
   case "normal" -> response.put("usage",Map.of("prompt_tokens",1000,"completion_tokens",500));
   case "zero" -> response.put("usage",Map.of("prompt_tokens",0,"completion_tokens",0));
   case "incomplete" -> response.put("usage",Map.of("prompt_tokens",1));
   case "invalid" -> response.put("usage",Map.of("prompt_tokens",-1,"completion_tokens",0));
  }
  server.createContext("/chat/completions",exchange->{
   try(exchange){
    if(scenario.equals("timeout"))try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    byte[] bytes=Data.json(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
   }
  });
  server.start();
  try(var transport=new LlmGateway(secrets.resolve("test.properties").toString())){
   var calls=new AiModelCalls(api,transport,secrets.toString());
   var binding=calls.capture(Map.of("credentialRef","agnes.properties","baseUrl","http://127.0.0.1:"+server.getAddress().getPort(),"modelName","stub","routeVersion",1,"freeAccessApproved",true,"inputPriceMicroyuanPerMillion",400000,"outputPriceMicroyuanPerMillion",1200000,"priceVersion","stub-v1"));
   if(scenario.equals("timeout")){
    assertEquals("AI_TIMEOUT",assertThrows(Problem.class,()->calls.complete(binding,List.of(),List.of(),java.time.Duration.ofMillis(100))).code);
   }else assertEquals(response.get("choices"),calls.complete(binding,List.of(),List.of(),java.time.Duration.ofSeconds(3)).get("choices"));
   var row=db.queryForMap("SELECT * FROM ai_call_reservation WHERE route_version=1");
   boolean settled=Set.of("normal","zero").contains(scenario);
   assertEquals(settled?"SETTLED":"RESERVED",row.get("status"));
   assertEquals(settled?0L:10000L,db.queryForObject("SELECT reserved_microyuan FROM ai_budget",Long.class));
   assertEquals(scenario.equals("normal")?1000L:0L,db.queryForObject("SELECT spent_microyuan FROM ai_budget",Long.class));
   if(settled){assertEquals("stub-v1",row.get("price_version"));assertEquals(scenario.equals("normal")?1000L:0L,row.get("input_tokens"));}
   else{assertNull(row.get("input_tokens"));assertEquals(Map.of("timeout","AI_TIMEOUT","missing","USAGE_MISSING","incomplete","USAGE_INCOMPLETE","invalid","USAGE_INVALID").get(scenario),row.get("detail"));}
  }finally{server.stop(0);}
 }
 @Test void thirdPaidHostSharesConfiguredCapAcrossModelsAndRollbacks(){
  long original=provider("original");
  api.publish(Map.of("version",0,"providerRevisionId",original),actor(true));
  var config=new HashMap<String,Object>(Map.of("providerName","third","protocol","OPENAI_CHAT","baseUrl","https://third.example/v1","modelName","first","credentialRef","agnes.properties","priceVersion","v1","billingMode","PAID","paidLimitMicroyuan",100000));
  assertThrows(Problem.class,()->api.create(config,actor(true)));
  config.put("paidApproved",true);
  long id=Long.parseLong(api.create(config,actor(true)).get("id").toString());
  api.checked(id,api.credentialFingerprint(api.provider(id)),new Actor(1,Set.of("PLATFORM"),Set.of()));
  api.publish(Map.of("version",1,"providerRevisionId",id),actor(true));
  api.reserve("first-call",2,95000);
  config.put("modelName","second");
  long second=Long.parseLong(api.create(config,actor(true)).get("id").toString());
  api.checked(second,api.credentialFingerprint(api.provider(second)),new Actor(1,Set.of("PLATFORM"),Set.of()));
  assertEquals("AI_PAID_BUDGET_EXHAUSTED",assertThrows(Problem.class,()->api.publish(Map.of("version",2,"providerRevisionId",second),actor(true))).code);
  api.rollback(Map.of("version",2),actor(true));
  assertEquals("original",api.route(null).get("modelName"));assertEquals("first",api.route(2L).get("modelName"));
 }
}
