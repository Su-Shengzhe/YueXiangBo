package cn.community.parkingservice;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("probe & (local | container)")
public class ProbeStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ProbeStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }
    public Map<String, String> create(String label, boolean rollback) {
        if (label == null || label.isBlank() || label.length() > 80) {
            throw new IllegalArgumentException("label must contain 1-80 characters");
        }
        String id = UUID.randomUUID().toString();
        return tx.execute(status -> {
            jdbc.update("INSERT INTO env_probe(id,label) VALUES (?,?)", id, label);
            if (rollback) throw new IllegalStateException("intentional rollback");
            return Map.of("id", id, "label", label);
        });
    }
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id,label,created_at FROM env_probe ORDER BY created_at DESC LIMIT 100");
    }
}
