package dev.supportflow.assistant.knowledge;

/** Counts describe only current, shop-scoped publications; historical cache rows are excluded. */
public record KnowledgeIndexStatus(String status, int expectedChunks, int indexedChunks, String modelFingerprint) { }
