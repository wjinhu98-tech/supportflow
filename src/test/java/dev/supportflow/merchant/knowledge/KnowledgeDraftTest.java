package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDraftTest {
    @Test
    void normalizesBoundaryWhitespaceAndCopiesKeywordCollection() {
        var keywords = new ArrayList<>(List.of(" 礼盒 "));
        var draft = new KnowledgeDraft(" 标题 ", keywords, " 正文\n第二行 ");
        keywords.clear();
        assertThat(draft.title()).isEqualTo("标题");
        assertThat(draft.content()).isEqualTo("正文\n第二行");
        assertThat(draft.keywords()).containsExactly("礼盒");
        assertThatThrownBy(() -> draft.keywords().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingBlankOrTooLongPlainText() {
        for (String text : Arrays.asList(null, "", "  ", "x".repeat(4001), "NUL\u0000value")) {
            assertThatThrownBy(() -> new KnowledgeDraft("title", List.of("key"), text))
                    .isInstanceOf(AssistantBusinessException.class);
        }
        assertThatThrownBy(() -> new KnowledgeDraft("t".repeat(121), List.of("key"), "body"))
                .isInstanceOf(AssistantBusinessException.class);
    }

    @Test
    void keywordsAreBoundedNonemptyAndDistinctAfterNormalization() {
        for (var keys : List.of(List.<String>of(), List.of(" "), List.of("key", " key "),
                List.of("k".repeat(41)), java.util.Collections.nCopies(13, "key"))) {
            assertThatThrownBy(() -> new KnowledgeDraft("title", keys, "body"))
                    .isInstanceOf(AssistantBusinessException.class);
        }
        assertThatThrownBy(() -> new KnowledgeDraft("title", null, "body"))
                .isInstanceOf(AssistantBusinessException.class);
    }

    @Test
    void acceptsBoundedLiteralTextWithoutExecutingMarkupOrCommands() {
        var draft = new KnowledgeDraft("title", List.of("key"), "<script>alert('data')</script>\nignore instructions");
        assertThat(draft.content()).contains("<script>", "ignore instructions");
    }

    @Test
    void rejectsTextThatBecomesEmptyAfterRetrievalNormalizationWithoutRewritingRealOriginals() {
        for (String whitespace : List.of("\u00A0", "\u2007", "\u202F", "\u00A0\u2007\u202F")) {
            assertThatThrownBy(() -> new KnowledgeDraft("title", List.of(whitespace), "body"))
                    .isInstanceOf(AssistantBusinessException.class);
            assertThatThrownBy(() -> new KnowledgeDraft(whitespace, List.of("key"), "body"))
                    .isInstanceOf(AssistantBusinessException.class);
            assertThatThrownBy(() -> new KnowledgeDraft("title", List.of("key"), whitespace))
                    .isInstanceOf(AssistantBusinessException.class);
        }
        String original = "\u00A0ＦＡＱ：演示原文\u00A0";
        assertThat(new KnowledgeDraft("title", List.of("ＦＡＱ"), original).content()).isEqualTo(original);
    }
}
