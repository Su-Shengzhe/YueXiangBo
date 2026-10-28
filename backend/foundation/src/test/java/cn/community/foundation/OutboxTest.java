package cn.community.foundation;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OutboxTest {
    Map<String,Object> event(int id) {
        return Map.of("id",id,"event_id",UUID.randomUUID().toString(),"event_type","ReservationCreated",
                "target","trade-service","created_at",Instant.now().toString(),"payload","{}","retry_count",0);
    }
    @Test void oneTickDrainsBoundedBatchRatherThanOneEvent() {
        var db=mock(JdbcTemplate.class);
        var rabbit=mock(RabbitTemplate.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        var reads=new AtomicInteger();
        when(db.queryForList(startsWith("SELECT * FROM outbox_event")))
                .thenAnswer(call->List.of(event(reads.incrementAndGet())));
        doAnswer(call->{
            CorrelationData confirmation=call.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).convertAndSend(eq("parking.business"),eq("trade-service"),any(Object.class),any(CorrelationData.class));
        new Events(db,rabbit,manager,List.of(),"parking-service").publish();
        assertEquals(20,reads.get());
        verify(rabbit,times(20)).convertAndSend(eq("parking.business"),eq("trade-service"),any(Object.class),any(CorrelationData.class));
    }
    @Test void brokerFailureStopsBatchAndSchedulesRetry() {
        var db=mock(JdbcTemplate.class);
        var rabbit=mock(RabbitTemplate.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(db.queryForList(startsWith("SELECT * FROM outbox_event"))).thenReturn(List.of(event(1)));
        doThrow(new IllegalStateException("offline")).when(rabbit)
                .convertAndSend(eq("parking.business"),eq("trade-service"),any(Object.class),any(CorrelationData.class));
        new Events(db,rabbit,manager,List.of(),"parking-service").publish();
        verify(rabbit,times(1)).convertAndSend(eq("parking.business"),eq("trade-service"),any(Object.class),any(CorrelationData.class));
        verify(db).update(startsWith("UPDATE outbox_event SET status=?,next_retry_at=?"),eq("PENDING"),any(),eq(1),anyString());
    }
}
