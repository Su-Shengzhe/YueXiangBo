package cn.community.trade;

import cn.community.foundation.*;
import java.time.Clock;
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
    private final SimulatedChannel channelAdapter;
    private final PaymentPosting posting;
    private final Clock clock;
    private final int queryBudget;
    private final long leaseSeconds, backoffSeconds, maxBackoffSeconds;

    public PaymentWorker(JdbcTemplate db, PlatformTransactionManager manager) {
        this(db, manager, Clock.systemUTC(), 5, 30, 5, 60);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentWorker(JdbcTemplate db, PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Value("${business.payment.query-budget:5}") int queryBudget,
            @org.springframework.beans.factory.annotation.Value("${business.payment.lease-seconds:30}") long leaseSeconds,
            @org.springframework.beans.factory.annotation.Value("${business.payment.backoff-seconds:5}") long backoffSeconds,
            @org.springframework.beans.factory.annotation.Value("${business.payment.max-backoff-seconds:60}") long maxBackoffSeconds,
            @org.springframework.beans.factory.annotation.Value("${business.demo:false}") boolean demo,
            @org.springframework.beans.factory.annotation.Value("${business.channel.result:SUCCESS}") String result,
            @org.springframework.beans.factory.annotation.Value("${business.channel.delay-millis:0}") long delay,
            @org.springframework.beans.factory.annotation.Value("${business.channel.lose-submit-response:false}") boolean loseResponse) {
        this(db, manager, Clock.systemUTC(), queryBudget, leaseSeconds, backoffSeconds, maxBackoffSeconds);
        channelAdapter.configure(demo, result, delay, loseResponse);
    }

    PaymentWorker(JdbcTemplate db, PlatformTransactionManager manager, Clock clock,
            int queryBudget, long leaseSeconds, long backoffSeconds, long maxBackoffSeconds) {
        if (queryBudget < 1 || queryBudget > 100 || leaseSeconds < 1 || leaseSeconds > 3600
                || backoffSeconds < 1 || maxBackoffSeconds < backoffSeconds || maxBackoffSeconds > 86400)
            throw new IllegalArgumentException("Invalid payment recovery configuration");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.queryBudget = queryBudget;
        this.leaseSeconds = leaseSeconds;
        this.backoffSeconds = backoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.channelAdapter = new SimulatedChannel(db, manager);
        this.posting = new PaymentPosting(db, manager);
    }

    private void lock(long reservation) {
        db.queryForMap("SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE", reservation);
    }

    @Scheduled(fixedDelayString = "${business.payment.poll-millis:1000}")
    public void process() {
        var candidates = db.queryForList("""
                SELECT id,reservation_id FROM payment_attempt
                WHERE status IN ('CREATED','SUBMITTED','UNKNOWN') AND next_retry_at<=?
                AND (lease_until IS NULL OR lease_until<?) ORDER BY id LIMIT 20
                """, Data.ts(clock.instant()), Data.ts(clock.instant()));
        for (var candidate : candidates) {
            long id = ((Number) candidate.get("id")).longValue();
            long reservation = ((Number) candidate.get("reservation_id")).longValue();
            String token = UUID.randomUUID().toString();
            try {
                var attempt = tx.execute(s -> {
                    lock(reservation);
                    RefundLedger.mapHistory(db,reservation);
                    int exhausted = db.update("""
                            UPDATE payment_attempt SET status='REVIEW',lease_token=NULL,lease_until=NULL,last_error='RECOVERY_LIMIT'
                            WHERE id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN') AND query_count>=?
                            AND (lease_until IS NULL OR lease_until<?)
                            """, id, queryBudget, Data.ts(clock.instant()));
                    if (exhausted > 0) {
                        db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?", reservation);
                        return null;
                    }
                    int claimed = db.update("""
                            UPDATE payment_attempt SET status='SUBMITTED',lease_token=?,
                            lease_until=?,query_count=query_count+CASE WHEN retry_count>0 THEN 1 ELSE 0 END,retry_count=retry_count+1
                            WHERE id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN')
                            AND query_count<?
                            AND next_retry_at<=?
                            AND (lease_until IS NULL OR lease_until<?)
                            """, token, Data.ts(clock.instant().plusSeconds(leaseSeconds)), id,
                            queryBudget, Data.ts(clock.instant()), Data.ts(clock.instant()));
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
                        var rows = db.queryForList("SELECT query_count FROM payment_attempt WHERE id=? AND lease_token=?", id, token);
                        if (rows.isEmpty()) {
                            // A mapping failure precedes lease acquisition. Isolate only an unleased,
                            // still eligible task; never overwrite another worker's live lease.
                            if(failure instanceof Problem problem && "REFUND_HISTORY_CONFLICT".equals(problem.code)) {
                                int isolated=db.update("UPDATE payment_attempt SET status='REVIEW',last_error='REFUND_HISTORY_CONFLICT',lease_token=NULL,lease_until=NULL WHERE id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN') AND (lease_until IS NULL OR lease_until<?)",id,Data.ts(clock.instant()));
                                if(isolated>0) db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?",reservation);
                            } else {
                                db.update("UPDATE payment_attempt SET next_retry_at=?,last_error=? WHERE id=? AND status IN ('CREATED','SUBMITTED','UNKNOWN') AND (lease_until IS NULL OR lease_until<?)",Data.ts(clock.instant().plusSeconds(maxBackoffSeconds)),failure.getClass().getSimpleName(),id,Data.ts(clock.instant()));
                            }
                            return;
                        }
                        int retries = ((Number) rows.getFirst().get("query_count")).intValue();
                        db.update("""
                                UPDATE payment_attempt SET status=?,next_retry_at=?,last_error=?,
                                lease_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?
                                """, retries >= queryBudget ? "REVIEW" : "UNKNOWN",
                                Data.ts(clock.instant().plusSeconds(Math.min(maxBackoffSeconds,
                                        backoffSeconds * (1L << Math.min(retries, 17))))),
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
        String outcome = ((Number) attempt.get("retry_count")).intValue() == 1
                ? channelAdapter.submit(channel, kind, amount)
                : channelAdapter.query(channel, kind, amount);
        // An existing durable channel operation may still be unknown or have failed.
        // Never infer success merely from the presence of its business number.
        Problem.require("SUCCESS".equals(outcome) || "FAILED".equals(outcome),
                409, "CHANNEL_RESULT_UNKNOWN", "模拟渠道结果尚未确定");
        posting.post(new PaymentPosting.Result(id, reservation, token, channel, kind, amount, outcome));
    }
}
