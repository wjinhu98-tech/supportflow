package dev.supportflow.assistant.knowledge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagKnowledgeSearchTest {
    private final RagProperties enabled = new RagProperties(true, "unused", 0.78, 0.025, 32, 8000);
    private KnowledgeDocument source(String id, String version, String content) {
        return new KnowledgeDocument(id, version, "蓝牙设备配对指南", List.of("蓝牙配置"), content);
    }

    @Test
    void denseVectorsCanRecallAParaphraseWithoutExactKeywordOverlap() {
        var document = source("kb-source-a", "v1", "按说明进入可发现状态后重新添加配件。");
        var search = new RagKnowledgeSearch(enabled, (text, query) -> new float[]{1, 0}, new SimpleMeterRegistry());
        assertThat(new KnowledgeRetriever(List.of(document)).retrieve("那个无线音频配件一直识别不了")).isEmpty();
        assertThat(search.search(100, List.of(document), "那个无线音频配件一直识别不了"))
                .extracting(KnowledgeMatch::sourceId).containsExactly("kb-source-a");
    }

    @Test
    void ambiguousDenseResultsAreNotInventedAsEvidence() {
        var first = source("kb-source-a", "v1", "第一份说明。");
        var second = source("kb-source-b", "v1", "第二份说明。");
        var search = new RagKnowledgeSearch(enabled, (text, query) -> new float[]{1, 0}, new SimpleMeterRegistry());
        assertThat(search.search(100, List.of(first, second), "没有主题锚点的提问")).isEmpty();
    }

    @Test
    void cacheKeysIncludeShopVersionAndContentAndOfflineSourcesAreNeverReturned() {
        var calls = new AtomicInteger();
        var search = new RagKnowledgeSearch(enabled, (text, query) -> { calls.incrementAndGet(); return new float[]{1, 0}; },
                new SimpleMeterRegistry());
        var first = source("kb-source-a", "v1", "第一版内容。");
        search.search(100, List.of(first), "孤立提问"); assertThat(calls).hasValue(2);
        search.search(100, List.of(first), "孤立提问"); assertThat(calls).hasValue(3);
        search.search(101, List.of(first), "孤立提问"); assertThat(calls).hasValue(5);
        search.search(100, List.of(source("kb-source-a", "v2", "第二版内容。")), "孤立提问"); assertThat(calls).hasValue(7);
        search.search(100, List.of(source("kb-source-a", "v2", "内容摘要也参与键。")), "孤立提问"); assertThat(calls).hasValue(9);
        assertThat(search.search(100, List.of(), "孤立提问")).isEmpty();
    }

    @Test
    void unavailableEmbeddingsKeepVerifiedLexicalEvidenceAndRecordDegradation() {
        var meters = new SimpleMeterRegistry();
        var document = source("kb-source-a", "v1", "配对步骤。");
        var search = new RagKnowledgeSearch(enabled, (text, query) -> { throw new IllegalStateException("private data"); }, meters);
        assertThat(search.search(100, List.of(document), "蓝牙配置怎么操作"))
                .extracting(KnowledgeMatch::sourceId).containsExactly("kb-source-a");
        assertThat(meters.get("supportflow.rag.fallback").tag("reason", "embedding_unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void longQueriesAreNotSilentlyTruncatedAndBoundedCacheCannotGrowWithoutLimit() {
        var calls = new AtomicInteger();
        var search = new RagKnowledgeSearch(enabled, (text, query) -> { calls.incrementAndGet(); return new float[]{1, 0}; },
                new SimpleMeterRegistry());
        assertThat(search.search(100, List.of(), "长".repeat(301))).isEmpty();
        assertThat(search.search(100, List.of(source("kb-source-a", "v1", "内容")), "\u00a0\u3000")).isEmpty();
        assertThat(search.search(100, List.of(source("kb-source-a", "v1", "内容")), "?!！")).isEmpty();
        assertThat(calls).hasValue(0);
        for (int index = 0; index < 40; index++) search.search(index + 1, List.of(source("kb-source-a", "v1", "内容")), "孤立提问");
        int before = calls.get();
        search.search(1, List.of(source("kb-source-a", "v1", "内容")), "孤立提问");
        assertThat(calls).hasValue(before + 2);
    }

    @Test
    void invalidVectorsAndThresholdConfigurationAreRejected() {
        assertThatThrownBy(() -> RagKnowledgeSearch.cosine(new float[]{Float.NaN}, new float[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagKnowledgeSearch.cosine(new float[]{0}, new float[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RagKnowledgeSearch.cosine(new float[]{1}, new float[]{1, 0})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties(true, "model", Double.NaN, 0, 32, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties(true, "model", 0.7, 0, 31, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
