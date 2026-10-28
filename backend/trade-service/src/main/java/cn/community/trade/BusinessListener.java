package cn.community.trade;
import cn.community.foundation.Events;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
@Component @Profile("business")
public class BusinessListener {
 private final Events events;
 public BusinessListener(Events events){this.events=events;}
 @RabbitListener(queues="trade-service.business") public void receive(String body){events.receive(body);}
}
