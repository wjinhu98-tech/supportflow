package dev.supportflow.common.security.config;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.common.security.local.LocalCustomerAccountsProperties;
import dev.supportflow.common.security.local.LocalCustomerUserDetailsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只解析公开 YAML 模板，不执行 ConfigData import，也不读取私密文件。
 */
class LocalConfigurationTemplateTest {

    @Test
    @DisplayName("公开local模板的密码占位符和身份配置可以真实绑定")
    void shouldBindPublicTemplateWithInMemoryTestSecret() throws IOException {
        String password = UUID.randomUUID().toString();
        String hash = PasswordEncoderFactories
                .createDelegatingPasswordEncoder().encode(password);

        publicTemplate(hash).run(context -> {
            assertThat(context).hasNotFailed();
            var binder = Binder.get(context.getEnvironment());
            var authentication = binder.bind("supportflow.security.authentication", BrowserAuthenticationProperties.class).get();
            assertThat(authentication.mode()).isEqualTo("database");
            assertThat(authentication.basicEnabled()).isFalse();
            assertThat(authentication.sessionTimeout()).isEqualTo(Duration.ofMinutes(30));
            var account = binder.bind("supportflow.security.local", LocalCustomerAccountsProperties.class).get()
                    .accounts().getFirst();
            assertThat(account.username()).isEqualTo("customer-200");
            assertThat(account.passwordHash()).isEqualTo(hash);
            assertThat(account.identity()).isEqualTo(new CustomerIdentity(200L));
            assertThat(account.permissions()).containsExactlyInAnyOrder(SecurityPermission.ORDER_READ,
                    SecurityPermission.ORDER_CONFIRM_RECEIPT);
            assertThat(context).doesNotHaveBean(LocalCustomerUserDetailsService.class);
            assertThat(context).doesNotHaveBean(AuthenticationManager.class);
        });
    }

    @Test
    void shouldAuthenticateTemplateAccountWhenConfiguredModeIsExplicitlySelected() throws IOException {
        String password = UUID.randomUUID().toString();
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password);
        publicTemplate(hash)
                .withPropertyValues("supportflow.security.authentication.mode=configured")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Authentication result = context.getBean(AuthenticationManager.class).authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated("customer-200", password));
                    assertThat(result.isAuthenticated()).isTrue();
                    assertThat(result.getPrincipal()).isInstanceOfSatisfying(CustomerPrincipal.class,
                            principal -> assertThat(principal.getIdentity()).isEqualTo(new CustomerIdentity(200L)));
                });
    }

    private ApplicationContextRunner publicTemplate(String hash) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "public-local-template",
                new ClassPathResource("application-local.yml")
        );

        return new ApplicationContextRunner()
                .withUserConfiguration(LocalAuthenticationConfiguration.class)
                .withInitializer(context -> {
                    context.getEnvironment().setActiveProfiles("local");
                    sources.forEach(source -> context.getEnvironment()
                            .getPropertySources().addLast(source));
                })
                .withPropertyValues(
                        "SUPPORTFLOW_CUSTOMER_200_PASSWORD_HASH=" + hash
                );
    }
}
