package dev.supportflow.chat.application.model;

import dev.supportflow.chat.api.response.ChatResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeEvidenceTest {
    private final KnowledgeSource source = new KnowledgeSource("kb-12345678-1234-1234-1234-123456789abc", "v1",
            "【演示】礼盒包装", "仅供本地演示。礼盒可配贺卡，不代表实际服务承诺。");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sourcesAreImmutableSnapshotsEvenWhenTheInputListChanges() {
        var inputs = new ArrayList<>(List.of(source));
        var evidence = new KnowledgeEvidence(KnowledgeStatus.ANSWERED, inputs);
        inputs.clear();
        assertThat(evidence.sources()).containsExactly(source);
        assertThatThrownBy(() -> evidence.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void statusesCannotClaimAnAnswerWithoutEvidenceOrAttachEvidenceToARefusal() {
        assertThatThrownBy(() -> new KnowledgeEvidence(KnowledgeStatus.ANSWERED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var status : List.of(KnowledgeStatus.NO_MATCH, KnowledgeStatus.UNAVAILABLE)) {
            assertThat(new KnowledgeEvidence(status, List.of()).sources()).isEmpty();
            assertThatThrownBy(() -> new KnowledgeEvidence(status, List.of(source)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void duplicatedOrUnboundedCitationsAreRejected() {
        assertThatThrownBy(() -> new KnowledgeEvidence(KnowledgeStatus.ANSWERED, List.of(source, source)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeEvidence(KnowledgeStatus.ANSWERED,
                List.of(source, new KnowledgeSource("second-source", "v1", "二", "二"),
                        new KnowledgeSource("third-source", "v1", "三", "三"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceIdentifiersCannotBePathsOrUrlsAndBodiesHaveExplicitBounds() {
        for (String id : List.of("../secret", "https://invalid.example", "file:///secret", "x\nprivate")) {
            assertThatThrownBy(() -> new KnowledgeSource(id, "v1", "title", "content"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new KnowledgeSource("valid-source", "v1\nprivate", "title", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("valid-source", "v1", "字".repeat(241), "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeSource("valid-source", "v1", "title", "字".repeat(16_385)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KnowledgeSource("valid-source", "v1", "字".repeat(240), "字".repeat(16_384)).content())
                .hasSize(16_384);
    }

    @Test
    void sourceBodyIsDataAndIsNotRewrittenAsMarkupOrInstructions() {
        String original = "<script>alert('test')</script>\nhttps://invalid.example/source";
        var value = new KnowledgeSource("valid-source", "v1", "原文", original);
        assertThat(value.content()).isEqualTo(original);
        assertThat(json.readValue(json.writeValueAsString(value), KnowledgeSource.class)).isEqualTo(value);
    }

    @Test
    void normalizedEmptyEvidenceTextIsNotAValidPublicSource() {
        for (String blank : List.of("\u00A0", "\u2007", "\u202F")) {
            assertThatThrownBy(() -> new KnowledgeSource("valid-source", "v1", blank, "content"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeSource("valid-source", "v1", "title", blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void historicalJsonWithoutKnowledgeStillDeserializesWithoutInventingSources() {
        ChatResponse old = json.readValue("""
                {"reply":"旧的对话原文","type":"TEXT","orders":[],"nextAfterId":null}
                """, ChatResponse.class);
        assertThat(old.reply()).isEqualTo("旧的对话原文");
        assertThat(old.knowledge()).isNull();
        assertThat(new ChatResponse("old", ChatReplyType.TEXT, List.of(), null).knowledge()).isNull();
    }

    @Test
    void publicResponseAndStoredJsonPreserveTheSameEvidenceRevision() {
        var evidence = new KnowledgeEvidence(KnowledgeStatus.ANSWERED, List.of(source));
        ChatReply reply = ChatReply.knowledge("简短原文摘录", evidence);
        ChatResponse response = ChatResponse.from(reply);
        ChatResponse recovered = json.readValue(json.writeValueAsString(response), ChatResponse.class);
        assertThat(recovered).isEqualTo(response);
        assertThat(recovered.knowledge().sources()).containsExactly(source);
        assertThat(response.reply()).doesNotContain(source.content());
    }

    @Test
    void knowledgeMetadataCannotBeAttachedToBusinessCards() {
        var evidence = new KnowledgeEvidence(KnowledgeStatus.ANSWERED, List.of(source));
        assertThatThrownBy(() -> new ChatReply("selection", ChatReplyType.ORDER_SELECTION, List.of(), null, evidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatResponse("selection", ChatReplyType.ORDER_SELECTION, List.of(), null, evidence))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ChatReply.text("ordinary reply").knowledge()).isNull();
    }
}
