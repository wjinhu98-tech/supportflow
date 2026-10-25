package dev.supportflow.merchant.knowledge;

import java.text.Normalizer;
import java.util.List;

/** Plain text only: values are never interpreted as HTML, URLs, templates, model instructions or tool calls. */
public record KnowledgeDraft(String title, List<String> keywords, String content) {
    public KnowledgeDraft {
        title = text(title, KnowledgeLimits.TITLE);
        content = text(content, KnowledgeLimits.CONTENT);
        if (keywords == null || keywords.isEmpty() || keywords.size() > KnowledgeLimits.KEYWORDS) {
            throw KnowledgeException.invalid();
        }
        keywords = keywords.stream().map(keyword -> text(keyword, KnowledgeLimits.KEYWORD_LENGTH)).toList();
        if (keywords.stream().distinct().count() != keywords.size()) throw KnowledgeException.invalid();
    }

    private static String text(String input, int maximum) {
        if (input == null || input.length() > maximum || input.isBlank()
                || Normalizer.normalize(input, Normalizer.Form.NFKC).isBlank()
                || input.codePoints().anyMatch(point -> Character.isISOControl(point)
                && point != '\n' && point != '\r' && point != '\t')) throw KnowledgeException.invalid();
        return input.strip();
    }
}
