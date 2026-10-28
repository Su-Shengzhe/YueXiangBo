package cn.community.identity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"cn.community.identity","cn.community.foundation"})
public class Application {
 public static void main(String[] args) { SpringApplication.run(Application.class,args); }
}
