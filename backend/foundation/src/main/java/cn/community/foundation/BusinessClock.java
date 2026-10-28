package cn.community.foundation;
import java.time.Instant;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile("business")
public class BusinessClock {
 private final Path file;
 public BusinessClock(@Value("${business.clock-file:}") String file,@Value("${business.demo:false}") boolean demo){this.file=!demo||file.isBlank()?null:Path.of(file);}
 public boolean isDemo(){return file!=null;}
 public Instant now(){
  try{
   if(file==null||!Files.exists(file))return Instant.now();
   String value=Files.readString(file).trim();
   return value.isEmpty()?Instant.now():Instant.now().plusMillis(Long.parseLong(value));
  }catch(Exception e){throw new IllegalStateException("Invalid demo clock offset",e);}
 }
 public synchronized void advance(Instant at){
  Problem.require(file!=null,403,"DEMO_DISABLED","演示时钟未启用");
  Problem.require(!at.isBefore(now()),409,"CLOCK_BACKWARDS","演示时间只能前进");
  try{
   Path temp=Files.createTempFile(file.getParent(),"clock-",".tmp");
   long offset=java.time.Duration.between(Instant.now(),at).toMillis();
   Files.writeString(temp,Long.toString(offset));
   Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }catch(Exception e){throw new IllegalStateException(e);}
 }
}
