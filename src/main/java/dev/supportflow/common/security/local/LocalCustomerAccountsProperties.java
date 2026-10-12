package dev.supportflow.common.security.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 本地开发客户账号配置，仅保存密码哈希和服务端身份映射。
 */
@ConfigurationProperties(
        prefix = "supportflow.security.local",
        ignoreUnknownFields = false
)
public record LocalCustomerAccountsProperties(
        List<LocalCustomerAccount> accounts
) {

    private static final Pattern BCRYPT_HASH_PATTERN = Pattern.compile(
            "\\{bcrypt}\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"
    );

    public LocalCustomerAccountsProperties {
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one local customer account is required"
            );
        }

        accounts = List.copyOf(accounts);

        for (LocalCustomerAccount account : accounts) {
            if (!BCRYPT_HASH_PATTERN.matcher(account.passwordHash()).matches()) {
                throw new IllegalArgumentException(
                        "Local account passwordHash must be a {bcrypt} encoded BCrypt hash"
                );
            }
        }
    }

    @Override
    public String toString() {
        return "LocalCustomerAccountsProperties[accountCount="
                + accounts.size() + "]";
    }
}
