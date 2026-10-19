package dev.supportflow.common.security.config;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.io.IOException;
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

        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "public-local-template",
                new ClassPathResource("application-local.yml")
        );

        new ApplicationContextRunner()
                .withUserConfiguration(LocalAuthenticationConfiguration.class)
                .withInitializer(context -> {
                    context.getEnvironment().setActiveProfiles("local");
                    sources.forEach(source -> context.getEnvironment()
                            .getPropertySources().addLast(source));
                })
                .withPropertyValues(
                        "SUPPORTFLOW_CUSTOMER_200_PASSWORD_HASH=" + hash
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    Authentication result = context
                            .getBean(AuthenticationManager.class)
                            .authenticate(
                                    UsernamePasswordAuthenticationToken
                                            .unauthenticated(
                                                    "customer-200",
                                                    password
                                            )
                            );

                    assertThat(result.isAuthenticated()).isTrue();
                    assertThat(result.getPrincipal())
                            .isInstanceOfSatisfying(
                                    CustomerPrincipal.class,
                                    principal -> assertThat(
                                            principal.getIdentity()
                                    ).isEqualTo(
                                            new CustomerIdentity(200L)
                                    )
                            );
                });
    }
}
