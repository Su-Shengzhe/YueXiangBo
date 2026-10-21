package cn.community.parkingservice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("probe & (local | container)")
@RequestMapping("/api/dev")
public class ProbeController {
    private final ProbeStore store;
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final StringRedisTemplate redis;
    private final String token;
    public ProbeController(ProbeStore store, JdbcTemplate jdbc, RabbitTemplate rabbit,
                           StringRedisTemplate redis, @Value("${probe.token}") String token) {
        if (token.length() < 32) throw new IllegalArgumentException("PROBE_TOKEN must be at least 32 characters");
        this.store=store; this.jdbc=jdbc; this.rabbit=rabbit; this.redis=redis; this.token=token;
    }
    private void authorize(String supplied) {
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    @Bean Queue probeQueue() { return new Queue("parking.environment.probe", true); }
    @GetMapping("/probes")
    public List<Map<String,Object>> list(@RequestHeader(value="X-Probe-Token",required=false) String supplied) {
        authorize(supplied); return store.list();
    }
    @GetMapping("/probes/exists")
    public Map<String,Boolean> exists(@RequestHeader(value="X-Probe-Token",required=false) String supplied, @RequestParam String label) {
        authorize(supplied);
        return Map.of("exists",jdbc.queryForObject("SELECT COUNT(*) FROM env_probe WHERE label=?",Integer.class,label)>0);
    }
    public record ProbeRequest(String label) {}
    @PostMapping("/probes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,String> create(@RequestHeader(value="X-Probe-Token",required=false) String supplied, @RequestBody ProbeRequest input) {
        authorize(supplied);
        if (input == null) throw new IllegalArgumentException("body is required");
        return store.create(input.label(), false);
    }
    @PostMapping("/rollback")
    public void rollback(@RequestHeader(value="X-Probe-Token",required=false) String supplied, @RequestBody ProbeRequest input) {
        authorize(supplied);
        if (input == null) throw new IllegalArgumentException("body is required");
        try { store.create(input.label(), true); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "intentional rollback"); }
    }
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,String> send(@RequestHeader(value="X-Probe-Token",required=false) String supplied) {
        authorize(supplied);
        String id=UUID.randomUUID().toString();
        rabbit.convertAndSend("", "parking.environment.probe", id);
        return Map.of("id",id,"status","SUBMITTED");
    }
    @RabbitListener(queues="parking.environment.probe")
    public void consume(String id) {
        UUID.fromString(id);
        try { jdbc.update("INSERT INTO env_probe_message(id) VALUES (?)",id); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { /* duplicate delivery of this probe */ }
    }
    @GetMapping("/messages/{id}")
    public Map<String,Object> message(@RequestHeader(value="X-Probe-Token",required=false) String supplied,@PathVariable String id) {
        authorize(supplied);
        return Map.of("id",id,"received",jdbc.queryForObject("SELECT COUNT(*) FROM env_probe_message WHERE id=?",Integer.class,id)>0);
    }
    @GetMapping("/redis")
    public Map<String,String> redis(@RequestHeader(value="X-Probe-Token",required=false) String supplied) {
        authorize(supplied);
        String key="environment:probe:"+UUID.randomUUID();
        redis.opsForValue().set(key,"ok",java.time.Duration.ofSeconds(30));
        String value=redis.opsForValue().get(key);
        if (!"ok".equals(value)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        redis.delete(key);
        return Map.of("status","ok");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
