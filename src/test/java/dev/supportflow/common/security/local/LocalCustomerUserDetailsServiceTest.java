package dev.supportflow.common.security.local;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCustomerUserDetailsServiceTest {

    private String passwordHash;

    private CustomerIdentity identity;

    private LocalCustomerUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        passwordHash = PasswordEncoderFactories
                .createDelegatingPasswordEncoder()
                .encode(UUID.randomUUID().toString());

        identity = new CustomerIdentity(200L);

        LocalCustomerAccount account = new LocalCustomerAccount(
                "customer-200",
                passwordHash,
                identity,
                Set.of(SecurityPermission.ORDER_READ)
        );

        userDetailsService = new LocalCustomerUserDetailsService(
                List.of(account)
        );
    }

    @Test
    @DisplayName("清理一次加载的密码后再次加载仍能取得完整账号")
    void shouldLoadFreshPrincipalAfterCredentialsWereErased() {
        CustomerPrincipal firstPrincipal =
                userDetailsService.loadUserByUsername("customer-200");

        firstPrincipal.eraseCredentials();

        CustomerPrincipal secondPrincipal =
                userDetailsService.loadUserByUsername("customer-200");

        assertThat(firstPrincipal.getPassword()).isNull();

        assertThat(secondPrincipal).isNotSameAs(firstPrincipal);
        assertThat(secondPrincipal.getPassword()).isEqualTo(passwordHash);
        assertThat(secondPrincipal.getIdentity()).isEqualTo(identity);
        assertThat(secondPrincipal.getAuthorities())
                .containsExactly(SecurityPermission.ORDER_READ);
    }

    @Test
    @DisplayName("未知账号不能取得账号信息")
    void shouldRejectUnknownUsername() {
        assertThatThrownBy(
                () -> userDetailsService.loadUserByUsername(
                        "unknown-customer"
                )
        ).isInstanceOf(UsernameNotFoundException.class);
    }
}
