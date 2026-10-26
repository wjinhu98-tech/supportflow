package dev.supportflow.assistant.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimePropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRuntimeProperties.class)
    static class PropertiesConfiguration { }

    @Test
    void bindsConservativeDefaults() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(AgentRuntimeProperties.class))
                    .isEqualTo(new AgentRuntimeProperties(8, 90));
        });
    }

    @Test
    void bindsExplicitBoundsAndRejectsMisspelledProperties() {
        context.withPropertyValues("supportflow.agent.runtime.max-steps=3",
                "supportflow.agent.runtime.total-timeout-seconds=30").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(AgentRuntimeProperties.class))
                    .isEqualTo(new AgentRuntimeProperties(3, 30));
        });
        context.withPropertyValues("supportflow.agent.runtime.max-step=3")
                .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 33, Integer.MAX_VALUE})
    void rejectsInvalidStepBudgets(int steps) {
        assertThatThrownBy(() -> new AgentRuntimeProperties(steps, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void rejectsInvalidDeadlines(int seconds) {
        assertThatThrownBy(() -> new AgentRuntimeProperties(8, seconds))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
