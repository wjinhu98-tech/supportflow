package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.assistant.knowledge.KnowledgeAnswer;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationOrchestratorTest {
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final AssistantAgent agent = mock(AssistantAgent.class);
    private final CustomerPrincipal principal = new CustomerPrincipal("buyer", "not-a-real-hash",
            new CustomerIdentity(200), List.of());
    private final ConversationOrchestrator orchestrator = new ConversationOrchestrator(new ConversationRouter(), knowledge, agent);

    @ParameterizedTest
    @ValueSource(strings = {
            "这个是通过查数据库回答的吗", "刚刚回答的问题", "明天天气怎么样", "帮我写一段Java代码",
            "帮我写查询订单的SQL", "请写一个退款接口的Java代码"
    })
    void unrelatedQuestionsAreRejectedBeforeKnowledgeOrAgentExecution(String message) {
        for (String selected : new String[] {null, "SF10001"}) {
            ChatReply response = orchestrator.reply(principal, 100, message, selected);
            assertThat(response.reply()).isEqualTo("对不起，我无法回复您的问题。");
            assertThat(response.orders()).isEmpty();
            assertThat(response.knowledge()).isNull();
            assertThat(orchestrator.reply(principal, 100, message, selected, null)).isEqualTo(response);
        }
        verifyNoInteractions(knowledge, agent);
    }

    @Test
    void productDevelopmentCapabilityIsAProductFaqInsteadOfACodingRequest() {
        String message = "电脑是否支持Java开发";
        when(knowledge.tryAnswerDetailed(100, message)).thenReturn(Optional.of(answer("当前电脑的配置说明")));
        assertThat(orchestrator.reply(principal, 100, message, null).reply()).isEqualTo("当前电脑的配置说明");
        verifyNoInteractions(agent);
    }

    @Test
    void shouldAnswerKnowledgeWithoutInvokingBusinessAgent() {
        when(knowledge.answerDetailed(100, "退款政策是什么")).thenReturn(answer("source grounded policy"));
        assertThat(orchestrator.reply(principal, 100, "退款政策是什么", "SF10001").reply())
                .isEqualTo("source grounded policy");
        verifyNoInteractions(agent);
    }

    @Test
    void shouldPassTrustedIdentityShopAndSelectedOrderToAgent() {
        ChatReply expected = ChatReply.text("shipment result");
        when(agent.execute(AssistantRoute.LOGISTICS, principal, 100, "物流到了吗", "SF10001")).thenReturn(expected);
        assertThat(orchestrator.reply(principal, 100, "物流到了吗", "SF10001")).isSameAs(expected);
        verify(agent).execute(AssistantRoute.LOGISTICS, principal, 100, "物流到了吗", "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void reliableOrdinaryFaqUsesCurrentShopWithoutInvokingBusinessAgent() {
        when(knowledge.tryAnswerDetailed(100, "礼盒包装有什么颜色？"))
                .thenReturn(Optional.of(answer("published blue packaging @v1")));
        assertThat(orchestrator.reply(principal, 100, "礼盒包装有什么颜色？", null).reply())
                .isEqualTo("published blue packaging @v1");
        verifyNoInteractions(agent);
    }

    @Test
    void unmatchedOrdinaryQuestionRetainsExistingAgentFallback() {
        when(knowledge.tryAnswerDetailed(100, "你好")).thenReturn(Optional.empty());
        ChatReply expected = ChatReply.text("收到你的问题：你好");
        when(agent.execute(AssistantRoute.ORDER, principal, 100, "你好", null)).thenReturn(expected);
        assertThat(orchestrator.reply(principal, 100, "你好", null)).isSameAs(expected);
    }

    @Test
    void selectedOrderAmountFollowupAlwaysReachesRealBusinessAgent() {
        ChatReply expected = ChatReply.text("真实订单总金额99.00元");
        when(agent.execute(AssistantRoute.ORDER, principal, 100, "金额是多少", "SF10001")).thenReturn(expected);
        assertThat(orchestrator.reply(principal, 100, "金额是多少", "SF10001")).isSameAs(expected);
        verify(agent).execute(AssistantRoute.ORDER, principal, 100, "金额是多少", "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void unselectedOrderFactQuestionsCannotBeAnsweredByMerchantFaq() {
        for (String question : List.of("金额是多少", "总价是多少", "实际归属是谁", "买家ID是多少")) {
            ChatReply expected = ChatReply.text("请先选择本人订单");
            when(agent.execute(AssistantRoute.ORDER, principal, 100, question, null)).thenReturn(expected);
            assertThat(orchestrator.reply(principal, 100, question, null)).isSameAs(expected);
        }
        verifyNoInteractions(knowledge);
    }

    @Test
    void unselectedPublicShippingFeeQuestionMayUsePublishedFaq() {
        when(knowledge.tryAnswerDetailed(100, "运费多少钱"))
                .thenReturn(Optional.of(answer("店铺公开运费8元 @v1")));
        assertThat(orchestrator.reply(principal, 100, "运费多少钱", null).reply()).contains("运费8元");
        verifyNoInteractions(agent);
    }

    @Test
    void explicitOrderQueryCannotBeReplacedByMerchantFaqEvidence() {
        ChatReply expected = ChatReply.text("real order query");
        when(agent.execute(AssistantRoute.ORDER, principal, 100, "查订单 SF10001", null)).thenReturn(expected);
        assertThat(orchestrator.reply(principal, 100, "查订单 SF10001", null)).isSameAs(expected);
        verifyNoInteractions(knowledge);
    }

    @Test
    void structuredKnowledgeEvidencePassesThroughWithoutBusinessExecution() {
        KnowledgeAnswer expected = answer("礼盒可搭配贺卡，具体以商品说明为准。");
        when(knowledge.tryAnswerDetailed(100, "能放张祝福卡吗？")).thenReturn(Optional.of(expected));
        ChatReply actual = orchestrator.reply(principal, 100, "能放张祝福卡吗？", null);
        assertThat(actual.knowledge()).isEqualTo(expected.evidence());
        assertThat(actual.orders()).isEmpty();
        assertThat(actual.nextAfterId()).isNull();
        verifyNoInteractions(agent);
    }

    @Test
    void unavailableStoreRemainsAnExplicitEvidenceFailureInsteadOfCallingAnExternalModel() {
        var unavailable = new KnowledgeAnswer("资料暂不可用", new KnowledgeEvidence(KnowledgeStatus.UNAVAILABLE, List.of()));
        when(knowledge.tryAnswerDetailed(100, "礼盒包装")).thenReturn(Optional.of(unavailable));
        assertThat(orchestrator.reply(principal, 100, "礼盒包装", null).knowledge().status())
                .isEqualTo(KnowledgeStatus.UNAVAILABLE);
        verifyNoInteractions(agent);
    }

    private static KnowledgeAnswer answer(String text) {
        return new KnowledgeAnswer(text, new KnowledgeEvidence(KnowledgeStatus.ANSWERED,
                List.of(new KnowledgeSource("test-source", "v1", "演示知识", text))));
    }
}
