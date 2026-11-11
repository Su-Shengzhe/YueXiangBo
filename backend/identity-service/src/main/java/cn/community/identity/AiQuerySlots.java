package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import java.time.Instant;
final class AiQuerySlots {
 private static final String UTC="([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z)";
 private static final java.util.regex.Pattern SEARCH=java.util.regex.Pattern.compile(
   "^(?:请)?(?:查询|查找|搜索)\\s*(.+?)(?:[（(]编号\\s*([0-9]+)[）)])?\\s*从\\s*"+UTC+"\\s*(?:到|至)\\s*"+UTC+
   "\\s*的(?:可用|空闲)?车位(?:[，,]\\s*(?:不限价格|每小时(?:最多|不超过)\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(元|分)))?(?:[，,]\\s*不限类型)?[。！!]?$");
 static Map<String,Object> structuredSearch(String text,List<Map<String,Object>> communities){
  var match=SEARCH.matcher(text.strip());
  if(!match.matches())return null;
  var hits=communities.stream().filter(c->Data.str(c,"name").equals(match.group(1).strip())
    &&(match.group(2)==null||Data.str(c,"id").equals(match.group(2)))).toList();
  if(hits.size()!=1)return null;
  var args=new LinkedHashMap<String,Object>();
  args.put("communityId",hits.getFirst().get("id"));
  try{
   args.put("startAt",Instant.parse(match.group(3)).toString());args.put("endAt",Instant.parse(match.group(4)).toString());
   if(match.group(5)!=null){
    var price=new java.math.BigDecimal(match.group(5));
    args.put("maxHourlyPriceFen",(match.group(6).equals("元")?price.movePointRight(2):price).longValueExact());
   }
  }catch(java.time.DateTimeException|ArithmeticException invalid){return null;}
  return args;
 }
 static Map<String,Object> explicitCommunity(String text,Map<String,Object> args,List<Map<String,Object>> communities){
  var ids=communities.stream().map(c->Data.str(c,"id")).filter(id->!id.isBlank()&&java.util.regex.Pattern.compile("(?<![0-9])"+java.util.regex.Pattern.quote(id)+"(?![0-9])").matcher(text).find()).distinct().toList();
  var result=new LinkedHashMap<>(args);
  if(ids.size()==1)result.put("communityId",ids.getFirst());
  var price=java.util.regex.Pattern.compile("每小时\\s*(?:最多|不超过|上限(?:为)?)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(元|分)").matcher(text);
  if(price.find()){
   var amount=new java.math.BigDecimal(price.group(1));
   if(price.group(2).equals("元"))amount=amount.movePointRight(2);
   if(!price.find())try{result.put("maxHourlyPriceFen",amount.longValueExact());}catch(ArithmeticException e){throw new Problem(422,"INVALID_TOOL_ARGUMENT","每小时价格须为可表示的整数分");}
  }
  Problem.require(communities.stream().anyMatch(c->Data.str(c,"id").equals(Data.str(result,"communityId"))),422,"INVALID_TOOL_ARGUMENT","社区编号必须来自可用社区列表");
  return result;
 }
 static Map<String,Object> merge(Map<String,Object> previous,Map<String,Object> patch){
  AiTools.only(patch,"communityId","startAt","endAt","maxHourlyPriceFen","spaceType");
  var next=new LinkedHashMap<>(previous);
  for(var e:patch.entrySet()){
   String key=e.getKey();Object value=e.getValue();
   if(value==null){next.remove(key);continue;}
   if(key.equals("communityId"))Problem.require(Data.number(patch,key)>0,422,"INVALID_TOOL_ARGUMENT","社区编号无效");
   if(key.equals("maxHourlyPriceFen"))Problem.require(Data.number(patch,key)>=0,422,"INVALID_TOOL_ARGUMENT","预算不可为负");
   if(key.equals("startAt")||key.equals("endAt"))try{Instant.parse(value.toString());}catch(Exception ex){throw new Problem(422,"INVALID_TOOL_ARGUMENT","时间格式无效");}
   if(key.equals("spaceType"))Problem.require(Set.of("STANDARD","COMPACT","EV").contains(value),422,"INVALID_TOOL_ARGUMENT","车位类型无效");
   next.put(key,value);
  }
  return next;
 }
}
