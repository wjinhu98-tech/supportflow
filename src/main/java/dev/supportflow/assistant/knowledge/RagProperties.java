package dev.supportflow.assistant.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "supportflow.rag", ignoreUnknownFields = false)
public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue(".local/models/bge-small-zh-v1.5") String modelDirectory,
        @DefaultValue("0.55") double minimumSimilarity,
        @DefaultValue("0.025") double minimumMargin,
        @DefaultValue("4096") int cacheEntries,
        @DefaultValue("8000") int buildDeadlineMillis,
        @DefaultValue("false") boolean publicEvidenceSelectionEnabled) {
    public RagProperties(boolean enabled, String modelDirectory, double minimumSimilarity, double minimumMargin,
                         int cacheEntries, int buildDeadlineMillis) {
        this(enabled, modelDirectory, minimumSimilarity, minimumMargin, cacheEntries, buildDeadlineMillis, false);
    }
    @ConstructorBinding
    public RagProperties {
        if (modelDirectory == null || modelDirectory.isBlank() || modelDirectory.length() > 1000
                || !Double.isFinite(minimumSimilarity) || minimumSimilarity < 0.5 || minimumSimilarity > 1
                || !Double.isFinite(minimumMargin) || minimumMargin < 0 || minimumMargin > 0.3
                || cacheEntries < 32 || cacheEntries > 16384 || buildDeadlineMillis < 100 || buildDeadlineMillis > 15000)
            throw new IllegalArgumentException("Invalid local knowledge retrieval configuration");
    }
}
