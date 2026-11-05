package cn.community.trade;
import org.springframework.jdbc.core.JdbcTemplate;
final class TradeAccounting { static void refundIfClosed(JdbcTemplate db,long id){ RefundLedger.automatic(db,id); } }
