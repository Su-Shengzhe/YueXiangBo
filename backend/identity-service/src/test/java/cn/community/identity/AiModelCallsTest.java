package cn.community.identity;
import cn.community.foundation.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AiModelCallsTest {
 @TempDir Path directory;
 AiGatewayAdmin registry;LlmGateway transport;AiModelCalls calls;
 @BeforeEach void setup()throws Exception{
  registry=mock(AiGatewayAdmin.class);transport=mock(LlmGateway.class);calls=new AiModelCalls(registry,transport,directory.toString());
  Files.writeString(directory.resolve("agnes.properties"),"api-key=local-test-key\n");
  when(registry.route(null)).thenReturn(Map.of("credentialRef","agnes.properties","baseUrl","https://apihub.agnes-ai.com/v1","modelName","agnes-2.5-flash","routeVersion",3,"freeAccessApproved",true,"inputPriceMicroyuanPerMillion",400000,"outputPriceMicroyuanPerMillion",1200000,"priceVersion","test-price"));
 }
 @Test void pinsCredentialsAndMetersReportedTokens()throws Exception{
  when(transport.complete(any(),anyList(),anyList(),any())).thenReturn(Map.of("usage",Map.of("prompt_tokens",1000,"completion_tokens",500)));
  var binding=calls.capture();assertEquals(3,binding.version());
  calls.complete(binding,List.of(Map.of("role","user","content","test")),List.of(),Duration.ofSeconds(1));
  var id=ArgumentCaptor.forClass(String.class);verify(registry).admit(id.capture(),eq(3L),eq(10000L),any(),eq(17920L),isNull(),isNull());
  verify(registry).settleUsage(id.getValue(),1000L,500L,"test-price",1000L);
  var properties=ArgumentCaptor.forClass(Properties.class);verify(transport).captureRoute(properties.capture());
  assertEquals("1536",properties.getValue().getProperty("max-output-tokens"));
 }
 @Test void unknownResultRetainsReservation()throws Exception{
  when(transport.complete(any(),anyList(),anyList(),any())).thenThrow(new Problem(504,"AI_TIMEOUT","timeout"));
  assertThrows(Problem.class,()->calls.complete(calls.capture(),List.of(),List.of(),Duration.ofSeconds(1)));
  verify(registry).admit(anyString(),eq(3L),eq(10000L),any(),eq(17920L),isNull(),isNull());verify(registry).callFailure(anyString(),eq("AI_TIMEOUT"));verify(registry,never()).settleUsage(anyString(),anyLong(),anyLong(),anyString(),anyLong());
 }
 @Test void unknownUsagePreservesAnswerAndReservation()throws Exception{
  for(Object usage:List.of("missing",Map.of("prompt_tokens",1),Map.of("prompt_tokens",-1,"completion_tokens",0),Map.of("prompt_tokens",0.5,"completion_tokens",0))){
   var answer=new HashMap<String,Object>();answer.put("choices",List.of(Map.of("message",Map.of("content","ok"))));
   if(!usage.equals("missing"))answer.put("usage",usage);
   when(transport.complete(any(),anyList(),anyList(),any())).thenReturn(answer);
   assertSame(answer,calls.complete(calls.capture(),List.of(),List.of(),Duration.ofSeconds(1)));
  }
  verify(registry).callFailure(anyString(),eq("USAGE_MISSING"));
  verify(registry).callFailure(anyString(),eq("USAGE_INCOMPLETE"));
  verify(registry,times(2)).callFailure(anyString(),eq("USAGE_INVALID"));
  verify(registry,never()).settleUsage(anyString(),anyLong(),anyLong(),anyString(),anyLong());
 }
 @Test void reportedZeroIsSettled()throws Exception{
  when(transport.complete(any(),anyList(),anyList(),any())).thenReturn(Map.of("usage",Map.of("prompt_tokens",0,"completion_tokens",0)));
  calls.complete(calls.capture(),List.of(),List.of(),Duration.ofSeconds(1));
  verify(registry).settleUsage(anyString(),eq(0L),eq(0L),eq("test-price"),eq(0L));
 }
 @Test void contextLimitRejectsBeforeReservation()throws Exception{
  assertThrows(Problem.class,()->calls.complete(calls.capture(),List.of(Map.of("role","user","content","x".repeat(9000))),List.of(),Duration.ofSeconds(1)));
  verify(registry,never()).admit(anyString(),anyLong(),anyLong(),any(),anyLong(),any(),any());
 }
}
