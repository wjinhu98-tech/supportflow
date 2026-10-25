package dev.supportflow.merchant.knowledge;

import java.time.Instant;
import java.util.List;

/** An immutable published source; the article's mutable draft is deliberately absent. */
public record PublishedKnowledge(String articleId, int revision, String title, List<String> keywords,
                                 String content, Instant publishedAt) {
    public PublishedKnowledge { keywords = List.copyOf(keywords); }
}
