package dev.supportflow.merchant.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeAudit(String id, KnowledgeAction action, String actor, int version,
                             Integer publishedRevision, String title, List<String> keywords,
                             String content, Instant createdAt) {
    public KnowledgeAudit { keywords = List.copyOf(keywords); }
}
