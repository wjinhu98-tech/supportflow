package dev.supportflow.chat.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded evidence carried with the response and persisted unchanged with conversation history. */
public record KnowledgeEvidence(KnowledgeStatus status, List<KnowledgeSource> sources) {
    public static final int MAX_SOURCES = 2;

    public KnowledgeEvidence {
        Objects.requireNonNull(status, "knowledge status must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "knowledge sources must not be null"));
        if (sources.size() > MAX_SOURCES || (status == KnowledgeStatus.ANSWERED) != !sources.isEmpty()) {
            throw new IllegalArgumentException("Knowledge status and sources disagree");
        }
        var identifiers = new HashSet<String>();
        for (KnowledgeSource source : sources) {
            if (!identifiers.add(source.sourceId())) {
                throw new IllegalArgumentException("Duplicate knowledge source snapshot");
            }
        }
    }
}
