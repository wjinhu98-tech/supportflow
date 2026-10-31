package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.knowledge.KnowledgeAnswer;
import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.assistant.orchestration.ConversationRouter;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConversationContextEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-25T05:00:00Z");
    private static final String SOURCE = "kb-11111111-1111-1111-1111-111111111111";
    private final CustomerPrincipal principal = new CustomerPrincipal("buyer", "unused", new CustomerIdentity(200),
            List.of(SecurityPermission.ORDER_READ));
    private final ConversationOrchestrator orchestrator = mock(ConversationOrchestrator.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final OrderQueryService orders = mock(OrderQueryService.class);
    private final ConversationContextEngine engine = new ConversationContextEngine(orchestrator, knowledge, orders,
            new ConversationRouter(), Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {
            "这个是通过查数据库回答的吗", "刚刚回答的问题", "明天天气怎么样", "帮我写一段Java代码",
            "帮我写查询订单的SQL", "请写一个退款接口的Java代码"
    })
    void unrelatedQuestionsCannotBorrowAnyRememberedOrSelectedSubject(String message) {
        for (ConversationContextRef previous : List.of(ConversationContextRef.none(), orderContext(), knowledgeContext())) {
            for (String selected : new String[] {null, "SF10001"}) {
                ChatResponse response = reply(message, selected, previous);
                assertThat(response.reply()).isEqualTo("对不起，我无法回复您的问题。");
                assertThat(response.orders()).isEmpty();
                assertThat(response.knowledge()).isNull();
                assertThat(response.context().kind()).isEqualTo(ConversationContextKind.NONE);
            }
        }
        verifyNoInteractions(orders, knowledge, orchestrator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"电脑是否支持Java开发", "这个商品的材质代码是什么意思"})
    void technicalWordsInProductQuestionsStillUseCurrentShopKnowledge(String message) {
        when(knowledge.tryAnswerDetailed(100, message))
                .thenReturn(Optional.of(answer(SOURCE, "v1", "当前商品的使用说明")));
        ChatResponse response = reply(message, "SF10001", orderContext());
        assertThat(response.reply()).isEqualTo("当前商品的使用说明");
        assertThat(response.context().kind()).isEqualTo(ConversationContextKind.KNOWLEDGE);
        verify(knowledge).tryAnswerDetailed(100, message);
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void technicalWordsInAnExplicitAfterSaleReasonRemainBusinessInput() {
        String message = "申请售后 原因：程序里提示通过查数据库回答出错";
        when(orchestrator.reply(principal, 100, message, "SF10001"))
                .thenReturn(ChatReply.text("原有售后校验结果"));
        assertThat(reply(message, "SF10001", orderContext()).reply()).isEqualTo("原有售后校验结果");
        verify(orchestrator).reply(principal, 100, message, "SF10001");
        verifyNoInteractions(orders, knowledge);
    }

    @Test
    void aNewOwnedOrderCardEstablishesOnlyItsPublicReference() {
        when(orchestrator.reply(principal, 100, "查订单 SF10001", "SF10001"))
                .thenReturn(ChatReply.details("当前订单", order("SF10001")));
        ChatResponse response = reply("查订单 SF10001", null, ConversationContextRef.none());
        assertThat(response.context()).isEqualTo(ConversationContextRef.order("SF10001", expiry()));
        assertThat(response.context().sourceIds()).isEmpty();
    }

    @Test
    void anOrderFollowupRechecksOwnershipAndUsesAReadOnlyCommandRatherThanOldFactsOrAModelGuess() {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10001"))
                .thenReturn(ChatReply.details("当前为已发货", order("SF10001")));
        ChatResponse response = reply("这单现在什么状态？", null, orderContext());
        assertThat(response.reply()).contains("已发货");
        var flow = inOrder(orders, orchestrator);
        flow.verify(orders).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
        flow.verify(orchestrator).reply(principal, 100, "查询此订单", "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void theCurrentExplicitNumberOutranksBothSelectedAndRememberedOrders() {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10002"))
                .thenReturn(ChatReply.details("第二笔订单", order("SF10002")));
        ChatResponse response = reply("这笔 SF10002 现在什么状态", "SF10001", orderContext());
        assertThat(response.context().orderNo()).isEqualTo("SF10002");
        verify(orders).getOwnedOrder(principal.getIdentity(), 100, "SF10002");
        verify(orders, never()).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
    }

    @Test
    void anExplicitCurrentSelectionOutranksTheRememberedOrder() {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10002"))
                .thenReturn(ChatReply.details("第二笔订单", order("SF10002")));
        assertThat(reply("这单发货了吗", "SF10002", orderContext()).context().orderNo()).isEqualTo("SF10002");
        verify(orders).getOwnedOrder(principal.getIdentity(), 100, "SF10002");
    }

    @Test
    void expiredAndExplicitlyClearedOrderReferencesNeverReachTheBusinessService() {
        var expired = ConversationContextRef.order("SF10001", NOW);
        assertThat(reply("这单现在什么状态？", null, expired).context().kind()).isEqualTo(ConversationContextKind.NONE);
        var cleared = new ConversationMessageRequest(UUID.randomUUID(), "这单现在什么状态？", null, true);
        assertThat(engine.reply(principal, 100, cleared, orderContext()).context().kind())
                .isEqualTo(ConversationContextKind.NONE);
        verifyNoInteractions(orders, orchestrator, knowledge);
    }

    @Test
    void missingOrFarFutureReferencesRequireClarification() {
        assertThat(reply("这单发货了吗", null, ConversationContextRef.none()).context().kind())
                .isEqualTo(ConversationContextKind.NONE);
        assertThat(reply("这单发货了吗", null, ConversationContextRef.order("SF10001", expiry().plusSeconds(1)))
                .context().kind()).isEqualTo(ConversationContextKind.NONE);
        verifyNoInteractions(orders, orchestrator, knowledge);
    }

    @Test
    void aPreviouslyValidOrderReferenceDoesNotBypassCurrentOwnership() {
        when(orders.getOwnedOrder(principal.getIdentity(), 100, "SF10001")).thenThrow(new OrderNotFoundException());
        assertThatThrownBy(() -> reply("这单现在什么状态？", null, orderContext()))
                .isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void logisticsTextCanEstablishAReferenceOnlyAfterCurrentOwnershipWasChecked() {
        when(orchestrator.reply(principal, 100, "查物流", "SF10001")).thenReturn(ChatReply.text("当前录入的物流事件"));
        ChatResponse response = reply("查物流", "SF10001", ConversationContextRef.none());
        assertThat(response.context()).isEqualTo(ConversationContextRef.order("SF10001", expiry()));
        verify(orders).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
    }

    @Test
    void writeAndSensitiveRequestsNeverBorrowTheRememberedOrderOrConfirmation() {
        for (String text : List.of("申请退款", "申请售后 damaged item", "确认退款", "帮我取消这单", "修改这单地址")) {
            when(orchestrator.reply(principal, 100, text, null)).thenReturn(ChatReply.text("原有显式参数流程"));
            assertThat(reply(text, null, orderContext()).context().kind()).isEqualTo(ConversationContextKind.NONE);
            verify(orchestrator).reply(principal, 100, text, null);
        }
        verifyNoInteractions(orders, knowledge);
    }

    @Test
    void aGreetingCannotValidateAClientSuppliedForeignOrderOrRenewATopicLease() {
        when(orchestrator.reply(principal, 100, "你好", "FOREIGN-ORDER")).thenReturn(ChatReply.text("你好"));
        assertThat(reply("你好", "FOREIGN-ORDER", ConversationContextRef.none()).context().kind())
                .isEqualTo(ConversationContextKind.NONE);
        when(orchestrator.reply(principal, 100, "你好", null)).thenReturn(ChatReply.text("你好"));
        assertThat(reply("你好", null, orderContext()).context()).isEqualTo(orderContext());
        verifyNoInteractions(orders);
    }

    @Test
    void aFreshKnowledgeAnswerCreatesOnlySourcePointers() {
        KnowledgeAnswer answer = answer(SOURCE, "v1", "演示贺卡可手写祝福语。");
        when(knowledge.tryAnswerDetailed(100, "贺卡能写字吗")).thenReturn(Optional.of(answer));
        ChatResponse response = reply("贺卡能写字吗", null, ConversationContextRef.none());
        assertThat(response.context()).isEqualTo(ConversationContextRef.knowledge(List.of(SOURCE), expiry()));
        assertThat(response.knowledge()).isEqualTo(answer.evidence());
        verifyNoInteractions(orchestrator, orders);
    }

    @Test
    void aKnowledgeFollowupReadsTheCurrentSourceRatherThanTheHistoricalVersion() {
        when(knowledge.followUpDetailed(100, "那怎么填写？", List.of(SOURCE)))
                .thenReturn(answer(SOURCE, "v2", "当前版本请先与客服确认文字。"));
        ChatResponse response = reply("那怎么填写？", null, knowledgeContext());
        assertThat(response.knowledge().sources().getFirst().version()).isEqualTo("v2");
        assertThat(response.reply()).contains("当前版本");
        verify(knowledge).followUpDetailed(100, "那怎么填写？", List.of(SOURCE));
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void aClearlyNamedCurrentKnowledgeTopicOutranksTheRememberedSource() {
        String sizes = "kb-22222222-2222-2222-2222-222222222222";
        when(knowledge.tryAnswerDetailed(100, "那尺码怎么选"))
                .thenReturn(Optional.of(answer(sizes, "v1", "参考当前商品尺码表。")));
        assertThat(reply("那尺码怎么选", null, knowledgeContext()).context().sourceIds()).containsExactly(sizes);
        verify(knowledge, never()).followUpDetailed(anyLong(), anyString(), anyList());
    }

    @Test
    void multipleKnowledgeSourcesAndMissingTopicsAreClarifiedWithoutGuessing() {
        var multiple = ConversationContextRef.knowledge(List.of(SOURCE, "second-source"), expiry());
        assertThat(reply("那怎么填写？", null, multiple).context().kind()).isEqualTo(ConversationContextKind.NONE);
        assertThat(reply("那怎么填写？", null, ConversationContextRef.none()).context().kind())
                .isEqualTo(ConversationContextKind.NONE);
        verify(knowledge, never()).followUpDetailed(anyLong(), anyString(), anyList());
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void unavailableAndOfflineSourcesClearTheReferenceInsteadOfRevivingOldEvidence() {
        for (KnowledgeStatus status : List.of(KnowledgeStatus.NO_MATCH, KnowledgeStatus.UNAVAILABLE)) {
            when(knowledge.followUpDetailed(100, "那怎么填写？", List.of(SOURCE)))
                    .thenReturn(new KnowledgeAnswer("资料当前不可引用", new KnowledgeEvidence(status, List.of())));
            ChatResponse response = reply("那怎么填写？", null, knowledgeContext());
            assertThat(response.context().kind()).isEqualTo(ConversationContextKind.NONE);
            assertThat(response.knowledge().sources()).isEmpty();
        }
        verifyNoInteractions(orchestrator, orders);
    }

    @Test
    void anExplicitKnowledgeCommandWinsOverAStaleFrontendOrderSelection() {
        when(orchestrator.reply(principal, 100, "知识库：礼盒价格", "SF10001"))
                .thenReturn(ChatReply.knowledge("公开礼盒说明", answer(SOURCE, "v1", "公开礼盒说明").evidence()));
        assertThat(reply("知识库：礼盒价格", "SF10001", orderContext()).context().kind())
                .isEqualTo(ConversationContextKind.KNOWLEDGE);
        verifyNoInteractions(orders);
    }

    @Test
    void multipleExplicitOrdersNeverSelectOneByGuessing() {
        assertThat(reply("这单 SF10001 还是 SF10002 的状态？", null, orderContext()).context().kind())
                .isEqualTo(ConversationContextKind.NONE);
        verifyNoInteractions(orchestrator, orders, knowledge);
    }

    @Test
    void vaguePronounsRequireClarificationForMissingExpiredOrderAndMultipleKnowledgeTopics() {
        var expired = ConversationContextRef.order("SF10001", NOW);
        var multiple = ConversationContextRef.knowledge(List.of(SOURCE, "second-source"), expiry());
        for (ConversationContextRef previous : List.of(ConversationContextRef.none(), expired,
                orderContext(), knowledgeContext(), multiple)) {
            for (String text : List.of("它呢", "那呢", "刚才那个呢")) {
                assertThat(reply(text, null, previous).context().kind()).isEqualTo(ConversationContextKind.NONE);
            }
        }
        verify(knowledge, never()).followUpDetailed(anyLong(), anyString(), anyList());
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void aClearlyNamedKnowledgeSubjectCanContainAReferenceWordWithoutBeingLost() {
        when(knowledge.tryAnswerDetailed(100, "这个礼盒有贺卡"))
                .thenReturn(Optional.of(answer(SOURCE, "v1", "礼盒包含演示贺卡。")));
        assertThat(reply("这个礼盒有贺卡", "SF10001", orderContext()).context().kind())
                .isEqualTo(ConversationContextKind.KNOWLEDGE);
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void genericPriceAndAmountFollowupsKeepTheCurrentOrderFactPathAheadOfKnowledgeKeywords() {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10001"))
                .thenReturn(ChatReply.details("当前订单金额为 99.00 CNY", order("SF10001")));
        for (String text : List.of("这个价格", "这个多少钱", "金额多少")) {
            ChatResponse response = reply(text, "SF10001", orderContext());
            assertThat(response.context().orderNo()).isEqualTo("SF10001");
            assertThat(response.reply()).contains("99.00");
        }
        verify(orders, times(3)).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void explicitlyNamedProductAndPublicFeePricesUseCurrentKnowledgeInsteadOfAStaleSelectedOrder() {
        for (String text : List.of("这个礼盒多少钱", "这个礼盒价格", "包装费用是多少", "配送费多少")) {
            when(knowledge.tryAnswerDetailed(100, text))
                    .thenReturn(Optional.of(answer(SOURCE, "v2", "当前公开费用说明：5.00 元。")));
            ChatResponse response = reply(text, "SF10001", orderContext());
            assertThat(response.context().kind()).isEqualTo(ConversationContextKind.KNOWLEDGE);
            assertThat(response.context().sourceIds()).containsExactly(SOURCE);
            assertThat(response.reply()).contains("5.00").doesNotContain("99.00");
        }
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void aNamedProductPriceWithoutReliableCurrentEvidenceCannotFallBackToTheOldOrderAmount() {
        for (String text : List.of("这个礼盒多少钱", "这个礼盒价格")) {
            ChatResponse response = reply(text, "SF10001", orderContext());
            assertThat(response.context().kind()).isEqualTo(ConversationContextKind.NONE);
            assertThat(response.reply()).contains("核实").doesNotContain("99.00", "SF10001");
        }
        verifyNoInteractions(orders, orchestrator);
        verify(knowledge, never()).followUpDetailed(anyLong(), anyString(), anyList());
    }

    @Test
    void explicitTransactionAmountsStayOrderFactsEvenWhenProductOrPublicFeeWordsArePresent() {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10001"))
                .thenReturn(ChatReply.details("当前订单金额为 99.00 CNY", order("SF10001")));
        for (String text : List.of("这单礼盒多少钱", "礼盒订单实付是多少", "礼盒总价多少", "礼盒交易金额多少")) {
            ChatResponse response = reply(text, "SF10001", orderContext());
            assertThat(response.context().orderNo()).isEqualTo("SF10001");
            assertThat(response.reply()).contains("99.00");
        }
        verify(orders, times(4)).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
        verifyNoInteractions(knowledge);
    }

    @ParameterizedTest
    @ValueSource(strings = {"本单", "该单", "此单", "本笔", "该笔", "此笔"})
    void transactionReferencesUseSelectedOrServerReferencedOrdersAndNeverPublicPrices(String reference) {
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10001"))
                .thenReturn(ChatReply.details("当前订单金额为 99.00 CNY", order("SF10001")));
        String message = reference + "礼盒多少钱";

        ChatResponse selected = reply(message, "SF10001", ConversationContextRef.none());
        ChatResponse remembered = reply(message, null, orderContext());

        assertThat(selected.context()).isEqualTo(ConversationContextRef.order("SF10001", expiry()));
        assertThat(remembered.context()).isEqualTo(ConversationContextRef.order("SF10001", expiry()));
        assertThat(selected.reply()).contains("99.00");
        assertThat(remembered.reply()).contains("99.00");
        verify(orders, times(2)).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
        verify(orchestrator, times(2)).reply(principal, 100, "查询此订单", "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void candidateOrderListsClearTheCurrentReferenceRatherThanChoosingAResult() {
        when(orchestrator.reply(principal, 100, "我的订单", null))
                .thenReturn(ChatReply.selection("请明确选择", List.of(order("SF10001")), null));
        assertThat(reply("我的订单", null, orderContext()).context().kind()).isEqualTo(ConversationContextKind.NONE);
    }

    @Test
    void namedProductOverridesTheOldUiOrderAndReturnsTheNewOwnedOrder() {
        when(orders.findOwnedOrdersByProduct(principal.getIdentity(), 100, "鼠标"))
                .thenReturn(new dev.supportflow.order.application.model.OrderProductMatches(List.of(order("SF10002")), false));
        when(orchestrator.reply(principal, 100, "查询此订单", "SF10002"))
                .thenReturn(ChatReply.details("待发货", order("SF10002")));
        var response = reply("我买的鼠标发货了吗", "SF10001", orderContext());
        assertThat(response.reply()).contains("包含「鼠标」", "整单状态", "待发货");
        assertThat(response.context().orderNo()).isEqualTo("SF10002");
        verify(orchestrator, never()).reply(principal, 100, "查询此订单", "SF10001");
        verifyNoInteractions(knowledge);
    }

    @Test
    void multipleProductOrdersRequireAnExplicitSelectionAndDoNotRunLogistics() {
        when(orders.findOwnedOrdersByProduct(principal.getIdentity(), 100, "耳机"))
                .thenReturn(new dev.supportflow.order.application.model.OrderProductMatches(List.of(order("SF10001"), order("SF10002")), false));
        var response = reply("耳机物流到哪了", "SF10001", orderContext());
        assertThat(response.type()).isEqualTo(dev.supportflow.chat.application.model.ChatReplyType.ORDER_SELECTION);
        assertThat(response.selectionIntent()).isEqualTo(dev.supportflow.chat.application.model.OrderSelectionIntent.LOGISTICS);
        assertThat(response.orders()).hasSize(2);
        assertThat(response.context().kind()).isEqualTo(ConversationContextKind.NONE);
        assertThat(response.nextAfterId()).isNull();
        verifyNoInteractions(orchestrator, knowledge);
    }

    @Test
    void aMissingNamedProductDoesNotFallBackToThePreviousOrder() {
        when(orders.findOwnedOrdersByProduct(principal.getIdentity(), 100, "游戏手柄"))
                .thenReturn(new dev.supportflow.order.application.model.OrderProductMatches(List.of(), false));
        var response = reply("我买的游戏手柄发货了吗", "SF10001", orderContext());
        assertThat(response.reply()).contains("暂未找到", "历史订单可能");
        assertThat(response.orders()).isEmpty();
        assertThat(response.context().kind()).isEqualTo(ConversationContextKind.NONE);
        verifyNoInteractions(orchestrator, knowledge);
    }

    @Test
    void productLookupNeverReusesAnUnfilteredPaginationCursor() {
        when(orders.findOwnedOrdersByProduct(principal.getIdentity(), 100, "耳机"))
                .thenReturn(new dev.supportflow.order.application.model.OrderProductMatches(
                        java.util.stream.IntStream.range(1, 21).mapToObj(i -> order("SF" + i)).toList(), true));
        var response = reply("我买的耳机发货了吗", null, ConversationContextRef.none());
        assertThat(response.orders()).hasSize(20);
        assertThat(response.nextAfterId()).isNull();
        assertThat(response.reply()).contains("补充更具体");
        verifyNoInteractions(orchestrator, knowledge);
    }

    @Test
    void aGeneralShippingQuestionDoesNotBecomeAReadOfTheOldSelectedOrder() {
        when(knowledge.answerDetailed(100, "耳机一般什么时候发货"))
                .thenReturn(answer(SOURCE, "v1", "公开发货安排说明"));
        var response = reply("耳机一般什么时候发货", "SF10001", orderContext());
        assertThat(response.reply()).contains("公开发货安排");
        assertThat(response.knowledge()).isNotNull();
        assertThat(response.context().kind()).isEqualTo(ConversationContextKind.KNOWLEDGE);
        verifyNoInteractions(orders, orchestrator);
    }

    @Test
    void anUnnamedLogisticsFollowupUsesTheOwnedOrderReferenceInsteadOfSearchingForAProductCalledInformation() {
        when(orchestrator.reply(principal, 100, "查物流", "SF10001")).thenReturn(ChatReply.text("最新物流记录"));
        var response = reply("快递什么时候能到", null, orderContext());
        assertThat(response.reply()).isEqualTo("最新物流记录");
        assertThat(response.context().orderNo()).isEqualTo("SF10001");
        verify(orders).getOwnedOrder(principal.getIdentity(), 100, "SF10001");
        verify(orders, never()).findOwnedOrdersByProduct(any(), anyLong(), anyString());
        verifyNoInteractions(knowledge);
    }

    private ChatResponse reply(String text, String selectedOrder, ConversationContextRef previous) {
        return engine.reply(principal, 100, new ConversationMessageRequest(UUID.randomUUID(), text, selectedOrder), previous);
    }
    @Test
    void explicitOrderSelectionCannotBeReplacedByAMatchingMerchantFaq() {
        when(knowledge.tryAnswerDetailed(100, "查订单"))
                .thenReturn(Optional.of(answer(SOURCE, "v1", "PUBLIC_FAQ_ORDER_TRAP")));
        when(orchestrator.reply(principal, 100, "查订单", null))
                .thenReturn(ChatReply.selection("请选择当前店铺的订单", List.of(order("SF10001")), null));
        assertThat(reply("查订单", null, ConversationContextRef.none()).type())
                .isEqualTo(dev.supportflow.chat.application.model.ChatReplyType.ORDER_SELECTION);
        verifyNoInteractions(knowledge, orders);
    }

    private static ConversationContextRef orderContext() {
        return ConversationContextRef.order("SF10001", NOW.plusSeconds(120));
    }
    private static ConversationContextRef knowledgeContext() {
        return ConversationContextRef.knowledge(List.of(SOURCE), NOW.plusSeconds(120));
    }
    private static Instant expiry() { return NOW.plusSeconds(ConversationLimits.CONTEXT_TTL_SECONDS); }
    private static KnowledgeAnswer answer(String id, String version, String content) {
        return new KnowledgeAnswer(content, new KnowledgeEvidence(KnowledgeStatus.ANSWERED,
                List.of(new KnowledgeSource(id, version, "演示资料", content))));
    }
    private static SupportOrder order(String number) {
        return new SupportOrder(1, 100, 200, 200, number, OrderStatus.SHIPPED,
                new BigDecimal("99.00"), "CNY", NOW, NOW);
    }
}
