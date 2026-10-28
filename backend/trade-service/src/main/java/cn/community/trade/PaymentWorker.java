package cn.community.trade;

import cn.community.foundation.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Each attempt has its own transaction, retry schedule and fenced lease. */
@Component
@Profile("business")
public class PaymentWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorker.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public PaymentWorker(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    private void lock(long reservation) {
        db.queryForMap("SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE", reservation);
    }

    @Scheduled(fixedDelay = 1000)
    public void process() {
        var candidates = db.queryForList("""
                SELECT id,reservation_id FROM payment_attempt
                WHERE status IN ('CREATED','SUBMITTED','UNKNOWN') AND next_retry_at<=CURRENT_TIMESTAMP(3)
                AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 20
                """);
        for (var candidate : candidates) {
            long id = ((Number) candidate.get("id")).longValue();
            long reservation = ((Number) candidate.get("reservation_id")).longValue();
            String token = UUID.randomUUID().toString();
            try {
                var attempt = tx.execute(s -> {
                    lock(reservation);
                    int claimed = db.update("""
                            UPDATE payment_attempt SET status='SUBMITTED',lease_token=?,
                            lease_until=?,retry_count=retry_count+1
                            WHERE id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN')
                            AND next_retry_at<=CURRENT_TIMESTAMP(3)
                            AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(3))
                            """, token, Data.ts(Instant.now().plusSeconds(30)), id);
                    if (claimed == 0) return null;
                    db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?", reservation);
                    return db.queryForMap("SELECT * FROM payment_attempt WHERE id=?", id);
                });
                if (attempt != null) complete(attempt, reservation, token);
            } catch (Exception failure) {
                LOG.warn("Payment attempt {} failed ({})", id, failure.getClass().getSimpleName());
                try {
                    tx.executeWithoutResult(s -> {
                        lock(reservation);
                        var rows = db.queryForList("SELECT retry_count FROM payment_attempt WHERE id=? AND lease_token=?", id, token);
                        if (rows.isEmpty()) return;
                        int retries = ((Number) rows.getFirst().get("retry_count")).intValue();
                        db.update("""
                                UPDATE payment_attempt SET status=?,next_retry_at=?,last_error=?,
                                lease_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?
                                """, retries >= 5 ? "REVIEW" : "UNKNOWN",
                                Data.ts(Instant.now().plusSeconds(1L << Math.min(retries, 8))),
                                failure.getClass().getSimpleName(), id, token);
                        db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?", reservation);
                    });
                } catch (Exception recoveryFailure) {
                    // The expired lease permits later recovery if the database is unavailable now.
                    LOG.warn("Could not record failure for payment {} ({})", id, recoveryFailure.getClass().getSimpleName());
                }
            }
        }
    }

    private void complete(Map<String,Object> attempt, long reservation, String token) {
        long id = ((Number) attempt.get("id")).longValue();
        String channel = Data.str(attempt, "channel_no"), kind = Data.str(attempt, "kind");
        long amount = ((Number) attempt.get("amount_fen")).longValue();
        // Durable simulated channel receipt commits independently. Recovery reuses this channel number.
        tx.executeWithoutResult(s -> {
            db.update("INSERT IGNORE INTO channel_operation(channel_no,kind,amount_fen,result) VALUES(?,?,?,'SUCCESS')", channel, kind, amount);
            var receipt = db.queryForMap("SELECT * FROM channel_operation WHERE channel_no=?", channel);
            Problem.require(kind.equals(receipt.get("kind")) && amount == ((Number) receipt.get("amount_fen")).longValue(),
                    409, "CHANNEL_CONFLICT", "模拟渠道请求不一致");
        });
        tx.executeWithoutResult(s -> {
            lock(reservation);
            var current = db.queryForMap("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE", id);
            if (!token.equals(current.get("lease_token")) || Data.str(current, "status").equals("SUCCESS")) return;
            db.update("UPDATE payment_attempt SET status='SUCCESS',last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=?", id);
            db.update("INSERT INTO ledger_entry(reservation_id,channel_no,kind,amount_fen) VALUES(?,?,?,?)", reservation, channel, kind, amount);
            String column = switch (kind) {
                case "REFUND" -> "refund_fen";
                case "PREPAY" -> "paid_prepay_fen";
                case "OVERTIME" -> "paid_overtime_fen";
                default -> throw new IllegalStateException("Unsupported payment kind");
            };
            db.update("UPDATE trade_order SET " + column + "=" + column + "+?,bill_version=bill_version+1 WHERE reservation_id=?", amount, reservation);
            if (!kind.equals("REFUND")) Events.add(db, "parking-service", "PaymentSucceeded", Map.of(
                    "reservationId", Long.toString(reservation), "paymentAttemptId", Long.toString(id), "amountFen", amount, "kind", kind));
            TradeAccounting.refundIfClosed(db, reservation);
        });
    }
}
