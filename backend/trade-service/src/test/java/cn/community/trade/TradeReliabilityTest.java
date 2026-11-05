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
    @Test void typedRequestRejectsFractionalAndOverflowingNumbers(){
        for(String input:List.of("{\"reservationId\":1,\"amountFen\":1.5}","{\"reservationId\":1.5,\"amountFen\":1}","{\"reservationId\":1,\"amountFen\":9223372036854775808}","{\"reservationId\":1,\"amountFen\":1,\"previousApplicationId\":1.5}")){
            var request=Data.JSON.readValue(input,RefundContracts.ApplicationRequest.class);
            assertEquals(422,assertThrows(Problem.class,request::values).status);
        }
        var valid=Data.JSON.readValue("{\"reservationId\":\"123\",\"amountFen\":100}",RefundContracts.ApplicationRequest.class);
        assertEquals(123L,valid.values().get("reservationId"));assertEquals(100L,valid.values().get("amountFen"));
    }
    @Test void approvalUnknownResponseReplaysOriginalResult(){
        seed(1);worker.process();db.update("UPDATE trade_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP");
        var workflow=new RefundApplications(db,manager);
        long id=Long.parseLong(workflow.apply(Map.of("reservationId",1,"amountFen",200,"reason","test"),"apply-key",request()).get("id").toString());
        var input=Map.<String,Object>of("decision","APPROVE","note","verified");
        var first=workflow.decide(id,input,"decision-key",admin());
        assertEquals(Data.json(first),Data.json(workflow.decide(id,input,"decision-key",admin())));
        assertThrows(Problem.class,()->workflow.decide(id,Map.of("decision","REJECT","note","changed"),"decision-key",admin()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE kind='REFUND'",Integer.class));
    }
    @Test void incompleteExistingAllocationIsRejectedAndRolledBack(){
        seed(1);worker.process();
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(2,1,1,'REFUND',200,'CREATED','BAD-MAPPING')");
        db.update("INSERT INTO refund_receipt_allocation VALUES(2,1,100)");
        assertEquals("REFUND_HISTORY_CONFLICT",assertThrows(Problem.class,()->new TransactionTemplate(manager).executeWithoutResult(s->RefundLedger.mapHistory(db,1))).code);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM refund_application",Integer.class));
        worker.process();
        assertEquals("REVIEW",db.queryForObject("SELECT status FROM payment_attempt WHERE id=2",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE channel_no='BAD-MAPPING'",Integer.class));
    }
    @Test void twentyBrokenOrdersDoNotStarveFollowingPayment(){
        for(long id=1;id<=20;id++){
            seed(id);
            db.update("UPDATE payment_attempt SET kind='REFUND' WHERE id=?",id);
        }
        seed(21);
        db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");
        worker.process();worker.process();
        assertEquals(20,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE status='REVIEW'",Integer.class));
        assertEquals("SUCCESS",db.queryForObject("SELECT status FROM payment_attempt WHERE id=21",String.class));
    }
    @Test void mapsOneLegacyRefundAcrossReceiptsWithoutAnotherPayment(){
        seed(1);db.update("UPDATE payment_attempt SET status='SUCCESS',amount_fen=300");
        db.update("UPDATE trade_order SET paid_prepay_fen=500,refund_fen=400,status='CANCELLED'");
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(2,1,1,'PREPAY',200,'SUCCESS','PAID-2'),(3,1,1,'REFUND',400,'SUCCESS','OLD-REFUND')");
        new TransactionTemplate(manager).executeWithoutResult(s->{RefundLedger.mapHistory(db,1);RefundLedger.mapHistory(db,1);});
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM refund_receipt_allocation WHERE attempt_id=3",Integer.class));
        assertEquals(400L,db.queryForObject("SELECT SUM(amount_fen) FROM refund_receipt_allocation WHERE attempt_id=3",Long.class));
        assertEquals("OLD-REFUND",db.queryForObject("SELECT channel_no FROM payment_attempt WHERE id=3",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM channel_call",Integer.class));
        new TransactionTemplate(manager).executeWithoutResult(s->{TradeAccounting.refundIfClosed(db,1);TradeAccounting.refundIfClosed(db,1);});
        assertEquals(100L,db.queryForObject("SELECT SUM(amount_fen) FROM payment_attempt WHERE kind='REFUND' AND status='CREATED'",Long.class));
    }
    @Test void failedApplicationRetryPreservesTargetAndCompletesAfterSuccess(){
        seed(1);worker.process();db.update("UPDATE trade_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP");
        var workflow=new RefundApplications(db,manager);
        var app=workflow.apply(Map.of("reservationId","1","amountFen",200,"reason","测试失败恢复"),"retry-app",request());
        long application=Long.parseLong(app.get("id").toString());
        workflow.decide(application,Map.of("decision","APPROVE","note","同意"),admin());
        long attempt=db.queryForObject("SELECT id FROM payment_attempt WHERE application_id=?",Long.class,application);
        var channel=db.queryForObject("SELECT channel_no FROM payment_attempt WHERE id=?",String.class,attempt);
        db.update("INSERT INTO channel_operation VALUES(?,'REFUND',200,'FAILED')",channel);
        db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");worker.process();
        assertEquals("PARTIAL_FAILED",db.queryForObject("SELECT status FROM refund_application",String.class));
        api.retryRefund(attempt,"retry-app-attempt",Map.of("reason","核查确定失败"),admin());
        api.retryRefund(attempt,"retry-app-ancestor",Map.of("reason","重复请求"),admin());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE application_id=?",Integer.class,application));
        db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");worker.process();
        assertEquals("COMPLETED",db.queryForObject("SELECT status FROM refund_application",String.class));
        assertEquals(200L,db.queryForObject("SELECT refund_fen FROM trade_order",Long.class));
    }
    @Test void deadlineAndNewEvidenceAreEnforcedWithoutExtendingWindow(){
        seed(1);worker.process();
        var completed=java.time.Instant.parse("2030-01-01T00:00:00Z");
        db.update("UPDATE trade_order SET status='COMPLETED',completed_at=?",Data.ts(completed));
        var time=new java.util.concurrent.atomic.AtomicReference<>(completed.plusSeconds(60));
        var clock=new BusinessClock("",false){@Override public java.time.Instant now(){return time.get();}};
        var workflow=new RefundApplications(db,manager,clock);
        var input=Map.<String,Object>of("reservationId","1","amountFen",100,"reason","争议","evidence","原证据");
        var first=workflow.apply(input,"first-evidence",request());long id=Long.parseLong(first.get("id").toString());
        workflow.decide(id,Map.of("decision","REJECT","note","需补充材料"),admin());
        assertEquals(first.get("id"),workflow.apply(input,"duplicate-facts",request()).get("id"));
        var second=new HashMap<>(input);second.put("previousApplicationId",id);second.put("evidence","新证据");
        time.set(completed.plus(java.time.Duration.ofDays(7)));
        assertEquals("REFUND_DEADLINE",assertThrows(Problem.class,()->workflow.apply(second,"at-deadline",request())).code);
        time.set(completed.plus(java.time.Duration.ofDays(7)).minusMillis(1));
        var changed=new HashMap<>(input);changed.put("reason","仅改变理由");changed.put("previousApplicationId",id);
        assertEquals("NEW_EVIDENCE_REQUIRED",assertThrows(Problem.class,()->workflow.apply(changed,"no-new-evidence",request())).code);
        var resubmitted=workflow.apply(second,"new-evidence",request());
        assertEquals(Long.toString(id),resubmitted.get("previousApplicationId"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM refund_application",Integer.class));
    }
    @Test void partialRefundApplicationApprovesAndPostsOnce(){
        seed(1);worker.process();db.update("UPDATE trade_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE reservation_id=1");
        var workflow=new RefundApplications(db,manager);
        var input=Map.<String,Object>of("reservationId","1","amountFen",200,"reason","停车服务争议");
        var application=workflow.apply(input,"application-key",request());
        assertEquals(Data.json(application),Data.json(workflow.apply(input,"application-key",request())));
        long id=Long.parseLong(application.get("id").toString());
        assertThrows(Problem.class,()->workflow.decide(id,Map.of("decision","APPROVE","note","通过"),request()));
        workflow.decide(id,Map.of("decision","APPROVE","note","核实部分退款"),admin());
        assertThrows(Problem.class,()->workflow.decide(id,Map.of("decision","APPROVE","note","重复"),admin()));
        db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00'");worker.process();worker.process();
        assertEquals(200L,db.queryForObject("SELECT refund_fen FROM trade_order WHERE reservation_id=1",Long.class));
        assertEquals("COMPLETED",db.queryForObject("SELECT status FROM refund_application WHERE id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE kind='REFUND'",Integer.class));
    }
    JdbcTemplate db;
    DataSourceTransactionManager manager;
    PaymentWorker worker;
    BusinessTrade api;

    @BeforeEach void setup() throws Exception {
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
        db.execute("CREATE TABLE audit_log(actor_id BIGINT,action VARCHAR(60),resource_id VARCHAR(60))");
        try (var input=getClass().getResourceAsStream("/db/business/V003__refund_retry_chain.sql");
             var connection=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                connection,
                new org.springframework.core.io.ByteArrayResource(input.readAllBytes()));
        }
        db.execute("ALTER TABLE payment_attempt ADD COLUMN query_count INT NOT NULL DEFAULT 0");
        try (var input=getClass().getResourceAsStream("/db/business/V005__channel_call_audit.sql");
             var connection=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(input.readAllBytes()));
        }
        try (var input=getClass().getResourceAsStream("/db/business/V006__refund_applications.sql"); var connection=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(input.readAllBytes())); }
        try (var input=getClass().getResourceAsStream("/db/business/V007__refund_deadline_evidence.sql"); var connection=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(input.readAllBytes())); }
        try (var input=getClass().getResourceAsStream("/db/business/V008__refund_receipt_allocations.sql"); var connection=java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(input.readAllBytes())); }
        api = new BusinessTrade(db, manager);
    }

    void seed(long id) {
        db.update("INSERT INTO trade_order(reservation_id,user_id,prepay_fen) VALUES(?,1,500)", id);
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,next_retry_at) VALUES(?,?,1,'PREPAY',500,'CREATED',?,TIMESTAMP '2000-01-01 00:00:00')", id, id, "SIM-" + id);
    }

    @Test void lostChannelResponseKeepsReceiptAndQueryDoesNotResubmit() {
        var channel = new SimulatedChannel(db, manager);
        channel.configure(true, "SUCCESS", 0, true);
        assertThrows(IllegalStateException.class, () -> channel.submit("lost", "PREPAY", 500));
        assertEquals("SUCCESS", channel.query("lost", "PREPAY", 500));
        assertEquals(1, channel.submissionCount());
        assertEquals(1, channel.queryCount());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE operation='SUBMIT' AND state='ERROR' AND error_code='CHANNEL_RESPONSE_LOST'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM channel_call WHERE operation='QUERY' AND state='RETURNED' AND outcome='SUCCESS'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM channel_operation", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> channel.configure(false, "UNKNOWN", 0, false));
        assertThrows(IllegalArgumentException.class, () -> channel.configure(false, "SUCCESS", 1, false));
        assertThrows(IllegalArgumentException.class, () -> channel.configure(false, "SUCCESS", 0, true));
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
        for (int i=0;i<5;i++) {
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

    @Test void confirmedChannelFailureNeverPostsMoneyOrSuccessEvent() {
        seed(1);
        db.update("INSERT INTO channel_operation VALUES('SIM-1','PREPAY',500,'FAILED')");
        worker.process(); worker.process();
        assertEquals("FAILED", db.queryForObject("SELECT status FROM payment_attempt WHERE id=1", String.class));
        assertEquals(0L, db.queryForObject("SELECT paid_prepay_fen FROM trade_order", Long.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
        assertEquals("FAILED", db.queryForObject("SELECT result FROM channel_operation", String.class));
    }

    @Test void unknownChannelResultKeepsOriginalAttemptUntilAuthoritativeSuccess() {
        seed(1);
        db.update("INSERT INTO channel_operation VALUES('SIM-1','PREPAY',500,'UNKNOWN')");
        worker.process();
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM payment_attempt WHERE id=1", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
        api.pay(Map.of("reservationId","1","kind","PREPAY"), UUID.randomUUID().toString(), request());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class));
        db.update("UPDATE channel_operation SET result='SUCCESS' WHERE channel_no='SIM-1'");
        db.update("UPDATE payment_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=1");
        worker.process(); worker.process();
        assertEquals(500L, db.queryForObject("SELECT paid_prepay_fen FROM trade_order", Long.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM channel_operation", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
    }

    @Test void unknownRefundDoesNotReduceNetReceipts() {
        seed(1);
        db.update("UPDATE trade_order SET paid_prepay_fen=500,status='CANCELLED'");
        db.update("UPDATE payment_attempt SET kind='REFUND'");
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(2,1,1,'PREPAY',500,'SUCCESS','PAID-2')");
        db.update("INSERT INTO channel_operation VALUES('SIM-1','REFUND',500,'UNKNOWN')");
        worker.process();
        assertEquals(0L, db.queryForObject("SELECT refund_fen FROM trade_order", Long.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM payment_attempt WHERE id=1", String.class));
    }

    @Test void expiredLeaseAtLimitGoesToReviewWithoutChannelCall() {
        seed(1);
        db.update("UPDATE payment_attempt SET status='SUBMITTED',retry_count=6,query_count=5,lease_token='crashed',lease_until=TIMESTAMP '2000-01-01 00:00:00'");
        worker.process();worker.process();
        assertEquals("REVIEW",db.queryForObject("SELECT status FROM payment_attempt",String.class));
        assertEquals(6,db.queryForObject("SELECT retry_count FROM payment_attempt",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM channel_operation",Integer.class));
    }

    @Test void injectedClockControlsDueTimeAndConfiguredRecoveryBudget() {
        seed(1);
        db.update("INSERT INTO channel_operation VALUES('SIM-1','PREPAY',500,'UNKNOWN')");
        var now = java.time.Instant.parse("2030-01-01T00:00:00Z");
        var time = new java.util.concurrent.atomic.AtomicReference<>(now);
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return time.get(); }
        };
        var configured = new PaymentWorker(db, manager, clock, 2, 45, 7, 10);
        configured.process();
        assertEquals(now.plusSeconds(7), db.queryForObject("SELECT next_retry_at FROM payment_attempt", java.sql.Timestamp.class).toInstant());
        configured.process();
        assertEquals(1, db.queryForObject("SELECT retry_count FROM payment_attempt", Integer.class));
        time.set(now.plusSeconds(7));
        configured.process();
        assertEquals(1, db.queryForObject("SELECT query_count FROM payment_attempt", Integer.class));
        assertEquals(now.plusSeconds(17), db.queryForObject("SELECT next_retry_at FROM payment_attempt", java.sql.Timestamp.class).toInstant());
        time.set(now.plusSeconds(17));
        configured.process();
        assertEquals("REVIEW", db.queryForObject("SELECT status FROM payment_attempt", String.class));
        assertEquals(3, db.queryForObject("SELECT retry_count FROM payment_attempt", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
    }

    @Test void rejectsUnsafeRecoveryConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentWorker(db, manager, java.time.Clock.systemUTC(), 0, 30, 5, 60));
        assertThrows(IllegalArgumentException.class, () -> new PaymentWorker(db, manager, java.time.Clock.systemUTC(), 5, 0, 5, 60));
        assertThrows(IllegalArgumentException.class, () -> new PaymentWorker(db, manager, java.time.Clock.systemUTC(), 5, 30, 10, 5));
    }

    @Test void recoveryAfterClaimCrashDoesNotCreateChannelReceipt() {
        seed(1);
        db.update("UPDATE payment_attempt SET status='SUBMITTED',retry_count=1,lease_token='crashed',lease_until=TIMESTAMP '2000-01-01 00:00:00'");
        worker.process();
        assertEquals("UNKNOWN", db.queryForObject("SELECT status FROM payment_attempt", String.class));
        assertEquals(1, db.queryForObject("SELECT query_count FROM payment_attempt", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM channel_operation", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ledger_entry", Integer.class));
    }

    MockHttpServletRequest admin() {
        var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(2,Set.of("PLATFORM"),Set.of()));return r;
    }

    @Test void failedRefundRetriesConcurrentlyWithOneSuccessorAndOneRefund() throws Exception {
        seed(1);
        db.update("UPDATE trade_order SET status='CANCELLED',paid_prepay_fen=500");
        db.update("UPDATE payment_attempt SET kind='REFUND',status='FAILED'");
        db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no) VALUES(2,1,1,'PREPAY',500,'SUCCESS','PAID-2')");
        new TransactionTemplate(manager).executeWithoutResult(s->TradeAccounting.refundIfClosed(db,1));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE kind='REFUND'",Integer.class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->api.retryRefund(1,UUID.randomUUID().toString(),Map.of("reason","恢复退款"),admin()).getBody());
            var b=pool.submit(()->api.retryRefund(1,UUID.randomUUID().toString(),Map.of("reason","恢复退款"),admin()).getBody());
            assertEquals(((Map<?,?>)a.get(5,TimeUnit.SECONDS)).get("id"),((Map<?,?>)b.get(5,TimeUnit.SECONDS)).get("id"));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE retry_of_id=1",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='REFUND_RETRY'",Integer.class));
        worker.process();worker.process();
        assertEquals(500L,db.queryForObject("SELECT refund_fen FROM trade_order",Long.class));
        api.retryRefund(1,UUID.randomUUID().toString(),Map.of("reason","重复操作"),admin());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE kind='REFUND'",Integer.class));
    }

    @Test void refundRetryRejectsNonAdminAndUnknownResult() {
        seed(1);
        db.update("UPDATE payment_attempt SET kind='REFUND',status='UNKNOWN'");
        assertThrows(Problem.class,()->api.retryRefund(1,"retry-key-1",Map.of("reason","恢复"),request()));
        assertThrows(Problem.class,()->api.retryRefund(1,"retry-key-2",Map.of("reason","恢复"),admin()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_attempt",Integer.class));
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
