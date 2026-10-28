package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class TradeReliabilityTest {
    JdbcTemplate db;
    DataSourceTransactionManager manager;
    PaymentWorker worker;
    BusinessTrade api;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        db = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        db.execute("CREATE TABLE trade_order(reservation_id BIGINT PRIMARY KEY,user_id BIGINT,prepay_fen BIGINT,overtime_fen BIGINT DEFAULT 0,paid_prepay_fen BIGINT DEFAULT 0,paid_overtime_fen BIGINT DEFAULT 0,refund_fen BIGINT DEFAULT 0,status VARCHAR(24) DEFAULT 'WAIT_PAY',version INT DEFAULT 0,bill_version BIGINT DEFAULT 0)");
        db.execute("CREATE TABLE payment_attempt(id BIGINT PRIMARY KEY,reservation_id BIGINT,user_id BIGINT,kind VARCHAR(16),amount_fen BIGINT,status VARCHAR(20),channel_no VARCHAR(80) UNIQUE,retry_count INT DEFAULT 0,next_retry_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR(36),last_error VARCHAR(80))");
        db.execute("CREATE TABLE channel_operation(channel_no VARCHAR(80) PRIMARY KEY,kind VARCHAR(16),amount_fen BIGINT,result VARCHAR(20))");
        db.execute("CREATE TABLE ledger_entry(id BIGINT AUTO_INCREMENT PRIMARY KEY,reservation_id BIGINT,channel_no VARCHAR(80) UNIQUE,kind VARCHAR(16),amount_fen BIGINT)");
        db.execute("CREATE TABLE outbox_event(event_id VARCHAR(36),target VARCHAR(40),event_type VARCHAR(60),payload VARCHAR(8000),status VARCHAR(20),next_retry_at TIMESTAMP)");
        db.execute("CREATE TABLE business_sequence(name VARCHAR(30) PRIMARY KEY,last_id BIGINT)");
        db.update("INSERT INTO business_sequence VALUES('business',1000)");
        db.execute("CREATE TABLE idempotency_record(actor_id BIGINT,operation VARCHAR(40),request_key VARCHAR(80),request_hash VARCHAR(64),response_json VARCHAR(8000),PRIMARY KEY(actor_id,operation,request_key))");
        worker = new PaymentWorker(db, manager);
        api = new BusinessTrade(db, manager);
    }

    void seed(long id) {
        db.update("INSERT INTO trade_order(reservation_id,user_id,prepay_fen) VALUES(?,1,500)", id);
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(?,?,1,'PREPAY',500,'CREATED',?)", id, id, "SIM-" + id);
    }

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute("actor", new Actor(1, Set.of("DRIVER"), Set.of()));
        return request;
    }

    @Test void poisonAttemptDoesNotBlockOthersAndReviewPreventsAnotherCharge() {
        seed(1); seed(2);
        db.update("INSERT INTO channel_operation VALUES('SIM-1','PREPAY',999,'SUCCESS')");
        worker.process();
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM payment_attempt WHERE id=1", String.class));
        assertEquals("SUCCESS", db.queryForObject("SELECT status FROM payment_attempt WHERE id=2", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        // Backoff must prevent an immediate second channel submission.
        worker.process();
        assertEquals(1, db.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=1", Integer.class));
        for (int i=0;i<4;i++) {
            db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=1");
            worker.process();
        }
        assertEquals("REVIEW", db.queryForObject("SELECT status FROM payment_attempt WHERE id=1", String.class));
        api.pay(Map.of("reservationId","1","kind","PREPAY"), UUID.randomUUID().toString(), request());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE reservation_id=1", Integer.class));
    }

    @Test void recoversReceiptCommittedBeforeLedgerWithoutDuplicateAccounting() {
        seed(1);
        db.update("INSERT INTO channel_operation VALUES('SIM-1','PREPAY',500,'SUCCESS')");
        db.update("UPDATE payment_attempt SET status='SUBMITTED',lease_token='old',lease_until=TIMESTAMP '2000-01-01 00:00:00'");
        worker.process(); worker.process();
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM channel_operation", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals(500L, db.queryForObject("SELECT paid_prepay_fen FROM trade_order", Long.class));
        assertTrue(db.queryForObject("SELECT bill_version FROM trade_order", Long.class) > 0);
        assertNull(db.queryForObject("SELECT lease_token FROM payment_attempt", String.class));
    }

    @Test void concurrentWorkersPostOnlyOneLedgerEntry() throws Exception {
        seed(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(worker::process);
            var second = pool.submit(new PaymentWorker(db, manager)::process);
            first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals(500L, db.queryForObject("SELECT paid_prepay_fen FROM trade_order", Long.class));
    }

    @Test void billReadCannotMixOldAmountWithNewPaymentStatus() throws Exception {
        seed(1);
        var read = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var once = new AtomicBoolean();
        var observedDb = new JdbcTemplate(db.getDataSource()) {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                var result = super.queryForList(sql,args);
                if (sql.equals("SELECT * FROM trade_order WHERE reservation_id=?") && once.compareAndSet(false,true)) {
                    read.countDown();
                    try {
                        assertTrue(writerStarted.await(2,TimeUnit.SECONDS));
                        assertFalse(committed.await(200,TimeUnit.MILLISECONDS), "writer must wait for the bill snapshot lock");
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                return result;
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var writer = pool.submit(() -> {
                try { assertTrue(read.await(2,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                writerStarted.countDown();
                new TransactionTemplate(manager).executeWithoutResult(s -> {
                    db.queryForMap("SELECT reservation_id FROM trade_order WHERE reservation_id=1 FOR UPDATE");
                    db.update("UPDATE trade_order SET paid_prepay_fen=500,bill_version=bill_version+1 WHERE reservation_id=1");
                    db.update("UPDATE payment_attempt SET status='SUCCESS' WHERE id=1");
                });
                committed.countDown();
            });
            var bill = new BusinessTrade(observedDb,manager).get(1,request());
            assertEquals(0L, bill.get("paidPrepayFen"));
            assertEquals("CREATED", ((Map<?,?>)((List<?>)bill.get("attempts")).getFirst()).get("status"));
            writer.get(5,TimeUnit.SECONDS);
            assertEquals(500L, api.get(1,request()).get("paidPrepayFen"));
        }
    }

    @Test void databaseIdsAreUniqueAcrossConcurrentAllocators() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Long>>();
            for (int i=0;i<40;i++) futures.add(pool.submit(() -> new TransactionTemplate(manager).execute(s -> Data.id(db))));
            var ids = new HashSet<Long>();
            for (var future : futures) assertTrue(ids.add(future.get(5,TimeUnit.SECONDS)));
            assertEquals(40,ids.size());
        }
        assertThrows(IllegalStateException.class, () -> Data.id(db));
    }
}
