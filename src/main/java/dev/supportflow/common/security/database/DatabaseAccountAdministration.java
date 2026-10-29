package dev.supportflow.common.security.database;

import dev.supportflow.common.security.local.LocalCustomerAccount;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/**
 * Explicit operator utility, deliberately NOT a Spring bean or HTTP API.
 * Construct only with the separately authorized operator/migrator connection.
 * The application's SELECT-only connection cannot provision, enable or change accounts.
 */
public final class DatabaseAccountAdministration {
    private final JdbcTemplate operatorJdbc;

    public DatabaseAccountAdministration(JdbcTemplate operatorJdbc) {
        this.operatorJdbc = Objects.requireNonNull(operatorJdbc);
    }

    /** Copies existing bcrypt hashes and identity/permissions without resetting existing database accounts. */
    public int importMissingConfiguredAccounts(List<LocalCustomerAccount> accounts) {
        int inserted = 0;
        for (var account : List.copyOf(accounts)) {
            if (!account.passwordHash().matches("\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) {
                throw new IllegalArgumentException("Only bcrypt password hashes can be imported");
            }
            inserted += operatorJdbc.update("""
                    INSERT INTO support_security_account
                    (username, password_hash, principal_id, account_type, order_read, order_confirm_receipt,
                     after_sale_review, knowledge_manage)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM support_security_account WHERE username = ?)
                    """, account.username(), account.passwordHash(), account.identity().buyerId(), account.accountType().name(),
                    account.permissions().contains(SecurityPermission.ORDER_READ),
                    account.permissions().contains(SecurityPermission.ORDER_CONFIRM_RECEIPT),
                    account.permissions().contains(SecurityPermission.AFTER_SALE_REVIEW),
                    account.permissions().contains(SecurityPermission.KNOWLEDGE_MANAGE), account.username());
        }
        return inserted;
    }

    public boolean disable(String username) {
        return setEnabled(username, false);
    }

    public boolean enable(String username) {
        return setEnabled(username, true);
    }

    private boolean setEnabled(String username, boolean enabled) {
        return operatorJdbc.update("""
                UPDATE support_security_account SET enabled = ?, security_version = security_version + 1
                WHERE username = ?
                """, enabled, username) == 1;
    }

    public boolean invalidateSessions(String username) {
        return operatorJdbc.update("""
                UPDATE support_security_account SET security_version = security_version + 1 WHERE username = ?
                """, username) == 1;
    }

    public boolean changePassword(String username, CharSequence password, PasswordEncoder encoder) {
        if (password == null || password.length() < 12
                || password.toString().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password length must be between 12 and 72 characters");
        }
        String hash = encoder.encode(password);
        if (!hash.startsWith("{bcrypt}")) throw new IllegalArgumentException("Bcrypt password encoder is required");
        return operatorJdbc.update("""
                UPDATE support_security_account SET password_hash = ?, security_version = security_version + 1
                WHERE username = ?
                """, hash, username) == 1;
    }
}
