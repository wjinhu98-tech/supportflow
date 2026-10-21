package dev.supportflow.chat.infrastructure.deepseek;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
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
    dev.supportflow.assistant.planning.ToolPlanner deepSeekToolPlanner(
            @Qualifier("deepSeekHttpClient") HttpClient client,
            ObjectMapper mapper, DeepSeekProperties properties) {
        return new DeepSeekToolPlanner(client, mapper, properties);
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
    OrderIntentRecognizer deepSeekOrderIntentRecognizer(
            @Qualifier("deepSeekHttpClient") HttpClient httpClient,
            DeepSeekProperties properties,
            ObjectMapper objectMapper
    ) {
        return new DeepSeekOrderIntentRecognizer(httpClient, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "supportflow.ai.deepseek", name = "enabled", havingValue = "false", matchIfMissing = true
    )
    OrderIntentRecognizer disabledOrderIntentRecognizer() {
        return message -> OrderIntent.other();
    }
}
