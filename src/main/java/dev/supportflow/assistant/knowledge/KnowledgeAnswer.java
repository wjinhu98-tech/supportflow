package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeEvidence;

import java.util.Objects;

/** A bounded display answer plus the complete, immutable source snapshots that support it. */
public record KnowledgeAnswer(String reply, KnowledgeEvidence evidence) {
    public KnowledgeAnswer {
        Objects.requireNonNull(reply, "knowledge reply must not be null");
        Objects.requireNonNull(evidence, "knowledge evidence must not be null");
        if (reply.isBlank() || reply.length() > 4_000) {
            throw new IllegalArgumentException("Invalid knowledge reply length");
        }
    }
}
