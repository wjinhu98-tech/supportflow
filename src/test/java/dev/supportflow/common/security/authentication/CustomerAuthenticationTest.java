package dev.supportflow.common.security.authentication;

import dev.supportflow.common.security.local.LocalCustomerAccount;
import dev.supportflow.common.security.local.LocalCustomerUserDetailsService;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerAuthenticationTest {

    private String rawPassword;

    private CustomerIdentity identity;

    private AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        rawPassword = UUID.randomUUID().toString();

        PasswordEncoder passwordEncoder =
                PasswordEncoderFactories.createDelegatingPasswordEncoder();

        String passwordHash = passwordEncoder.encode(rawPassword);

        identity = new CustomerIdentity(200L);

        LocalCustomerAccount account = new LocalCustomerAccount(
                "customer-200",
                passwordHash,
                identity,
                Set.of(SecurityPermission.ORDER_READ)
        );

        LocalCustomerUserDetailsService userDetailsService =
                new LocalCustomerUserDetailsService(List.of(account));

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);

        authenticationManager = new ProviderManager(provider);
    }

    @Test
    @DisplayName("正确密码可以连续认证并取得客户身份，结果中的凭据被清理")
    void shouldAuthenticateRepeatedlyAndKeepCustomerIdentity() {
        Authentication firstResult = authenticate(
                "customer-200",
                rawPassword
        );

        Authentication secondResult = authenticate(
                "customer-200",
                rawPassword
        );

        assertThat(firstResult.isAuthenticated()).isTrue();
        assertThat(secondResult.isAuthenticated()).isTrue();

        assertThat(firstResult.getPrincipal())
                .isInstanceOf(CustomerPrincipal.class);
        assertThat(secondResult.getPrincipal())
                .isInstanceOf(CustomerPrincipal.class);

        CustomerPrincipal firstPrincipal =
                (CustomerPrincipal) firstResult.getPrincipal();

        CustomerPrincipal secondPrincipal =
                (CustomerPrincipal) secondResult.getPrincipal();

        assertThat(secondPrincipal).isNotSameAs(firstPrincipal);

        assertThat(firstPrincipal.getIdentity()).isEqualTo(identity);
        assertThat(secondPrincipal.getIdentity()).isEqualTo(identity);

        assertThat(firstResult.getCredentials()).isNull();
        assertThat(secondResult.getCredentials()).isNull();
        assertThat(firstPrincipal.getPassword()).isNull();
        assertThat(secondPrincipal.getPassword()).isNull();

        assertThat(secondResult.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(SecurityPermission.ORDER_READ.getAuthority());
    }

    @Test
    @DisplayName("账号存在但密码错误时认证失败")
    void shouldRejectWrongPassword() {
        assertThatThrownBy(
                () -> authenticate(
                        "customer-200",
                        rawPassword + "-wrong"
                )
        ).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("账号不存在时同样返回凭据错误")
    void shouldRejectUnknownUsername() {
        assertThatThrownBy(
                () -> authenticate(
                        "unknown-customer",
                        rawPassword
                )
        ).isInstanceOf(BadCredentialsException.class);
    }

    private Authentication authenticate(
            String username,
            String password
    ) {
        UsernamePasswordAuthenticationToken request =
                UsernamePasswordAuthenticationToken.unauthenticated(
                        username,
                        password
                );

        return authenticationManager.authenticate(request);
    }
}
