package cn.community.foundation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only endpoint; BusinessWeb requires the private service token. */
@RestController
@Profile("business")
public class OutboxOperations {
    private final JdbcTemplate db;
    private final RabbitAdmin rabbit;
    private final String service;

    public OutboxOperations(JdbcTemplate db, RabbitTemplate template,
                            @Value("${spring.application.name}") String service) {
        this.db = db;
        this.rabbit = new RabbitAdmin(template);
        this.service = service;
    }

    @GetMapping("/internal/v1/outbox/status")
    public Map<String,Object> status() {
        var result = new LinkedHashMap<String,Object>();
        result.put("service",service);
        result.put("counts",db.queryForList("SELECT status,COUNT(*) AS count FROM outbox_event GROUP BY status"));
        result.put("oldestPendingAgeSeconds",db.queryForObject("""
                SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP(3)),0)
                FROM outbox_event WHERE status IN ('PENDING','PUBLISHING')
                """,Long.class));
        if (service.equals("parking-service") || service.equals("trade-service")) {
            try {
                var queue = rabbit.getQueueProperties(service + ".dead");
                result.put("deadLetterCount",queue == null ? -1 : queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
                result.put("brokerReachable",queue != null);
            } catch (Exception unavailable) {
                result.put("deadLetterCount",-1);
                result.put("brokerReachable",false);
            }
        }
        return result;
    }
}
