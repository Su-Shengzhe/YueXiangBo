package cn.community.trade;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"cn.community.trade","cn.community.foundation"})
public class Application {
 public static void main(String[] args) { SpringApplication.run(Application.class,args); }
}
