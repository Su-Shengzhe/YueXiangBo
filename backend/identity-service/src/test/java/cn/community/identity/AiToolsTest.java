package cn.community.identity;
import cn.community.foundation.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiToolsTest {
 HttpServer server;AiTools tools;AiKnowledge knowledge;String authorization;String query;int status=200;Map<String,Object> bill,parking;
 @BeforeEach void setup()throws Exception{
  knowledge=mock(AiKnowledge.class);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  bill=new LinkedHashMap<>(Map.of("reservationId","123","billVersion",4,"reservationVersion",2,"prepayFen",500,"overtimeFen",100,"paidPrepayFen",500,"paidOvertimeFen",100,"refundFen",200));
  bill.put("attempts",List.of(Map.of("kind","REFUND","status","UNKNOWN","amountFen",50),Map.of("kind","REFUND","status","SUCCEEDED","amountFen",200)));
  parking=new LinkedHashMap<>(Map.of("version",2,"ruleSnapshot",Data.json(Map.of("ruleId","BILLING","version",1))));
  server.createContext("/api/v1/",x->{authorization=x.getRequestHeaders().getFirst("Authorization");query=x.getRequestURI().getRawQuery();String path=x.getRequestURI().getPath();Object data=path.contains("/bills/")?bill:path.contains("/reservations/")?parking:Map.of("items",List.of(Map.of("id","20","name","车位","hourlyFen",500,"ownerPhone","private","ownerId","3")),"total",1);byte[] body=Data.json(Map.of("data",data)).getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,body.length);x.getResponseBody().write(body);x.close();});server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();tools=new AiTools(knowledge,base,base);
 }
 @AfterEach void close(){tools.close();server.stop(0);}
 long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
 @Test void searchUsesOnlyAllowedQueryAndPublicCardFields()throws Exception{
  var result=tools.execute("searchAvailableSpaces",Map.of("communityId","10","startAt","2026-11-20T00:00:00Z","endAt","2026-11-20T01:00:00Z","maxHourlyPriceFen",500),"Bearer user",deadline());
  var card=(Map<?,?>)((List<?>)result.get("cards")).getFirst();assertEquals("20",card.get("id"));assertFalse(card.containsKey("ownerPhone"));assertFalse(card.containsKey("ownerId"));assertTrue(query.contains("maxHourlyFen=500"));assertEquals("Bearer user",authorization);
 }
 @Test void unknownToolAndInjectedArgumentsNeverReachUpstream(){assertEquals("TOOL_FORBIDDEN",assertThrows(Problem.class,()->tools.execute("refund",Map.of(),"Bearer user",deadline())).code);assertEquals("INVALID_TOOL_ARGUMENT",assertThrows(Problem.class,()->tools.execute("getMyOrderBill",Map.of("reservationId","123","userId","2"),"Bearer user",deadline())).code);assertNull(authorization);}
 @Test void rulesUseVersionedKnowledgeWithoutExternalRequest()throws Exception{var citations=List.<Map<String,Object>>of(Map.of("ruleId","BILLING","version",1));when(knowledge.retrieve("计费",10)).thenReturn(citations);assertEquals(citations,tools.execute("retrieveParkingRules",Map.of("query","计费","communityId","10"),null,deadline()).get("citations"));assertNull(authorization);}
 @Test void billPreservesFinancialFactsAndRejectsStaleOrUnauthorizedData()throws Exception{
  var result=tools.execute("getMyOrderBill",Map.of("reservationId","123"),"Bearer owner",deadline());assertEquals(600L,result.get("paidFen"));assertEquals(200L,result.get("refundedFen"));assertEquals(400L,result.get("netPaidFen"));assertEquals(50L,result.get("pendingRefundFen"));assertNotNull(result.get("ruleSnapshot"));assertEquals("Bearer owner",authorization);
  parking.put("version",3);assertEquals("BILL_SYNC_PENDING",assertThrows(Problem.class,()->tools.execute("getMyOrderBill",Map.of("reservationId","123"),"Bearer owner",deadline())).code);
  status=403;assertEquals("TOOL_FORBIDDEN",assertThrows(Problem.class,()->tools.execute("getMyOrderBill",Map.of("reservationId","123"),"Bearer other",deadline())).code);
 }
}
