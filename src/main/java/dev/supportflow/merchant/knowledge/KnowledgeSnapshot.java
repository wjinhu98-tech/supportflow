package dev.supportflow.merchant.knowledge;

import java.time.Instant;
import java.util.List;

public record KnowledgeSnapshot(int revision, String title, List<String> keywords, String content, Instant publishedAt) {
    public KnowledgeSnapshot { keywords = List.copyOf(keywords); }
}
