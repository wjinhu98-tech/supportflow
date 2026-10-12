package dev.supportflow.testsupport;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

/**
 * 为本地开发生成独立凭据，输出供本地初始化使用，不包含固定密码。
 */
public final class LocalCredentialsTool {

    private LocalCredentialsTool() {
    }

    public static void main(String[] args) {
        String password = UUID.randomUUID().toString();
        String hash = "{bcrypt}"
                + new BCryptPasswordEncoder().encode(password);
        System.out.printf(
                "{\"password\":\"%s\",\"passwordHash\":\"%s\"}%n",
                password,
                hash
        );
    }
}
