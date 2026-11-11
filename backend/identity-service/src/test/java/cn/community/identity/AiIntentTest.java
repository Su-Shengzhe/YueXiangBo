package cn.community.identity;
import cn.community.foundation.Problem;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AiIntentTest {
 @Test void clarifiesOnlyExplicitMissingFields(){var r=AiConversation.withoutTool(Map.of("content","{\"action\":\"CLARIFY\",\"missing\":[\"endAt\"]}"));assertEquals("CLARIFY",r.get("status"));assertEquals("请补充：结束时间。",r.get("answer"));}
 @Test void refusalIsNotMistakenForClarification(){assertEquals("REFUSED",AiConversation.withoutTool(Map.of("content","{\"action\":\"REFUSE\"}")).get("status"));}
 @Test void missingToolAndMalformedIntentAreNotClarification(){for(String content:new String[]{"plain text","{}","{\"action\":\"CLARIFY\",\"missing\":[]}","{\"action\":\"CLARIFY\",\"missing\":[\"apiKey\"]}"})assertThrows(Problem.class,()->AiConversation.withoutTool(Map.of("content",content)));}
}
