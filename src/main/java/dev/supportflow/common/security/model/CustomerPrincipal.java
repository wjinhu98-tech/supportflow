package dev.supportflow.common.security.model;

import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Spring Security 使用的客户账号信息。
 *
 * <p>认证成功后，业务代码从该对象取得身份范围。</p>
 */
public final class CustomerPrincipal extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CustomerIdentity identity;
    private final AccountType accountType;

    public CustomerPrincipal(
            String username,
            String passwordHash,
            CustomerIdentity identity,
            Collection<SecurityPermission> permissions
    ) {
        this(username, passwordHash, identity, permissions, AccountType.BUYER);
    }

    public CustomerPrincipal(
            String username,
            String passwordHash,
            CustomerIdentity identity,
            Collection<SecurityPermission> permissions,
            AccountType accountType
    ) {
        super(
                requireText(username, "username"),
                requireText(passwordHash, "passwordHash"),
                List.copyOf(permissions)
        );

        this.identity = Objects.requireNonNull(
                identity,
                "identity must not be null"
        );
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
    }

    public CustomerIdentity getIdentity() {
        return identity;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    private static String requireText(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        return value;
    }
}
