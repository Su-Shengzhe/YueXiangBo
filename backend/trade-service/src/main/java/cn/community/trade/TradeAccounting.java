package cn.community.trade;

import cn.community.foundation.Data;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Call inside a transaction after locking the trade_order aggregate. */
final class TradeAccounting {
    static void refundIfClosed(JdbcTemplate db, long id) {
        var bill = db.queryForMap("SELECT * FROM trade_order WHERE reservation_id=?", id);
        long amount = ((Number) bill.get("paid_prepay_fen")).longValue()
                - ((Number) bill.get("refund_fen")).longValue();
        boolean closed = List.of("CANCELLED", "EXPIRED", "NO_SHOW", "SPACE_UNAVAILABLE")
                .contains(Data.str(bill, "status"));
        if (closed && amount > 0 && db.queryForObject("""
                SELECT COUNT(*) FROM payment_attempt WHERE reservation_id=? AND kind='REFUND'
                AND status IN ('CREATED','SUBMITTED','UNKNOWN','REVIEW')
                """, Integer.class, id) == 0) {
            db.update("""
                    INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no)
                    VALUES(?,?,?,'REFUND',?,'CREATED',?)
                    """, Data.id(db), id, bill.get("user_id"), amount, "REFUND-" + id);
            db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?", id);
        }
    }
}
