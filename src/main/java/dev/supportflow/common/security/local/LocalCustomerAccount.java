package dev.supportflow.common.security.local;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.SecurityPermission;

import java.util.Objects;
import java.util.Set;

/**
 * 本地开发账号的配置快照。
 */
public record LocalCustomerAccount(
        String username,
        String passwordHash,
        CustomerIdentity identity,
        Set<SecurityPermission> permissions
) {

    public LocalCustomerAccount {
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
                + "]";
    }
}