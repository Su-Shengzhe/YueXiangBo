package cn.community.identity;
import cn.community.foundation.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Metered supplier calls. Prices are conservative CNY budget equivalents, not charges. */
@Service @Profile("business")
public class AiModelCalls {
 static final int DEFAULT_OUTPUT_TOKENS=1536;
 private final AiGatewayAdmin registry;private final LlmGateway transport;private final Path secrets;
 public AiModelCalls(AiGatewayAdmin registry,LlmGateway transport,@Value("${llm.credentials-directory:deploy/secrets}")String directory){this.registry=registry;this.transport=transport;this.secrets=Path.of(directory).toAbsolutePath().normalize();}
 static long reservation(long input,long output,long outputLimit){return Math.max(10000,(Math.addExact(Math.multiplyExact(16384,input),Math.multiplyExact(outputLimit,output))+999999)/1000000+1000);}
 public record Binding(long version,LlmGateway.Route route,long inputPrice,long outputPrice,String priceVersion,long outputLimit){}
 public Binding capture(){return capture(registry.route(null));}
 public Binding capture(Map<String,Object> row){
  String reference=row.get("credentialRef").toString();
  Path file=secrets.resolve(reference).normalize();Problem.require(file.getParent().equals(secrets),503,"AI_CREDENTIAL_INVALID","凭据配置无效");
  var p=new Properties();try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}catch(Exception e){throw new Problem(503,"AI_CREDENTIAL_UNAVAILABLE","模型凭据不可用");}
  String key=p.getProperty("api-key","").trim();Problem.require(!key.isBlank(),503,"AI_CREDENTIAL_UNAVAILABLE","模型凭据尚未配置");
  Problem.require(Boolean.TRUE.equals(row.get("freeAccessApproved"))||row.get("paidScope")!=null,503,"AI_PRICING_UNCONFIRMED","该配置尚未确认免费资格及调用授权");
  long version=((Number)row.get("routeVersion")).longValue();
  long outputLimit=row.get("maxOutputTokens")==null?DEFAULT_OUTPUT_TOKENS:Data.number(row,"maxOutputTokens");
  var config=new Properties();config.setProperty("enabled","true");config.setProperty("api-key",key);config.setProperty("base-url",row.get("baseUrl").toString());config.setProperty("model",row.get("modelName").toString());config.setProperty("route-version",Long.toString(version));config.setProperty("max-output-tokens",Long.toString(outputLimit));
  if(Boolean.TRUE.equals(row.get("disableThinking")))config.setProperty("thinking-mode","disabled");
  return new Binding(version,transport.captureRoute(config),Data.number(row,"inputPriceMicroyuanPerMillion"),Data.number(row,"outputPriceMicroyuanPerMillion"),Data.str(row,"priceVersion"),outputLimit);
 }
 public Map<String,Object> complete(Binding binding,List<Map<String,Object>> messages,List<Map<String,Object>> tools,Duration remaining)throws InterruptedException{
  return complete(binding,messages,tools,remaining,null,null);
 }
 public Map<String,Object> complete(Binding binding,List<Map<String,Object>> messages,List<Map<String,Object>> tools,Duration remaining,String messageId,String purpose)throws InterruptedException{
  int bytes=Data.json(Map.of("messages",messages,"tools",tools)).getBytes(StandardCharsets.UTF_8).length;
  Problem.require(bytes<=8192,422,"AI_CONTEXT_TOO_LARGE","请求上下文过长");
  Problem.require(!remaining.isZero()&&!remaining.isNegative(),504,"AI_TIMEOUT","调用期限已到");
  long reserved=reservation(binding.inputPrice(),binding.outputPrice(),binding.outputLimit());
  transport.checkAdmission(binding.route());
  String id=UUID.randomUUID().toString(),account=transport.accountKey(binding.route());registry.admit(id,binding.version(),reserved,account,16384L+binding.outputLimit(),messageId,purpose);
  // Unknown outcomes retain reservation. No automatic retry creates a second billable call.
  Map<String,Object> result;
  try{result=transport.complete(binding.route(),messages,tools,remaining);}catch(Problem p){registry.callFailure(id,p.code);if(p.code.equals("AI_BUSY"))registry.sharedCooldown(account,transport.cooldownUntil(binding.route()));throw p;}catch(InterruptedException e){registry.callFailure(id,"AI_INTERRUPTED");throw e;}finally{registry.releaseAdmission(id);}
  Object reported=result.get("usage");
  if(reported==null){registry.callFailure(id,"USAGE_MISSING");return result;}
  if(!(reported instanceof Map<?,?> usage)){registry.callFailure(id,"USAGE_INVALID");return result;}
  if(usage.get("prompt_tokens")==null||usage.get("completion_tokens")==null){
   registry.callFailure(id,"USAGE_INCOMPLETE");return result;
  }
  long input,output,amount;
  try{
   input=tokens(usage.get("prompt_tokens"));
   output=tokens(usage.get("completion_tokens"));
   long weighted=Math.addExact(Math.multiplyExact(input,binding.inputPrice()),Math.multiplyExact(output,binding.outputPrice()));
   amount=Math.ceilDiv(weighted,1000000L);
  }catch(ArithmeticException|NumberFormatException e){
   registry.callFailure(id,"USAGE_INVALID");return result;
  }
  registry.settleUsage(id,input,output,binding.priceVersion(),amount);
  return result;
 }
 private static long tokens(Object value){
  if(!(value instanceof Number number))throw new NumberFormatException("Token count must be numeric");
  long count=new java.math.BigDecimal(number.toString()).longValueExact();
  if(count<0)throw new ArithmeticException("Negative token count");
  return count;
 }
}
