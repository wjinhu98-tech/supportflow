package dev.supportflow.chat.application.model;

import java.text.Normalizer;

/** Immutable public evidence snapshot, never a URL, instruction or current business record. */
public record KnowledgeSource(String sourceId, String version, String title, String content) {
    public static final int MAX_TITLE_LENGTH = 240;
    public static final int MAX_CONTENT_LENGTH = 16_384;

    public KnowledgeSource {
        if (sourceId == null || !sourceId.matches("[a-z][a-z0-9-]{1,63}")
                || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")
                || title == null || title.length() > MAX_TITLE_LENGTH || title.isBlank()
                || Normalizer.normalize(title, Normalizer.Form.NFKC).isBlank()
                || content == null || content.length() > MAX_CONTENT_LENGTH || content.isBlank()
                || Normalizer.normalize(content, Normalizer.Form.NFKC).isBlank()) {
            throw new IllegalArgumentException("Invalid knowledge source snapshot");
        }
    }
}
