package dev.supportflow.operations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OperationsProperties.class)
    static class Binding { }

    @Test
    void defaultsToDisabledWithoutASecret() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OperationsProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void enabledOperationsRequiresEncodedCredentialsAndNeverRendersThem() {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("Synthetic-Operations-Only!");
        runner.withPropertyValues("supportflow.operations.enabled=true", "supportflow.operations.username=ops-test",
                "supportflow.operations.password-hash=" + hash).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OperationsProperties.class).toString().contains(hash)).isFalse();
        });
    }

    @Test
    void rejectsMissingOrRawPasswordsInsteadOfInstallingAnOpenManagementChain() {
        for (String hash : new String[]{"", "plain-text-test-value", "{noop}synthetic"}) {
            runner.withPropertyValues("supportflow.operations.enabled=true", "supportflow.operations.password-hash=" + hash)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsUnknownSettingsSoSecurityTyposCannotPassSilently() {
        runner.withPropertyValues("supportflow.operations.passwrod-hash=synthetic")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void managementConfigurationCannotReplaceDefaultSecurityOutsideSupportedProfiles() {
        new ApplicationContextRunner().withUserConfiguration(OperationsSecurityConfiguration.class)
                .withPropertyValues("spring.profiles.active=production", "supportflow.operations.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(org.springframework.security.web.SecurityFilterChain.class);
                });
    }
}
