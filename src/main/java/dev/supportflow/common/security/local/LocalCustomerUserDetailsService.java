package dev.supportflow.common.security.local;

import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 从本地开发账号配置中加载客户账号。
 */
public final class LocalCustomerUserDetailsService
        implements UserDetailsService {

    private final Map<String, LocalCustomerAccount> accountsByUsername;

    public LocalCustomerUserDetailsService(
            Collection<LocalCustomerAccount> accounts
    ) {
        Objects.requireNonNull(
                accounts,
                "accounts must not be null"
        );

        if (accounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one local customer account is required"
            );
        }

        Map<String, LocalCustomerAccount> accountMap = new HashMap<>();

        for (LocalCustomerAccount account : accounts) {
            Objects.requireNonNull(
                    account,
                    "account must not be null"
            );

            LocalCustomerAccount previous = accountMap.putIfAbsent(
                    account.username(),
                    account
            );

            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate local account username: "
                                + account.username()
                );
            }
        }

        this.accountsByUsername = Map.copyOf(accountMap);
    }

    @Override
    public CustomerPrincipal loadUserByUsername(String username) {
        LocalCustomerAccount account = accountsByUsername.get(username);

        if (account == null) {
            throw new UsernameNotFoundException(
                    "账号或密码错误"
            );
        }

        return new CustomerPrincipal(
                account.username(),
                account.passwordHash(),
                account.identity(),
                account.permissions(),
                account.accountType()
        );
    }
}
