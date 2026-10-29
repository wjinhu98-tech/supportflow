package dev.supportflow.merchant.knowledge;

/** Entire validated text for a plain-text preview; no trusted token or client-supplied hash is issued. */
public record KnowledgeImportPreview(String title, String content, int characterCount, int byteCount) { }
