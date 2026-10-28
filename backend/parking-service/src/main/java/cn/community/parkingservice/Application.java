package cn.community.parkingservice;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"cn.community.parkingservice","cn.community.foundation"})
public class Application {
 public static void main(String[] args) { SpringApplication.run(Application.class,args); }
}
