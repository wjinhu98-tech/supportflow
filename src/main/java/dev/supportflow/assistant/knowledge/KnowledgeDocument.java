package dev.supportflow.assistant.knowledge;

import java.util.List;
import java.util.Objects;

/** A repository-controlled knowledge document, not a customer or model instruction. */
record KnowledgeDocument(String sourceId, String version, String title, List<String> keywords, String content) {

    KnowledgeDocument {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (!sourceId.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Unsupported knowledge sourceId");
        }
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")) {
            throw new IllegalArgumentException("Unsupported knowledge version");
        }
        if (title.isBlank() || content.isBlank()) {
            throw new IllegalArgumentException("Knowledge title and content must not be blank");
        }
        keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords must not be null"));
        if (keywords.isEmpty() || keywords.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Knowledge keywords must not be empty");
        }
    }
}
