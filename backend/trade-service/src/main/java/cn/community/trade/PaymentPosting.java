package cn.community.trade;

import cn.community.foundation.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Posts a definitive channel result under the order lock and fenced attempt lease. */
final class PaymentPosting {
    record Result(long attemptId, long reservationId, String leaseToken, String channelNumber,
                  String kind, long amountFen, String outcome) {
        Result {
            if (attemptId <= 0 || reservationId <= 0 || amountFen <= 0
                    || !java.util.Set.of("SUCCESS", "FAILED").contains(outcome)
                    || !java.util.Set.of("PREPAY", "OVERTIME", "REFUND").contains(kind)
                    || leaseToken == null || leaseToken.isBlank() || channelNumber == null || channelNumber.isBlank())
                throw new IllegalArgumentException("Invalid definitive payment result");
        }
    }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    PaymentPosting(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; this.tx = new TransactionTemplate(manager);
    }
    private void lock(long reservation) {
        db.queryForMap("SELECT reservation_id FROM trade_order WHERE reservation_id=? FOR UPDATE", reservation);
    }
    void post(Result result) {
        long id = result.attemptId(), reservation = result.reservationId(), amount = result.amountFen();
        String token = result.leaseToken(), channel = result.channelNumber(), kind = result.kind(), outcome = result.outcome();
        tx.executeWithoutResult(s -> {
            lock(reservation);
            var current = db.queryForMap("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE", id);
            if (!token.equals(current.get("lease_token")) || Data.str(current, "status").equals("SUCCESS")) return;
            if ("FAILED".equals(outcome)) {
                db.update("UPDATE payment_attempt SET status='FAILED',last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=?", id);
                updateApplication(current);
                db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?", reservation);
                return;
            }
            db.update("UPDATE payment_attempt SET status='SUCCESS',last_error=NULL,lease_token=NULL,lease_until=NULL WHERE id=?", id);
            updateApplication(current);
            db.update("INSERT INTO ledger_entry(reservation_id,channel_no,kind,amount_fen) VALUES(?,?,?,?)", reservation, channel, kind, amount);
            String column = switch (kind) {
                case "REFUND" -> "refund_fen";
                case "PREPAY" -> "paid_prepay_fen";
                case "OVERTIME" -> "paid_overtime_fen";
                default -> throw new IllegalStateException("Unsupported payment kind");
            };
            db.update("UPDATE trade_order SET " + column + "=" + column + "+?,bill_version=bill_version+1 WHERE reservation_id=?", amount, reservation);
            if(kind.equals("REFUND")) Settlements.refundPosted(db,reservation,id);
            if (!kind.equals("REFUND")) Events.add(db, "parking-service", "PaymentSucceeded", Map.of(
                    "reservationId", Long.toString(reservation), "paymentAttemptId", Long.toString(id), "amountFen", amount, "kind", kind));
            TradeAccounting.refundIfClosed(db, reservation);
        });
    }
    private void updateApplication(Map<String,Object> attempt){
        Object application=attempt.get("application_id");if(application==null)return;
        RefundLedger.refresh(db,application);
    }
}
