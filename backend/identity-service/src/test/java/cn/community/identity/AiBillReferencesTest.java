package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiBillReferencesTest {
 Map<String,Object> facts(){return Map.of("reservationId","123","billVersion",7,"overtimeFen",100,"refundedFen",0,"pendingRefundFen",20,"ruleSnapshot",Map.of("ruleId","BILLING","version",1));}
 Map<String,Object> selection(){return new HashMap<>(Map.of("reservationId","123","billVersion",7,"explanations",AiBillReferences.allowed(facts())));}
 @Test void validatesAllSupportedFacts(){assertEquals(4,AiBillReferences.validate(selection(),facts()).size());}
 @Test void referenceOrderDoesNotChangeMeaning(){var s=selection();var rows=new ArrayList<Map<String,Object>>();for(var e:AiBillReferences.allowed(facts())){var row=new HashMap<>(e);var refs=new ArrayList<>((List<?>)row.get("factRefs"));Collections.reverse(refs);row.put("factRefs",refs);rows.add(row);}Collections.reverse(rows);s.put("explanations",rows);assertEquals(4,AiBillReferences.validate(s,facts()).size());}
 @Test void refusesOtherBillAndOldVersion(){for(String field:List.of("reservationId","billVersion")){var s=selection();s.put(field,99);assertThrows(Problem.class,()->AiBillReferences.validate(s,facts()));}}
 @Test void rejectsForgedReferencesAndMissingFacts(){var s=selection();s.put("explanations",List.of(Map.of("reasonCode","PREPAY","factRefs",List.of("bill:999:v7:prepayFen"),"citations",List.of("rule:BILLING:v999"))));assertThrows(Problem.class,()->AiBillReferences.validate(s,facts()));}
 @Test void rejectsAmountsAndFreeText(){var s=selection();s.put("amount",1);assertThrows(Problem.class,()->AiBillReferences.validate(s,facts()));}
}
