package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;

/** Server-owned fact references; never accept model-provided money or clause text. */
final class AiBillReferences {
 private static final Map<String,List<String>> FIELDS=Map.of("PREPAY",List.of("prepayFen"),"OVERTIME",List.of("overtimeFen"),"REFUND_SUCCEEDED",List.of("refundedFen"),"REFUND_PENDING",List.of("pendingRefundFen"),"NET_PAYMENT",List.of("paidFen","refundedFen","netPaidFen"));
 static List<Map<String,Object>> allowed(Map<String,Object> facts){
  var codes=new ArrayList<>(List.of("PREPAY","NET_PAYMENT"));
  for(var pair:Map.of("OVERTIME","overtimeFen","REFUND_SUCCEEDED","refundedFen","REFUND_PENDING","pendingRefundFen").entrySet())if(Data.number(facts,pair.getValue())>0)codes.add(pair.getKey());
  Collections.sort(codes);String prefix="bill:"+Data.str(facts,"reservationId")+":v"+Data.number(facts,"billVersion")+":";
  var rule=(Map<?,?>)facts.get("ruleSnapshot");Problem.require(rule!=null,422,"HISTORICAL_RULE_MISSING","历史规则缺失");
  String citation="rule:"+rule.get("ruleId")+":v"+rule.get("version");
  return codes.stream().map(code->Map.<String,Object>of("reasonCode",code,"factRefs",FIELDS.get(code).stream().map(f->prefix+f).toList(),"citations",List.of(citation))).toList();
 }
 static List<Map<String,Object>> validate(Map<String,Object> selection,Map<String,Object> facts){
  AiTools.only(selection,"reservationId","billVersion","explanations");
  Problem.require(Data.str(selection,"reservationId").equals(Data.str(facts,"reservationId"))&&Data.number(selection,"billVersion")==Data.number(facts,"billVersion"),422,"BILL_FACT_MISMATCH","账单版本或归属不一致");
  var expected=allowed(facts);
  Problem.require(selection.get("explanations") instanceof List<?>,422,"BILL_FACT_MISMATCH","缺少事实引用");
  var actual=(List<?>)selection.get("explanations");
  // Order is irrelevant; duplicates and any altered, missing or extra reference are rejected.
  Problem.require(actual.size()==expected.size(),422,"BILL_FACT_MISMATCH","账单解释项数量无效");
  var seen=new HashSet<Object>();
  for(Object item:actual){
   Problem.require(item instanceof Map<?,?>,422,"BILL_FACT_MISMATCH","账单解释格式无效");var row=(Map<?,?>)item;
   Problem.require(row.keySet().equals(Set.of("reasonCode","factRefs","citations"))&&seen.add(row.get("reasonCode")),422,"BILL_FACT_MISMATCH","解释字段或重复原因无效");
   var wanted=expected.stream().filter(e->e.get("reasonCode").equals(row.get("reasonCode"))).findFirst().orElseThrow(()->new Problem(422,"BILL_FACT_MISMATCH","账单原因无效"));
   for(String field:List.of("factRefs","citations")){Problem.require(row.get(field) instanceof List<?>,422,"BILL_FACT_MISMATCH","引用必须为数组");var refs=(List<?>)row.get(field);Problem.require(refs.size()==new HashSet<>(refs).size()&&new HashSet<>(refs).equals(new HashSet<>((List<?>)wanted.get(field))),422,"BILL_FACT_MISMATCH","账单引用无效");}
  }
  return expected;
 }
}
