package dev.supportflow.common.security.config;

import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;
import dev.supportflow.common.security.local.LocalCustomerUserDetailsService;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthenticationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalAuthenticationConfiguration.class);

    @Test
    @DisplayName("local 配置能够绑定账号并完成真实密码认证")
    void shouldBindLocalAccountAndAuthenticate() {
        String rawPassword = UUID.randomUUID().toString();
        String passwordHash = PasswordEncoderFactories
                .createDelegatingPasswordEncoder()
                .encode(rawPassword);

        withLocalAccount(passwordHash).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LocalCustomerAccountsProperties.class);
            assertThat(context).hasSingleBean(PasswordEncoder.class);
            assertThat(context).hasSingleBean(LocalCustomerUserDetailsService.class);
            assertThat(context).hasSingleBean(DaoAuthenticationProvider.class);
            assertThat(context).hasSingleBean(AuthenticationManager.class);

            AuthenticationManager manager = context.getBean(AuthenticationManager.class);
            Authentication authentication = manager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            "customer-200",
                            rawPassword
                    )
            );

            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getPrincipal()).isInstanceOf(CustomerPrincipal.class);

            CustomerPrincipal principal =
                    (CustomerPrincipal) authentication.getPrincipal();
            assertThat(principal.getUsername()).isEqualTo("customer-200");
            assertThat(principal.getIdentity())
                    .isEqualTo(new CustomerIdentity(100L, 200L));
            assertThat(authentication.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains(SecurityPermission.ORDER_READ.getAuthority());
            assertThat(authentication.getCredentials()).isNull();
            assertThat(principal.getPassword()).isNull();
        });
    }

    @Test
    @DisplayName("未激活 local 时不注册本地开发账号认证组件")
    void shouldNotRegisterLocalAuthenticationOutsideLocalProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LocalCustomerAccountsProperties.class);
            assertThat(context).doesNotHaveBean(PasswordEncoder.class);
            assertThat(context).doesNotHaveBean(LocalCustomerUserDetailsService.class);
            assertThat(context).doesNotHaveBean(DaoAuthenticationProvider.class);
            assertThat(context).doesNotHaveBean(AuthenticationManager.class);
        });
    }

    @Test
    @DisplayName("local 环境缺少账号配置时拒绝启动认证组件")
    void shouldFailWhenLocalAccountsAreMissing() {
        contextRunner
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles("local")
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName("local 配置不接受使用 noop 保存的明文密码")
    void shouldRejectNoopPasswordConfiguration() {
        withLocalAccount("{noop}" + UUID.randomUUID())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    private ApplicationContextRunner withLocalAccount(String passwordHash) {
        return contextRunner
                .withInitializer(context ->
                        context.getEnvironment().setActiveProfiles("local")
                )
                .withPropertyValues(
                        "supportflow.security.local.accounts[0].username=customer-200",
                        "supportflow.security.local.accounts[0].password-hash=" + passwordHash,
                        "supportflow.security.local.accounts[0].identity.tenant-id=100",
                        "supportflow.security.local.accounts[0].identity.customer-id=200",
                        "supportflow.security.local.accounts[0].permissions[0]=ORDER_READ"
                );
    }
}
