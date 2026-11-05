package cn.community.identity;

import cn.community.foundation.Actor;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class AccountPermissions {
    static final Set<String> FINANCE = Set.of("REFUND_READ", "REFUND_APPROVE", "FUNDS_RECOVER", "SETTLEMENT_EXECUTE");
    static Set<String> resolve(JdbcTemplate db, Object id, Set<String> roles) {
        var effective = Actor.permissions(roles);
        if (roles.contains("PLATFORM")) {
            for (var row : db.queryForList("SELECT permission_code,granted FROM account_permission WHERE user_id=?", id)) {
                String code = row.get("permission_code").toString();
                if (!FINANCE.contains(code) && !"ACCOUNT_AUTHORIZE".equals(code)) continue;
                if (Boolean.TRUE.equals(row.get("granted"))) effective.add(code); else effective.remove(code);
            }
        }
        return Set.copyOf(effective);
    }
}
