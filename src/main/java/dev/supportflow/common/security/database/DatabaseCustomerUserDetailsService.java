package dev.supportflow.common.security.database;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.EnumSet;

/** Account lookup never joins merchant grants: existing shop authorization remains authoritative. */
public final class DatabaseCustomerUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public DatabaseCustomerUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CustomerPrincipal loadUserByUsername(String username) {
        return jdbc.query("SELECT * FROM support_security_account WHERE username = ?", (row, index) -> {
            var permissions = EnumSet.noneOf(SecurityPermission.class);
            if (row.getBoolean("order_read")) permissions.add(SecurityPermission.ORDER_READ);
            if (row.getBoolean("order_confirm_receipt")) permissions.add(SecurityPermission.ORDER_CONFIRM_RECEIPT);
            if (row.getBoolean("after_sale_review")) permissions.add(SecurityPermission.AFTER_SALE_REVIEW);
            if (row.getBoolean("knowledge_manage")) permissions.add(SecurityPermission.KNOWLEDGE_MANAGE);
            if (row.getBoolean("chat_support")) permissions.add(SecurityPermission.CHAT_SUPPORT);
            return new CustomerPrincipal(row.getString("username"), row.getString("password_hash"),
                    new CustomerIdentity(row.getLong("principal_id")), permissions,
                    AccountType.valueOf(row.getString("account_type")), row.getLong("security_version"),
                    row.getBoolean("enabled"));
        }, username).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }

    public boolean isCurrent(CustomerPrincipal principal) {
        if (principal.getSecurityVersion() == null) return false;
        return jdbc.query("""
                SELECT enabled, security_version FROM support_security_account WHERE username = ?
                """, (row, index) -> row.getBoolean("enabled")
                && row.getLong("security_version") == principal.getSecurityVersion(), principal.getUsername())
                .stream().findFirst().orElse(false);
    }
}
