package dev.supportflow.assistant.knowledge;

import java.util.List;
import java.util.Optional;

/** A consented public-reference-only relevance decision. Empty Optional keeps local evidence unchanged. */
public interface PublicKnowledgeSelector {
    Optional<List<String>> select(long shopId, String question, List<KnowledgeMatch> matches);
}
