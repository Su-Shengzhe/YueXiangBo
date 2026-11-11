package cn.community.identity;
import cn.community.foundation.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service @Profile("business")
public class AiTools implements AutoCloseable {
 private final String parking,trade;private final AiKnowledge knowledge;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public AiTools(AiKnowledge knowledge,@Value("${business.parking-url:http://127.0.0.1:9082}")String parking,@Value("${business.trade-url:http://127.0.0.1:9083}")String trade){this.knowledge=knowledge;this.parking=parking;this.trade=trade;}
 private Object get(String base,String path,String bearer,long deadline)throws Exception{
  long millis=Math.min(2000,(deadline-System.nanoTime())/1000000);Problem.require(millis>0,504,"AI_TIMEOUT","请求已超时");
  var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofMillis(millis)).GET();if(bearer!=null)builder.header("Authorization",bearer);
  var r=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  if(Set.of(401,403,404).contains(r.statusCode()))throw new Problem(403,"TOOL_FORBIDDEN","无权访问该记录或记录不存在");
  Problem.require(r.statusCode()==200,r.statusCode()==422?422:503,"TOOL_UNAVAILABLE","业务数据暂不可用或查询条件不合法");return Data.map(r.body()).get("data");
 }
 @SuppressWarnings("unchecked") public List<Map<String,Object>> communities(long deadline)throws Exception{return (List<Map<String,Object>>)get(parking,"/api/v1/communities",null,deadline);}
 private static String enc(Object v){return URLEncoder.encode(v.toString(),StandardCharsets.UTF_8);}
 public static void only(Map<String,Object> args,String... allowed){Problem.require(Set.of(allowed).containsAll(args.keySet()),422,"INVALID_TOOL_ARGUMENT","工具参数含不允许字段");}
 @SuppressWarnings("unchecked") public Map<String,Object> execute(String name,Map<String,Object> args,String bearer,long deadline)throws Exception{
  if(name.equals("searchAvailableSpaces")){
   only(args,"communityId","startAt","endAt","maxHourlyPriceFen","spaceType");
   long community=Data.number(args,"communityId");String start=Instant.parse(Data.str(args,"startAt")).toString(),end=Instant.parse(Data.str(args,"endAt")).toString();
   String path="/api/v1/spaces/search?communityId="+community+"&startAt="+enc(start)+"&endAt="+enc(end)+"&page=1&size=10";
   if(args.get("maxHourlyPriceFen")!=null){long price=Data.number(args,"maxHourlyPriceFen");Problem.require(price>=0,422,"INVALID_PRICE","预算必须非负");path+="&maxHourlyFen="+price;}
   if(args.get("spaceType")!=null)path+="&spaceType="+enc(args.get("spaceType"));
   var result=(Map<String,Object>)get(parking,path,bearer,deadline);
   var cards=new ArrayList<Map<String,Object>>();for(var raw:(List<Map<String,Object>>)result.get("items")){var card=new LinkedHashMap<String,Object>();for(String key:List.of("id","name","windowId","communityId","spaceType","locationDesc","hourlyFen","estimatedFen"))if(raw.containsKey(key))card.put(key,raw.get(key));card.put("queriedAt",Instant.now().toString());cards.add(card);}
   return Map.of("cards",cards,"total",result.get("total"),"slots",args);
  }
  if(name.equals("retrieveParkingRules")){
   only(args,"query","communityId");long community=args.get("communityId")==null?0:Data.number(args,"communityId");
   return Map.of("citations",knowledge.retrieve(Management.text(args,"query",500),community));
  }
  if(name.equals("getMyOrderBill")){
   only(args,"reservationId");long id=Data.number(args,"reservationId");
   Map<String,Object> raw,parkingSnapshot;
   long syncDeadline=Math.min(deadline,System.nanoTime()+Duration.ofSeconds(3).toNanos());
   long backoffMillis=50;
   while(true){
    raw=(Map<String,Object>)get(trade,"/api/v1/bills/"+id,bearer,deadline);
    parkingSnapshot=(Map<String,Object>)get(parking,"/api/v1/reservations/"+id,bearer,deadline);
    if(Data.number(raw,"reservationVersion")==Data.number(parkingSnapshot,"version"))break;
    Problem.require(syncDeadline-System.nanoTime()>Duration.ofMillis(backoffMillis).toNanos(),503,"BILL_SYNC_PENDING","停车状态仍在同步");
    Thread.sleep(backoffMillis);
    backoffMillis=Math.min(400,backoffMillis*2);
   }
   var facts=new LinkedHashMap<String,Object>();for(String key:List.of("reservationId","billVersion","status","prepayFen","overtimeFen","paidPrepayFen","paidOvertimeFen","refundFen","settlementStatus"))facts.put(key,raw.get(key));
   long paid=Data.number(raw,"paidPrepayFen")+Data.number(raw,"paidOvertimeFen"),refunded=Data.number(raw,"refundFen");facts.put("paidFen",paid);facts.put("refundedFen",refunded);facts.put("netPaidFen",paid-refunded);
   long pending=0;if(raw.get("attempts") instanceof List<?> attempts)for(Object item:attempts){var a=(Map<String,Object>)item;if("REFUND".equals(a.get("kind"))&&Set.of("CREATED","SUBMITTED","UNKNOWN","REVIEW").contains(a.get("status")))pending+=Data.number(a,"amountFen");}
   facts.put("pendingRefundFen",pending);facts.put("billAsOf",Instant.now().toString());
   for(String key:List.of("startAt","endAt","enteredAt","exitedAt","hourlyFen"))facts.put(key,parkingSnapshot.get(key));
   facts.put("ruleSnapshot",parkingSnapshot.get("ruleSnapshot")==null?null:Data.map(parkingSnapshot.get("ruleSnapshot").toString()));
   facts.put("parkingVersion",parkingSnapshot.get("version"));
   return facts;
  }
  throw new Problem(403,"TOOL_FORBIDDEN","不允许的工具");
 }
 public static List<Map<String,Object>> schemas(){
  return List.of(tool("searchAvailableSpaces","查找实时车位。必须明确社区、起止时间；缺失或含糊时不要调用，返回clarify JSON。预算省略/null不限价格。",Map.of("communityId",type("string"),"startAt",type("string"),"endAt",type("string"),"maxHourlyPriceFen",Map.of("type",List.of("integer","null")),"spaceType",Map.of("type","string","enum",List.of("STANDARD","COMPACT","EV"))),List.of("communityId","startAt","endAt")),
   tool("retrieveParkingRules","检索有来源的停车规则，仅查询与问题相关的关键词。",Map.of("query",type("string"),"communityId",type("string")),List.of("query")),
   tool("getMyOrderBill","获取本人订单账单；reservationId必须来自用户输入或订单上下文，不能猜测。",Map.of("reservationId",type("string")),List.of("reservationId")));
 }
 private static Map<String,Object> type(String s){return Map.of("type",s);}
 private static Map<String,Object> tool(String name,String description,Map<String,Object> properties,List<String> required){return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",Map.of("type","object","properties",properties,"required",required,"additionalProperties",false)));}
 @jakarta.annotation.PreDestroy public void close(){http.shutdownNow();}
}
