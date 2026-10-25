package dev.supportflow.merchant.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeArticle(String id, long shopId, String title, List<String> keywords, String content,
                               KnowledgeStatus status, int version, Integer publishedRevision,
                               KnowledgeSnapshot publishedSnapshot, Instant createdAt, Instant updatedAt) {
    public KnowledgeArticle { keywords = List.copyOf(keywords); }
}
