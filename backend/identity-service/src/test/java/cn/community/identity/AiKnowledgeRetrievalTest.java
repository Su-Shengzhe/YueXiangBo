package cn.community.identity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiKnowledgeRetrievalTest {
 @Test void clauseTextIsSearchableWithoutDuplicatingEveryWordInKeywords(){var rule=Map.<String,Object>of("keywords","费用 超时","content","无免费宽限、优惠券和跨日封顶。");for(String q:new String[]{"免费宽限","优惠券","跨日封顶"})assertTrue(AiKnowledge.score(q,rule)>0);assertEquals(0,AiKnowledge.score("天气预报",rule));}
}
