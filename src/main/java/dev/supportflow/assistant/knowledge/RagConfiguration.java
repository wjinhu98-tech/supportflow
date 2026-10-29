package dev.supportflow.assistant.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {
    @Bean
    @ConditionalOnProperty(name = {"supportflow.rag.enabled", "supportflow.rag.public-evidence-selection-enabled",
            "supportflow.ai.deepseek.enabled"}, havingValue = "true")
    PublicKnowledgeSelector deepSeekPublicKnowledgeSelector(
            dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor executor,
            tools.jackson.databind.ObjectMapper json,
            dev.supportflow.chat.infrastructure.deepseek.DeepSeekProperties properties) {
        return new dev.supportflow.chat.infrastructure.deepseek.DeepSeekPublicKnowledgeSelector(executor, json, properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "supportflow.rag", name = "enabled", havingValue = "true")
    KnowledgeEmbedding localKnowledgeEmbedding(RagProperties properties) {
        return new OnnxKnowledgeEmbedding(Path.of(properties.modelDirectory()));
    }

    @Bean
    RagKnowledgeSearch ragKnowledgeSearch(RagProperties properties, ObjectProvider<KnowledgeEmbedding> embeddings,
                                          MeterRegistry meters) {
        return new RagKnowledgeSearch(properties, embeddings.getIfAvailable(), meters);
    }
}
