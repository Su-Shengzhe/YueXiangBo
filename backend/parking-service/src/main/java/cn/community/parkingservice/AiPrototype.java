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
  Problem.require(admission.tryAcquire(),429,"AI_BUSY","AI服务繁忙，仍可使用普通业务页面");
  Future<String> future=null;long submitted=System.nanoTime();CountDownLatch started=new CountDownLatch(1);
  try{
   final String selected=scenario;future=workers.submit(()->{started.countDown();return model.answer(question,selected);});
   if(!started.await(1,TimeUnit.SECONDS))throw new TimeoutException("queue");
   String answer=future.get(Math.max(1,20000-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-submitted)),TimeUnit.MILLISECONDS);
   return Map.of("mode","STUB","realModelAccepted",false,"answer",answer,"writeToolsEnabled",false);
  }catch(TimeoutException e){timeouts.incrementAndGet();throw new Problem(504,"AI_TIMEOUT","模型桩超时，普通业务仍可用");}
   catch(RejectedExecutionException e){throw new Problem(429,"AI_BUSY","模型桩繁忙");}
   catch(InterruptedException e){Thread.currentThread().interrupt();throw new Problem(503,"AI_UNAVAILABLE","模型桩中断");}
   catch(ExecutionException e){throw new Problem(503,"AI_UNAVAILABLE","模型桩故障，普通业务仍可用");}
   finally{if(future!=null){future.cancel(true);workers.purge();}admission.release();}
 }
 @GetMapping("/api/v1/ai/status") public Map<String,Object> status(){return Map.of("mode","STUB","realModelAccepted",false,"active",workers.getActiveCount(),"queued",workers.getQueue().size(),"timeouts",timeouts.get(),"provider","UNDECIDED","budget","UNDECIDED");}
 @PreDestroy public void close(){workers.shutdownNow();}
}
