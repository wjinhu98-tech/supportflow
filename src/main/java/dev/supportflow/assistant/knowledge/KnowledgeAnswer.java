package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeEvidence;

import java.util.Objects;

/** A bounded display answer plus the complete, immutable source snapshots that support it. */
public record KnowledgeAnswer(String reply, KnowledgeEvidence evidence) {
    public static final int MAX_REPLY_LENGTH = 4_000;

    public KnowledgeAnswer {
        Objects.requireNonNull(reply, "knowledge reply must not be null");
        Objects.requireNonNull(evidence, "knowledge evidence must not be null");
        if (reply.isBlank() || reply.length() > MAX_REPLY_LENGTH) {
            throw new IllegalArgumentException("Invalid knowledge reply length");
        }
    }
}
