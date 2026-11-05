package cn.community.trade;

import cn.community.foundation.Data;
import cn.community.foundation.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable simulator: the same business number always denotes the same operation. */
final class SimulatedChannel {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private String simulatedResult = "SUCCESS";
    private long delayMillis;
    private boolean loseSubmitResponse;
    private final java.util.concurrent.atomic.AtomicLong submissions = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong queries = new java.util.concurrent.atomic.AtomicLong();

    void configure(boolean demo, String result, long delay, boolean loseResponse) {
        if (!java.util.Set.of("SUCCESS", "FAILED", "UNKNOWN").contains(result) || delay < 0 || delay > 60000)
            throw new IllegalArgumentException("Invalid channel simulation configuration");
        if (!demo && (!"SUCCESS".equals(result) || delay != 0 || loseResponse))
            throw new IllegalArgumentException("Channel faults require business.demo=true");
        simulatedResult = result;
        delayMillis = delay;
        loseSubmitResponse = loseResponse;
    }
    long submissionCount() { return submissions.get(); }
    long queryCount() { return queries.get(); }
    private void delay() {
        try { if (delayMillis > 0) Thread.sleep(delayMillis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("CHANNEL_INTERRUPTED", e); }
    }
    SimulatedChannel(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    String submit(String number, String kind, long amount) {
        return recorded(number, "SUBMIT", () -> submitInternal(number, kind, amount));
    }
    private String submitInternal(String number, String kind, long amount) {
        submissions.incrementAndGet();
        String result = tx.execute(s -> {
            db.update("INSERT IGNORE INTO channel_operation(channel_no,kind,amount_fen,result) VALUES(?,?,?,?)", number, kind, amount, simulatedResult);
            var receipt = db.queryForMap("SELECT * FROM channel_operation WHERE channel_no=?", number);
            Problem.require(kind.equals(receipt.get("kind")) && amount == ((Number) receipt.get("amount_fen")).longValue(),409,"CHANNEL_CONFLICT","模拟渠道请求不一致");
            return Data.str(receipt,"result");
        });
        delay();
        if (loseSubmitResponse) throw new IllegalStateException("CHANNEL_RESPONSE_LOST");
        return result;
    }

    /** A recovery query must never create a new receipt or initiate a charge. */
    String query(String number, String kind, long amount) {
        return recorded(number, "QUERY", () -> queryInternal(number, kind, amount));
    }
    private String queryInternal(String number, String kind, long amount) {
        queries.incrementAndGet();
        delay();
        var receipts = db.queryForList("SELECT * FROM channel_operation WHERE channel_no=?", number);
        if (receipts.isEmpty()) return "NOT_FOUND";
        var receipt = receipts.getFirst();
        Problem.require(kind.equals(receipt.get("kind")) && amount == ((Number) receipt.get("amount_fen")).longValue(),
                409, "CHANNEL_CONFLICT", "模拟渠道请求不一致");
        return Data.str(receipt, "result");
    }

    private String recorded(String number, String operation, java.util.function.Supplier<String> action) {
        String id = java.util.UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> db.update("INSERT INTO channel_call(id,channel_no,operation,state) VALUES(?,?,?,'STARTED')", id, number, operation));
        try {
            String result = action.get();
            tx.executeWithoutResult(s -> db.update("UPDATE channel_call SET state='RETURNED',outcome=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=?", result, id));
            return result;
        } catch (RuntimeException failure) {
            String code = failure instanceof IllegalStateException && "CHANNEL_RESPONSE_LOST".equals(failure.getMessage())
                    ? "CHANNEL_RESPONSE_LOST" : failure.getClass().getSimpleName();
            try {
                tx.executeWithoutResult(s -> db.update("UPDATE channel_call SET state='ERROR',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=?", code, id));
            } catch (RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
            throw failure;
        }
    }
}
