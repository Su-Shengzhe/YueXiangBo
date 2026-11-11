package cn.community.identity;
import cn.community.foundation.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConversationTest {
 JdbcTemplate db;AiConversation api;AiModelCalls model;AiTools tools;AiKnowledge knowledge;
 @BeforeEach void setup()throws Exception{
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");db=new JdbcTemplate(ds);
  new ResourceDatabasePopulator(new ClassPathResource("db/business/V007__ai_gateway.sql"),new ClassPathResource("db/business/V008__ai_sessions_knowledge.sql")).execute(ds);
  db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)");db.execute("INSERT INTO app_user VALUES(1),(2)");db.execute("CREATE TABLE authorization_guard(id INT PRIMARY KEY)");db.execute("INSERT INTO authorization_guard VALUES(1)");db.execute("CREATE TABLE audit_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action VARCHAR(60),resource_id VARCHAR(60),detail_json TEXT)");
  var manager=new DataSourceTransactionManager(ds);model=mock(AiModelCalls.class);tools=mock(AiTools.class);var clock=new BusinessClock("",false);knowledge=new AiKnowledge(db,manager,clock);api=new AiConversation(db,manager,model,tools,clock);
  when(model.capture()).thenReturn(new AiModelCalls.Binding(1,null,400000,1200000,"test",1536));when(tools.communities(anyLong())).thenReturn(List.of(Map.of("id","10","name","阳光社区")));
 }
 @AfterEach void stop(){api.stop();}
 MockHttpServletRequest user(long id){var req=new MockHttpServletRequest();req.setAttribute("actor",new Actor(id,Set.of("DRIVER"),Set.of()));req.addHeader("Authorization","Bearer test");return req;}
 MockHttpServletRequest admin(){var req=user(1);req.setAttribute("actor",new Actor(1,Set.of("PLATFORM"),Set.of(),Set.of("KNOWLEDGE_MANAGE")));return req;}
 String create(){return api.create(user(1)).get("sessionId").toString();}
 Map<String,Object> input(long version){return Map.of("text","我想了解提前离开能退回剩下的钱么","expectedSessionVersion",version);}
 Map<String,Object> modelRule(){return Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("name","retrieveParkingRules","arguments","{\"query\":\"取消\"}")))))));}
 @SuppressWarnings("unchecked") Map<String,Object> await(DeferredResult<ResponseEntity<Map<String,Object>>> r)throws Exception{for(int i=0;i<200&&!r.hasResult();i++)Thread.sleep(10);assertTrue(r.hasResult());return ((ResponseEntity<Map<String,Object>>)r.getResult()).getBody();}
 @Test void sameKeyReplaysWithoutCallingModelAgain()throws Exception{
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenReturn(modelRule());when(tools.execute(eq("retrieveParkingRules"),anyMap(),anyString(),anyLong())).thenReturn(Map.of("citations",knowledge.retrieve("取消",0)));
  String id=create();var first=await(api.message(id,"request_01",input(0),user(1)));assertEquals("ANSWERED",first.get("status"));assertEquals(Data.map(Data.json(first)),await(api.message(id,"request_01",input(0),user(1))));verify(model,times(1)).complete(any(),anyList(),anyList(),any(),eq(first.get("requestId").toString()),eq("INTENT"));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->api.message(id,"request_01",Map.of("text","其他","expectedSessionVersion",0),user(1))).code);
  assertEquals("SESSION_VERSION_CONFLICT",assertThrows(Problem.class,()->api.message(id,"request_02",input(0),user(1))).code);
 }
 @Test void explicitBillContextUsesOneModelCallAndRechecksFacts()throws Exception{
  var facts=new LinkedHashMap<String,Object>();facts.put("reservationId","123");facts.put("billVersion",1);facts.put("parkingVersion",1);for(String field:List.of("prepayFen","overtimeFen","paidFen","refundedFen","netPaidFen","pendingRefundFen"))facts.put(field,0);facts.put("ruleSnapshot",Map.of("ruleId","BILLING","version",1));
  when(tools.execute(eq("getMyOrderBill"),anyMap(),anyString(),anyLong())).thenReturn(facts);
  String content=Data.json(Map.of("reservationId","123","billVersion",1,"explanations",AiBillReferences.allowed(facts)));
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenReturn(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
  var answer=await(api.message(create(),"bill_context_01",Map.of("text","解释本人账单","expectedSessionVersion",0,"context",Map.of("reservationId","123")),user(1)));
  assertEquals("ANSWERED",answer.get("status"));verify(model,times(1)).complete(any(),anyList(),argThat(list->list.size()==1),any(),eq(answer.get("requestId").toString()),eq("BILL_EXPLANATION"));verify(tools,times(2)).execute(eq("getMyOrderBill"),anyMap(),anyString(),anyLong());
 }
 @Test void billChangesWhileModelIsInFlightDiscardOldExplanation()throws Exception{
  var original=new LinkedHashMap<String,Object>();original.put("reservationId","123");original.put("billVersion",1);original.put("parkingVersion",1);for(String field:List.of("prepayFen","overtimeFen","paidFen","refundedFen","netPaidFen","pendingRefundFen"))original.put(field,0);original.put("ruleSnapshot",Map.of("ruleId","BILLING","version",1));
  var current=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>(original);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(tools.execute(eq("getMyOrderBill"),anyMap(),anyString(),anyLong())).thenAnswer(x->current.get());
  String content=Data.json(Map.of("reservationId","123","billVersion",1,"explanations",AiBillReferences.allowed(original)));
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenAnswer(x->{started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Map.of("choices",List.of(Map.of("message",Map.of("content",content))));});
  String session=create();var pending=api.message(session,"bill_race_01",Map.of("text","解释本人账单","expectedSessionVersion",0,"context",Map.of("reservationId","123")),user(1));
  assertTrue(started.await(2,TimeUnit.SECONDS));var updated=new LinkedHashMap<>(original);updated.put("billVersion",2);updated.put("paidFen",500);updated.put("netPaidFen",500);current.set(updated);release.countDown();
  var result=await(pending);assertEquals("FALLBACK",result.get("status"));assertEquals("BILL_CHANGED",result.get("fallbackReason"));assertEquals(updated,result.get("bill"));assertEquals(List.of(),result.get("explanations"));assertEquals(List.of(),result.get("reasons"));
  var replay=await(api.message(session,"bill_race_01",Map.of("text","解释本人账单","expectedSessionVersion",0,"context",Map.of("reservationId","123")),user(1)));assertEquals(Data.map(Data.json(result)),replay);verify(model,times(1)).complete(any(),anyList(),anyList(),any(),eq(result.get("requestId").toString()),eq("BILL_EXPLANATION"));
 }
 @Test void rejectsOtherUsersSession(){String id=create();assertEquals(404,assertThrows(Problem.class,()->api.history(id,1,20,user(2))).status);}
 @Test void serializesBusySessionAndRecoversLeaseWithoutLateOverwrite()throws Exception{
  var started=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenAnswer(x->{started.countDown();release.await(5,TimeUnit.SECONDS);return modelRule();});when(tools.execute(anyString(),anyMap(),anyString(),anyLong())).thenReturn(Map.of("citations",List.of()));
  String id=create();api.message(id,"request_01",input(0),user(1));assertTrue(started.await(2,TimeUnit.SECONDS));
  assertEquals("SESSION_BUSY",assertThrows(Problem.class,()->api.message(id,"request_02",input(0),user(1))).code);assertEquals("PROCESSING",await(api.message(id,"request_01",input(0),user(1))).get("status"));
  db.update("UPDATE ai_session SET lease_until=? WHERE id=?",Data.ts(Instant.now().minusSeconds(1)),id);api.recover();release.countDown();Thread.sleep(100);
  assertEquals("FAILED",await(api.message(id,"request_01",input(0),user(1))).get("status"));assertEquals(1L,db.queryForObject("SELECT version FROM ai_session WHERE id=?",Long.class,id));
 }
 @Test void unknownToolsAreRefused()throws Exception{
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenReturn(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("name","executePayment","arguments","{}"))))))));
  assertEquals("REFUSED",await(api.message(create(),"request_01",input(0),user(1))).get("status"));verify(tools,never()).execute(anyString(),anyMap(),anyString(),anyLong());
 }
 @Test void preventsSixthTurnAndAllowsExplicitNewTask()throws Exception{
  when(model.complete(any(),anyList(),anyList(),any(),anyString(),anyString())).thenReturn(Map.of("choices",List.of(Map.of("message",Map.of("content","{\"action\":\"CLARIFY\",\"missing\":[\"communityId\"]}")))));
  String id=create();db.update("UPDATE ai_session SET turns=5 WHERE id=?",id);assertEquals("TURN_LIMIT",assertThrows(Problem.class,()->api.message(id,"request_01",input(0),user(1))).code);
  assertEquals("CLARIFY",await(api.message(id,"request_02",Map.of("text","新的问题","expectedSessionVersion",0,"newTask",true),user(1))).get("status"));
 }
 @Test void publishedKnowledgeIsImmutableAndVersionsCannotOverlap(){
  assertEquals("IMMUTABLE_RULE",assertThrows(Problem.class,()->knowledge.edit(1,Map.of("version",0),admin())).code);
  var draft=knowledge.create(Map.of("ruleId","BILLING","communityId",0,"ruleVersion",2,"title","新规则","topic","BILLING","keywords","费用","content","新内容","effectiveAt","2026-10-17T00:00:00Z"),admin());
  long id=Long.parseLong(draft.get("id").toString());assertEquals("RULE_OVERLAP",assertThrows(Problem.class,()->knowledge.publish(id,"publish",Map.of("version",0),admin())).code);
  assertEquals(403,assertThrows(Problem.class,()->knowledge.list(1,20,user(2))).status);
 }
 @Test void localPolicyAndExplicitSearchWorkWithUnavailableCredentials()throws Exception{
  when(model.capture()).thenThrow(new Problem(503,"AI_CREDENTIAL_UNAVAILABLE","missing"));
  when(tools.execute(eq("retrieveParkingRules"),anyMap(),anyString(),anyLong())).thenReturn(Map.of("citations",knowledge.retrieve("预约费",0)));
  var rule=await(api.message(create(),"local_rule_01",Map.of("expectedSessionVersion",0,"text","预约费怎么算"),user(1)));
  assertEquals("ANSWERED",rule.get("status"));verify(tools,never()).communities(anyLong());
  when(tools.execute(eq("searchAvailableSpaces"),anyMap(),anyString(),anyLong())).thenReturn(Map.of("cards",List.of()));
  var result=await(api.message(create(),"local_search_01",Map.of("expectedSessionVersion",0,"text","查询阳光社区（编号10）从2026-11-20T13:00:00Z到2026-11-20T14:00:00Z的可用车位，每小时最多0 分，不限类型。"),user(1)));
  assertEquals("ANSWERED",result.get("status"));assertEquals(0L,((Number)((Map<?,?>)result.get("slots")).get("maxHourlyPriceFen")).longValue());
  verify(model,never()).capture();
 }
 @Test void ambiguousCommunitiesClarifyBeforeCallingSupplier()throws Exception{
  when(tools.communities(anyLong())).thenReturn(List.of(Map.of("id","10","name","阳光社区"),Map.of("id","11","name","阳光社区")));
  var answer=await(api.message(create(),"ambiguous_01",Map.of("expectedSessionVersion",0,"text","查询阳光社区明晚的车位"),user(1)));
  assertEquals("CLARIFY",answer.get("status"));verify(model,never()).capture();
 }
}
