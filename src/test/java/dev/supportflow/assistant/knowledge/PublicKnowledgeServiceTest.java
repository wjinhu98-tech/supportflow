package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.merchant.knowledge.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicKnowledgeServiceTest {
    private static final String QUESTION = "安卓手机找不到无线耳塞，怎么连接？";
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final RagKnowledgeSearch rag = mock(RagKnowledgeSearch.class);
    private final PublicKnowledgeSelector selector = mock(PublicKnowledgeSelector.class);
    private final KnowledgeMatch first = new PublicReferenceCatalog().find(102, "ref-android-bluetooth-reconnect").orElseThrow();
    private final KnowledgeMatch second = new PublicReferenceCatalog().find(102, "ref-android-bluetooth-pair").orElseThrow();
    private KnowledgeService knowledge;

    @BeforeEach void setup() {
        when(rag.publicSelectionEnabled()).thenReturn(true);
        when(repository.publishedForShop(anyLong())).thenReturn(List.of());
        when(rag.search(eq(102L), anyList(), eq(QUESTION))).thenReturn(List.of(first, second));
        knowledge = new KnowledgeService(repository, rag, selector);
    }

    @Test void onlyTheSuppliedSelectedSourceIsQuotedWithoutChangingItsContentOrVersion() {
        when(selector.select(102, QUESTION, List.of(first, second))).thenReturn(Optional.of(List.of(second.sourceId())));
        var answer = knowledge.answerDetailed(102, QUESTION);
        assertThat(answer.evidence().status()).isEqualTo(KnowledgeStatus.ANSWERED);
        assertThat(answer.evidence().sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceId()).isEqualTo(second.sourceId()); assertThat(source.version()).isEqualTo(second.version());
            assertThat(source.content()).isEqualTo(second.content());
        });
        assertThat(answer.reply()).contains(second.content()).doesNotContain(first.title());
        verify(selector).select(102, QUESTION, List.of(first, second));
    }

    @Test void privateMixedAndForgedSourceSetsNeverReachTheSelector() {
        var privateSource = new KnowledgeMatch("kb-private", "v1", "私有FAQ", "PRIVATE-CONTENT", 1, List.of());
        var forged = new KnowledgeMatch(first.sourceId(), first.version(), first.title(), "PRIVATE-CONTENT", 1, List.of());
        for (var matches : List.of(List.of(privateSource), List.of(first, privateSource), List.of(forged))) {
            when(rag.search(eq(102L), anyList(), eq(QUESTION))).thenReturn(matches);
            assertThat(knowledge.answerDetailed(102, QUESTION).evidence().sources()).hasSize(matches.size());
        }
        verifyNoInteractions(selector);
    }

    @Test void readOnlyGetSearchAndRestoringCurrentPublicReferencesDoNotCallTheSelector() {
        assertThat(knowledge.searchEvidence(102, QUESTION).evidence().sources()).hasSize(2);
        when(rag.references(102)).thenReturn(new PublicReferenceCatalog().forShop(102));
        assertThat(knowledge.followUpDetailed(102, "展开这份说明", List.of(first.sourceId())).evidence().sources()).hasSize(1);
        verifyNoInteractions(selector);
    }

    @Test void protectedBusinessQuestionsAndDisabledFlagStayLocal() {
        knowledge.answerDetailed(102, "这个订单的退款已经审批了吗");
        verifyNoInteractions(selector);
        when(rag.publicSelectionEnabled()).thenReturn(false);
        assertThat(knowledge.answerDetailed(102, QUESTION).evidence().sources()).hasSize(2);
        verifyNoInteractions(selector);
    }

    @Test void invalidOrUnavailableModelOutputFallsBackButAValidNoEvidenceDecisionRefuses() {
        when(selector.select(anyLong(), anyString(), anyList())).thenReturn(Optional.empty());
        assertThat(knowledge.answerDetailed(102, QUESTION).evidence().sources()).hasSize(2);
        when(selector.select(anyLong(), anyString(), anyList())).thenReturn(Optional.of(List.of("kb-private")));
        assertThat(knowledge.answerDetailed(102, QUESTION).evidence().sources()).hasSize(2);
        when(selector.select(anyLong(), anyString(), anyList())).thenThrow(new IllegalStateException("private-provider-data"));
        assertThat(knowledge.answerDetailed(102, QUESTION).reply()).doesNotContain("private-provider-data");
        doReturn(Optional.of(List.of())).when(selector).select(anyLong(), anyString(), anyList());
        var refused = knowledge.answerDetailed(102, QUESTION);
        assertThat(refused.evidence().status()).isEqualTo(KnowledgeStatus.NO_MATCH);
        assertThat(refused.evidence().sources()).isEmpty();
    }
}
