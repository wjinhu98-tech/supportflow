package dev.supportflow.common.security.local;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Objects;
import java.util.Set;

/**
 * 本地开发账号的配置快照。
 */
public record LocalCustomerAccount(
        String username,
        String passwordHash,
        CustomerIdentity identity,
        Set<SecurityPermission> permissions,
        AccountType accountType
) {

    public LocalCustomerAccount(String username, String passwordHash, CustomerIdentity identity,
                                Set<SecurityPermission> permissions) {
        this(username, passwordHash, identity, permissions, AccountType.BUYER);
    }

    @ConstructorBinding
    public LocalCustomerAccount {
        accountType = accountType == null ? AccountType.BUYER : accountType;
        Objects.requireNonNull(
                username,
                "username must not be null"
        );
        Objects.requireNonNull(
                passwordHash,
                "passwordHash must not be null"
        );
        Objects.requireNonNull(
                identity,
                "identity must not be null"
        );
        Objects.requireNonNull(
                permissions,
                "permissions must not be null"
        );

        if (username.isBlank()) {
            throw new IllegalArgumentException(
                    "username must not be blank"
            );
        }

        if (passwordHash.isBlank()) {
            throw new IllegalArgumentException(
                    "passwordHash must not be blank"
            );
        }

        permissions = Set.copyOf(permissions);
    }

    @Override
    public String toString() {
        return "LocalCustomerAccount[username=" + username
                + ", passwordHash=[PROTECTED]"
                + ", identity=" + identity
                + ", permissions=" + permissions
                + ", accountType=" + accountType
                + "]";
    }
}
