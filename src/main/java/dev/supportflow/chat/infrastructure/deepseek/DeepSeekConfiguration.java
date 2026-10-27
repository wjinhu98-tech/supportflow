package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/** 开关关闭时保留普通聊天，开启后才装配 DeepSeek 网络适配器。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "true")
    dev.supportflow.assistant.runtime.planning.AgentDecisionPlanner deepSeekAgentDecisionPlanner(
            DeepSeekCallExecutor executor, ObjectMapper mapper, DeepSeekProperties properties) {
        return new DeepSeekAgentDecisionPlanner(executor, mapper, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "false", matchIfMissing = true)
    dev.supportflow.assistant.runtime.planning.AgentDecisionPlanner localAgentDecisionPlanner() {
        return new dev.supportflow.assistant.runtime.planning.RuleBasedAgentDecisionPlanner();
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "true")
    dev.supportflow.assistant.planning.ToolPlanner deepSeekToolPlanner(
            DeepSeekCallExecutor executor,
            ObjectMapper mapper, DeepSeekProperties properties) {
        return new DeepSeekToolPlanner(executor, mapper, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "false", matchIfMissing = true)
    dev.supportflow.assistant.planning.ToolPlanner localToolPlanner() {
        return new dev.supportflow.assistant.planning.RuleBasedToolPlanner();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "true")
    HttpClient deepSeekHttpClient(DeepSeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "true")
    DeepSeekCallExecutor deepSeekCallExecutor(
            @Qualifier("deepSeekHttpClient") HttpClient client, DeepSeekProperties properties,
            ObjectProvider<MeterRegistry> registries) {
        return new DeepSeekCallExecutor(client, properties, registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnProperty(prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "true")
    OrderIntentRecognizer deepSeekOrderIntentRecognizer(
            DeepSeekCallExecutor executor,
            DeepSeekProperties properties,
            ObjectMapper objectMapper
    ) {
        return new DeepSeekOrderIntentRecognizer(executor, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "false", matchIfMissing = true
    )
    OrderIntentRecognizer disabledOrderIntentRecognizer() {
        return message -> OrderIntent.other();
    }
}
