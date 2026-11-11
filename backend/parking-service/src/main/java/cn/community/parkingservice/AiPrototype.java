package cn.community.parkingservice;
import cn.community.foundation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
@RestController @Profile("business")
public class AiPrototype {
 private final LlmGateway gateway;
 public AiPrototype(LlmGateway gateway){this.gateway=gateway;}
 private final ThreadPoolExecutor workers=new ThreadPoolExecutor(10,10,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(10),Thread.ofPlatform().name("ai-stub-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 @org.springframework.beans.factory.annotation.Value("${business.demo:false}") private boolean demo;
 private final Semaphore admission=new Semaphore(20);
 private final AtomicInteger timeouts=new AtomicInteger();
 public interface ModelAdapter {String answer(String question,String scenario)throws Exception;}
 private final ModelAdapter model=(question,scenario)->{
  if(scenario.equals("slow"))Thread.sleep(30000);
  if(scenario.equals("fail"))throw new IllegalStateException("Stub failure");
  return "模型桩示例：请先选择社区和预约时间查询可用车位；支付成功并确认预约后，由物业办理入场。费用以账单接口为准。";
 };
 @PostMapping("/api/v1/ai/prototype") public Map<String,Object> answer(@RequestBody Map<String,Object> in,HttpServletRequest req){
  Actor actor=Actor.of(req);String question=Data.str(in,"question"),scenario=Data.str(in,"scenario");if(scenario.isBlank())scenario="normal";
  Problem.require(!question.isBlank()&&question.length()<=500,422,"INVALID_ARGUMENT","问题长度须为1—500字");
  Problem.require(List.of("normal","slow","fail").contains(scenario),422,"INVALID_ARGUMENT","场景无效");if(!scenario.equals("normal")){actor.role("PLATFORM");Problem.require(demo,403,"DEMO_DISABLED","故障模拟未启用");}
  final boolean real=scenario.equals("normal")&&gateway.enabled();
  Problem.require(admission.tryAcquire(),429,"AI_BUSY","AI服务繁忙，仍可使用普通业务页面");
  Future<String> future=null;long submitted=System.nanoTime();CountDownLatch started=new CountDownLatch(1);
  try{
   final String selected=scenario;future=workers.submit(()->{started.countDown();
    if(!real)return model.answer(question,selected);
    var response=gateway.complete(List.of(Map.of("role","system","content","这是停车助手接入验证。不能访问用户订单、车位或资金事实，不得编造实时结果；涉及具体业务时引导用户使用普通页面。"),Map.of("role","user","content",question)),List.of(),java.time.Duration.ofMillis(Math.max(1,20000-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-submitted))));
    Object first=((List<?>)response.get("choices")).getFirst();
    if(!(first instanceof Map<?,?> choice)||!(choice.get("message") instanceof Map<?,?> message)||!(message.get("content") instanceof String answer)||answer.isBlank())throw new Problem(502,"AI_INVALID_RESPONSE","模型网关未返回文本");
    return answer;
   });
   if(!started.await(1,TimeUnit.SECONDS))throw new TimeoutException("queue");
   String answer=future.get(Math.max(1,20000-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-submitted)),TimeUnit.MILLISECONDS);
   return Map.of("mode",real?"GATEWAY":"STUB","realModelAccepted",false,"answer",answer,"writeToolsEnabled",false);
  }catch(TimeoutException e){timeouts.incrementAndGet();throw new Problem(504,"AI_TIMEOUT","模型桩超时，普通业务仍可用");}
   catch(RejectedExecutionException e){throw new Problem(429,"AI_BUSY","模型桩繁忙");}
   catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(503,"AI_UNAVAILABLE","模型桩中断");}
   catch(ExecutionException e){if(e.getCause() instanceof Problem p)throw p;throw new Problem(503,"AI_UNAVAILABLE","模型服务故障，普通业务仍可用");}
   finally{if(future!=null){future.cancel(true);workers.purge();}admission.release();}
 }
 @GetMapping("/api/v1/ai/status") public Map<String,Object> status(){return Map.of("mode",gateway.enabled()?"GATEWAY":"STUB","realModelAccepted",false,"active",workers.getActiveCount(),"queued",workers.getQueue().size(),"timeouts",timeouts.get(),"provider","GATEWAY_MANAGED","budget","GATEWAY_MANAGED");}
 @PreDestroy public void close(){workers.shutdownNow();}
}
