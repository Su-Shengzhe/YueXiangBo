package cn.community.identity;
import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.*;
@RestController @Profile("business")
public class AiProviderChecks {
 private final AiGatewayAdmin admin;private final AiModelCalls calls;
 public AiProviderChecks(AiGatewayAdmin admin,AiModelCalls calls){this.admin=admin;this.calls=calls;}
 @PostMapping("/api/v1/admin/ai-gateway/providers/{id}/check")
 @SuppressWarnings("unchecked") public Map<String,Object> check(@PathVariable long id,HttpServletRequest request)throws InterruptedException{
  var actor=Actor.of(request);actor.role("PLATFORM");actor.permission("MODEL_MANAGE");
  var row=admin.provider(id);String fingerprint=admin.credentialFingerprint(row);var binding=calls.capture(row);
  long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
  var tool=Map.<String,Object>of("type","function","function",Map.of("name","probe","description","Return marker","parameters",Map.of("type","object","properties",Map.of("marker",Map.of("type","string")),"required",List.of("marker"),"additionalProperties",false)));
  String marker=UUID.randomUUID().toString();
  var result=calls.complete(binding,List.of(Map.of("role","user","content","Call probe with marker exactly "+marker)),List.of(tool),Duration.ofNanos(deadline-System.nanoTime()));
  var message=(Map<?,?>)((Map<?,?>)((List<?>)result.get("choices")).getFirst()).get("message");
  Problem.require(message.get("tool_calls") instanceof List<?> list&&!list.isEmpty(),422,"PROVIDER_CHECK_FAILED","供应商不支持所需工具调用");
  for(var raw:(List<?>)message.get("tool_calls")){var function=(Map<?,?>)((Map<?,?>)raw).get("function");Problem.require("probe".equals(function.get("name"))&&Map.of("marker",marker).equals(Data.map(function.get("arguments").toString())),422,"PROVIDER_CHECK_FAILED","工具参数不符合契约");}
  var json=calls.complete(binding,List.of(Map.of("role","user","content","Return only a JSON object with one field marker equal to "+marker)),List.of(),Duration.ofNanos(deadline-System.nanoTime()));
  var response=(Map<?,?>)((Map<?,?>)((List<?>)json.get("choices")).getFirst()).get("message");
  Problem.require(Map.of("marker",marker).equals(AiConversation.parseJsonText(response.get("content").toString())),422,"PROVIDER_CHECK_FAILED","结构化响应校验失败");
  admin.checked(id,fingerprint,actor);return Map.of("checked",true,"providerRevisionId",Long.toString(id));
 }
}
