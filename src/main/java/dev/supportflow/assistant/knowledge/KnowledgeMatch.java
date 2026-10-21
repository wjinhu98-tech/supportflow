package dev.supportflow.assistant.knowledge;

import java.util.List;

/** Explainable retrieval evidence: immutable source, version, score and matching terms. */
public record KnowledgeMatch(
        String sourceId,
        String version,
        String title,
        String content,
        double score,
        List<String> matchedTerms
) {
    public KnowledgeMatch {
        matchedTerms = List.copyOf(matchedTerms);
    }
}
