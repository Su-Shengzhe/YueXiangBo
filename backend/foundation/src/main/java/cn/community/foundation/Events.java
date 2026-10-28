package cn.community.foundation;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration @Profile("business")
public class Events {
 public interface Handler {void accept(String type,Map<String,Object> payload);}
 private final JdbcTemplate db;private final RabbitTemplate rabbit;private final TransactionTemplate tx;private final List<Handler> handlers;private final String service;
 public Events(JdbcTemplate db,RabbitTemplate rabbit,PlatformTransactionManager manager,List<Handler> handlers,@Value("${spring.application.name}")String service){this.db=db;this.rabbit=rabbit;this.tx=new TransactionTemplate(manager);this.handlers=handlers;this.service=service;}
 @Bean Declarables businessQueues(){List<Declarable> declarations=new ArrayList<>();var exchange=new DirectExchange("parking.business");var dead=new DirectExchange("parking.business.dead");declarations.add(exchange);declarations.add(dead);
  for(String target:List.of("parking-service","trade-service")){var q=QueueBuilder.durable(target+".business").deadLetterExchange("parking.business.dead").deadLetterRoutingKey(target).build();var d=QueueBuilder.durable(target+".dead").build();declarations.add(q);declarations.add(d);declarations.add(BindingBuilder.bind(q).to(exchange).with(target));declarations.add(BindingBuilder.bind(d).to(dead).with(target));}return new Declarables(declarations);}
 public static void add(JdbcTemplate db,String target,String type,Map<String,Object> payload){db.update("INSERT INTO outbox_event(event_id,target,event_type,payload,status,next_retry_at) VALUES(?,?,?,?,'PENDING',CURRENT_TIMESTAMP(3))",UUID.randomUUID().toString(),target,type,Data.json(payload));}
 @Scheduled(fixedDelay=1000) public void publish(){
  // Claim immediately before each publish: a slow broker cannot exhaust leases of a prefetched batch.
  for(int i=0;i<20;i++){
   String token=UUID.randomUUID().toString();
   var row=tx.execute(s->{
    db.update("UPDATE outbox_event SET status='FAILED',lease_token=NULL WHERE status='PUBLISHING' AND lease_until<CURRENT_TIMESTAMP(3) AND retry_count>=5");
    var rows=db.queryForList("SELECT * FROM outbox_event WHERE ((status='PENDING' AND next_retry_at<=CURRENT_TIMESTAMP(3)) OR (status='PUBLISHING' AND lease_until<CURRENT_TIMESTAMP(3))) AND retry_count<5 ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
    if(rows.isEmpty())return null;
    var picked=rows.getFirst();
    db.update("UPDATE outbox_event SET status='PUBLISHING',lease_until=?,lease_token=?,retry_count=retry_count+1 WHERE id=?",Data.ts(Instant.now().plusSeconds(15)),token,picked.get("id"));
    return picked;
   });
   if(row==null)break;
   try{
    var event=Map.of("eventId",row.get("event_id"),"eventType",row.get("event_type"),"schemaVersion",1,"producer",service,"occurredAt",Data.instant(row.get("created_at")).toString(),"payload",Data.map(row.get("payload").toString()));
    var cd=new CorrelationData(UUID.randomUUID().toString());
    rabbit.convertAndSend("parking.business",row.get("target").toString(),Data.json(event),cd);
    var confirm=cd.getFuture().get(3,TimeUnit.SECONDS);
    if(!confirm.isAck()||cd.getReturned()!=null)throw new IllegalStateException("Publish not confirmed");
    db.update("UPDATE outbox_event SET status='SENT',published_at=CURRENT_TIMESTAMP(3),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",row.get("id"),token);
   }catch(Exception e){
    int retries=((Number)row.get("retry_count")).intValue()+1;
    db.update("UPDATE outbox_event SET status=?,next_retry_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",retries>=5?"FAILED":"PENDING",Data.ts(Instant.now().plusSeconds(1L<<retries)),row.get("id"),token);
    org.slf4j.LoggerFactory.getLogger(Events.class).warn("Outbox {} failed ({})",row.get("event_id"),e.getClass().getSimpleName());
    if(e instanceof InterruptedException){Thread.currentThread().interrupt();return;}
    break; // Broker failures back off rather than delaying the scheduler for a whole batch.
   }
  }
 }
 @SuppressWarnings("unchecked") public void receive(String body){
  Map<String,Object> event=Data.map(body);Problem.require(Data.number(event,"schemaVersion")==1,422,"EVENT_VERSION","Unsupported event version");String eventId=Data.str(event,"eventId");
  UUID.fromString(eventId);String expected=service.equals("trade-service")?"parking-service":"trade-service";
  Problem.require(expected.equals(Data.str(event,"producer")),422,"EVENT_PRODUCER","Unexpected event producer");
  try{tx.executeWithoutResult(s->{db.update("INSERT INTO consumed_event(event_id) VALUES(?)",eventId);for(Handler h:handlers)h.accept(Data.str(event,"eventType"),(Map<String,Object>)event.get("payload"));});}
  catch(org.springframework.dao.DuplicateKeyException e){if(db.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id=?",Integer.class,eventId)==0)throw e;}
 }
}
