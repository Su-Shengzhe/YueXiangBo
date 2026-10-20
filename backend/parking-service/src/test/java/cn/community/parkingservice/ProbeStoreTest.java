package cn.community.parkingservice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

class ProbeStoreTest {
    JdbcTemplate jdbc;
    ProbeStore store;
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE env_probe(id VARCHAR(36) PRIMARY KEY,label VARCHAR(80),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        store=new ProbeStore(jdbc,new DataSourceTransactionManager(ds));
    }
    @Test void commitsAndReadsRecord() {
        var created=store.create("smoke",false);
        assertEquals("smoke",jdbc.queryForObject("SELECT label FROM env_probe WHERE id=?",String.class,created.get("id")));
    }
    @Test void failureRollsBackInsertedRecord() {
        assertThrows(IllegalStateException.class,()->store.create("rollback",true));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM env_probe",Integer.class));
    }
    @Test void rejectsInvalidLabelsWithoutWriting() {
        for (String label : new String[]{null,""," ","x".repeat(81)})
            assertThrows(IllegalArgumentException.class,()->store.create(label,false));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM env_probe",Integer.class));
    }
}
