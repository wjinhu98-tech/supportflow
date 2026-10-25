package dev.supportflow.chat.application.model;

/** Distinguishes a supported answer, insufficient evidence and an unavailable evidence store. */
public enum KnowledgeStatus {
    ANSWERED,
    NO_MATCH,
    UNAVAILABLE
}
