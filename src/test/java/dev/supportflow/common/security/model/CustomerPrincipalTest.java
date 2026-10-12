package dev.supportflow.common.security.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerPrincipalTest {

    @Test
    @DisplayName("清理密码凭据后仍保留账号、客户身份及操作权限")
    void shouldKeepIdentityAndPermissionsAfterErasingCredentials() {
        PasswordEncoder passwordEncoder =
                PasswordEncoderFactories.createDelegatingPasswordEncoder();

        String passwordHash = passwordEncoder.encode(
                UUID.randomUUID().toString()
        );

        CustomerIdentity identity = new CustomerIdentity(
                100L,
                200L
        );

        CustomerPrincipal principal = new CustomerPrincipal(
                "customer-200",
                passwordHash,
                identity,
                Set.of(SecurityPermission.ORDER_READ)
        );

        assertThat(principal.getPassword()).isEqualTo(passwordHash);

        principal.eraseCredentials();

        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getUsername()).isEqualTo("customer-200");
        assertThat(principal.getIdentity()).isEqualTo(identity);
        assertThat(principal.getAuthorities())
                .containsExactly(SecurityPermission.ORDER_READ);
    }
}