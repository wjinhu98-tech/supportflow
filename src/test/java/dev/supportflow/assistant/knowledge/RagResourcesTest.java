package dev.supportflow.assistant.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagResourcesTest {
    @Test
    void referenceSourcesAreExplicitlyScopedAndKeepAttributionAndApplicability() {
        var references = new PublicReferenceCatalog();
        assertThat(references.forShop(100)).hasSize(18).extracting(KnowledgeDocument::sourceId).doesNotHaveDuplicates();
        assertThat(references.forShop(102)).hasSize(13).noneMatch(source -> source.sourceId().contains("textile") || source.sourceId().contains("ceramic"));
        assertThat(references.forShop(103)).hasSize(5).noneMatch(source -> source.sourceId().contains("android"));
        assertThat(references.forShop(999)).isEmpty();
        references.forShop(100).forEach(source -> {
            assertThat(source.content()).contains("来源：", "原文：https://", "2026-10-28");
            assertThat(source.content()).hasSizeLessThan(900);
        });
    }

    @Test
    void wordpieceKeepsChinesePunctuationAndCaseWithoutTrustingSpecialTokenText() {
        var tokenizer = new BertWordPieceTokenizer(List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "你", "好", "!", "USB", "##C"));
        assertThat(tokenizer.encode("你好! USBC")).containsExactly(2, 4, 5, 6, 7, 8, 3);
        assertThat(tokenizer.encode("usb")).containsExactly(2, 1, 3);
        assertThat(tokenizer.encode("你\u0000好")).containsExactly(2, 4, 5, 3);
        assertThat(tokenizer.encode("[CLS]")).isNotEqualTo(new long[]{2, 2, 3});
        assertThat(tokenizer.encode("你".repeat(600))).hasSize(384).endsWith(3);
        assertThatThrownBy(() -> new BertWordPieceTokenizer(List.of("[CLS]", "[CLS]"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkingPreservesEveryCodePointWithBoundedOverlapAndOriginalSourceUnchanged() {
        String body = "甲乙😀".repeat(220);
        var source = new KnowledgeDocument("kb-chunked", "v1", "标题", List.of("资料"), body);
        var chunks = KnowledgeChunk.split(source);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (var chunk : chunks) {
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(351);
            assertThat(chunk.text()).doesNotContain("\ufffd");
        }
        var reconstructed = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            String text = chunks.get(index).text().substring("标题\n".length());
            reconstructed.append(index == 0 ? text : text.substring(text.offsetByCodePoints(0, 32)));
        }
        assertThat(reconstructed.toString()).isEqualTo(body);
        assertThat(source.content()).isEqualTo(body);
    }

    @Test
    void aMissingOrTamperedLocalModelCannotBeLoadedAsSemanticSearch() {
        assertThatThrownBy(() -> new OnnxKnowledgeEmbedding(Path.of(".local/models/not-a-model")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum");
    }
}
