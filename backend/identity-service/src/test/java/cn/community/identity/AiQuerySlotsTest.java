package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiQuerySlotsTest {
 @Test void explicitZeroPriceDoesNotBecomeUnlimited(){var list=List.of(Map.<String,Object>of("id","10"));assertEquals(0L,AiQuerySlots.explicitCommunity("社区10，每小时最多0 分",Map.of("communityId","10"),list).get("maxHourlyPriceFen"));assertEquals(125L,AiQuerySlots.explicitCommunity("每小时不超过1.25元",Map.of("communityId","10"),list).get("maxHourlyPriceFen"));}
 @Test void explicitKnownIdIsNotTranscribedByModel(){var list=List.of(Map.<String,Object>of("id","979430303000"),Map.<String,Object>of("id","10"));assertEquals("979430303000",AiQuerySlots.explicitCommunity("查社区编号979430303000的车位",Map.of("communityId","97943030303000"),list).get("communityId"));assertThrows(Problem.class,()->AiQuerySlots.explicitCommunity("社区19794303030001",Map.of("communityId","19794303030001"),list));assertEquals("10",AiQuerySlots.explicitCommunity("社区10和979430303000",Map.of("communityId","10"),list).get("communityId"));}
 @Test void omittedBudgetIsRetainedAndNullExplicitlyClears(){var old=Map.<String,Object>of("communityId","10","maxHourlyPriceFen",500);assertEquals(500,AiQuerySlots.merge(old,Map.of("endAt","2026-11-20T12:00:00Z")).get("maxHourlyPriceFen"));var patch=new HashMap<String,Object>();patch.put("maxHourlyPriceFen",null);var next=AiQuerySlots.merge(old,patch);assertFalse(next.containsKey("maxHourlyPriceFen"));assertEquals("10",next.get("communityId"));assertEquals(500,old.get("maxHourlyPriceFen"));}
 @Test void rejectsInvalidFields(){for(var patch:List.of(Map.<String,Object>of("userId",2),Map.<String,Object>of("startAt","明天"),Map.<String,Object>of("maxHourlyPriceFen",-1)))assertThrows(Problem.class,()->AiQuerySlots.merge(Map.of(),patch));}
}
