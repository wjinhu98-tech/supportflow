package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.planning.PlannedToolCall;
import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.assistant.tool.AfterSaleTool;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ChatService;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.model.ChatReplyType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantAgentTest {
    private final ToolPlanner planner = mock(ToolPlanner.class);
    private final OrderTool orders = mock(OrderTool.class);
    private final LogisticsTool logistics = mock(LogisticsTool.class);
    private final AfterSaleTool afterSale = mock(AfterSaleTool.class);
    private final ChatService legacy = mock(ChatService.class);
    private final CustomerIdentity identity = new CustomerIdentity(200);
    private final CustomerPrincipal principal = new CustomerPrincipal("buyer", "not-a-real-hash", identity,
            List.of(SecurityPermission.ORDER_READ));
    private final SupportOrder order = new SupportOrder(1, 100, 200, 200, "SF10001", OrderStatus.SHIPPED,
            new BigDecimal("100.00"), "CNY", Instant.EPOCH, Instant.EPOCH);
    private AssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AssistantAgent(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldDelegateOrderRouteToExistingOwnershipScopedOrderConversation() {
        ChatReply expected = ChatReply.details("order", order);
        when(legacy.reply(identity, 100, "SF10001多少钱", "SF10001")).thenReturn(expected);
        assertThat(agent.execute(AssistantRoute.ORDER, principal, 100, "SF10001多少钱", "SF10001")).isSameAs(expected);
        verifyNoInteractions(planner, orders, logistics, afterSale);
    }

    @Test
    void shouldLoadAfterSaleHistoryWithoutCallingModelOrAnyOtherTool() {
        when(afterSale.history(principal, 100)).thenReturn("pending request");
        assertThat(agent.execute(AssistantRoute.AFTER_SALE_HISTORY, principal, 100, "我的售后", "SF10001").reply())
                .isEqualTo("pending request");
        verify(afterSale).history(principal, 100);
        verifyNoInteractions(planner, orders, logistics, legacy);
    }

    @Test
    void shouldNeverOpenFormOrPlanRequestForNegatedOrReadOnlyMessages() {
        for (String message : List.of("不要申请退款", "我不退款", "如何申请退款", "退款是否到账")) {
            ChatReply reply = agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 100, message, "SF10001");
            assertThat(reply.type()).isEqualTo(ChatReplyType.TEXT);
            assertThat(reply.orders()).isEmpty();
            assertThat(reply.reply()).contains("没有提交新的退款申请").doesNotContain("Java", "SQL", "AI");
        }
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldOpenSelectedOwnedOrderFormWithoutPlanningOrWriting() {
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);
        ChatReply reply = agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 100, "将该订单退款", "SF10001");
        assertThat(reply.type()).isEqualTo(ChatReplyType.AFTER_SALE_FORM);
        assertThat(reply.orders()).containsExactly(order);
        assertThat(reply.nextAfterId()).isNull();
        assertThat(reply.reply()).contains("SF10001", "退款原因", "客服审核", "不会实际返款")
                .doesNotContain("Java", "SQL", "AI", "Tool", "PENDING_REVIEW");
        verify(orders).lookup(identity, 100, "SF10001");
        verifyNoInteractions(planner, logistics, afterSale, legacy);
    }

    @Test
    void shouldRequireOrderChoiceWhenMissingOrAmbiguousWithoutModelFallback() {
        ChatReply missing = agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 100, "申请退款", null);
        ChatReply ambiguous = agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 100,
                "请将SF10001和SF10002退款", "SF10001");
        for (ChatReply reply : List.of(missing, ambiguous)) {
            assertThat(reply.type()).isEqualTo(ChatReplyType.AFTER_SALE_FORM);
            assertThat(reply.orders()).isEmpty();
            assertThat(reply.nextAfterId()).isNull();
            assertThat(reply.reply()).contains("选择", "退款原因");
        }
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldUseExplicitOrderInsteadOfStaleSelectedOrderAndCheckItsOwnership() {
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);
        ChatReply reply = agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 100,
                "请将SF10001退款", "SF10002");
        assertThat(reply.orders()).containsExactly(order);
        verify(orders).lookup(identity, 100, "SF10001");
        verifyNoInteractions(planner, logistics, afterSale, legacy);
    }

    @Test
    void shouldNotExposeRefundFormForOrderNotOwnedByCurrentBuyerInShop() {
        IllegalStateException ownershipFailure = new IllegalStateException("not owned");
        when(orders.lookup(identity, 101, "SF10001")).thenThrow(ownershipFailure);
        assertThatThrownBy(() -> agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, principal, 101,
                "申请退款", "SF10001")).isSameAs(ownershipFailure);
        verifyNoInteractions(planner, logistics, afterSale, legacy);
    }

    @Test
    void shouldAuthorizeBeforeLoadingAnyRefundOrder() {
        CustomerPrincipal noPermissions = new CustomerPrincipal("other", "not-a-real-hash", identity, List.of());
        assertThatThrownBy(() -> agent.execute(AssistantRoute.AFTER_SALE_GUIDANCE, noPermissions, 100,
                "申请退款", "SF10001"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldAuthorizeOrderBeforeReadingLogisticsAndPassSelectedOrderToPlanner() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, "到哪里了", "SF10001"))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001"));
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);
        when(logistics.lookup(order)).thenReturn("warehouse event");

        assertThat(agent.execute(AssistantRoute.LOGISTICS, principal, 100, "到哪里了", "SF10001").reply())
                .isEqualTo("warehouse event");

        InOrder flow = inOrder(planner, orders, logistics);
        flow.verify(planner).plan(ToolName.LOGISTICS_LOOKUP, "到哪里了", "SF10001");
        flow.verify(orders).lookup(identity, 100, "SF10001");
        flow.verify(logistics).lookup(order);
        verifyNoInteractions(afterSale, legacy);
    }

    @Test
    void shouldNotReadShipmentWhenOrderIsNotOwnedInCurrentShop() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, "SF10001物流", null))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, "SF10001"));
        IllegalStateException ownershipFailure = new IllegalStateException("not owned");
        when(orders.lookup(identity, 100, "SF10001")).thenThrow(ownershipFailure);

        assertThatThrownBy(() -> agent.execute(AssistantRoute.LOGISTICS, principal, 100, "SF10001物流", null))
                .isSameAs(ownershipFailure);
        verifyNoInteractions(logistics, afterSale, legacy);
    }

    @Test
    void shouldOfferCurrentShopOwnedOrderCandidatesWhenLogisticsNumberIsMissing() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, "我的物流", null))
                .thenReturn(new PlannedToolCall(ToolName.LOGISTICS_LOOKUP, null));
        ChatReply candidates = ChatReply.selection("select order", List.of(order), null);
        when(legacy.reply(identity, 100, "查订单", null)).thenReturn(candidates);

        assertThat(agent.execute(AssistantRoute.LOGISTICS, principal, 100, "我的物流", null)).isSameAs(candidates);
        verifyNoInteractions(orders, logistics, afterSale);
    }

    @Test
    void shouldOfferCandidatesWithoutSubmittingAfterSaleWhenNoOrderIsKnown() {
        String message = "申请售后 商品破损";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, null))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, null));
        ChatReply candidates = ChatReply.selection("select order", List.of(order), null);
        when(legacy.reply(identity, 100, "查订单", null)).thenReturn(candidates);

        assertThat(agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, null)).isSameAs(candidates);
        verifyNoInteractions(orders, logistics, afterSale);
    }

    @Test
    void shouldRejectModelToolEscalationFromLogisticsToAfterSale() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, "SF10001物流", null))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        assertThatThrownBy(() -> agent.execute(AssistantRoute.LOGISTICS, principal, 100, "SF10001物流", null))
                .isExactlyInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldRejectWrongPlannedToolOnAfterSaleRouteToo() {
        String message = "申请售后 SF10001 商品破损";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, null))
                .thenReturn(new PlannedToolCall(ToolName.ORDER_LOOKUP, "SF10001"));
        assertThatThrownBy(() -> agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, null))
                .isExactlyInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldPreserveRawUserReasonAfterExplicitCommandAndOrderNumber() {
        String message = "  申请售后 SF10001 商品破损，外包装有压痕！  ";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, null))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);
        when(afterSale.request(principal, 100, "SF10001", "商品破损，外包装有压痕！")).thenReturn("pending review");

        assertThat(agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, null).reply())
                .isEqualTo("pending review");
        InOrder flow = inOrder(planner, orders, afterSale);
        flow.verify(planner).plan(ToolName.AFTER_SALE_REQUEST, message, null);
        flow.verify(orders).lookup(identity, 100, "SF10001");
        flow.verify(afterSale).request(principal, 100, "SF10001", "商品破损，外包装有压痕！");
        verifyNoInteractions(logistics, legacy);
    }

    @Test
    void shouldKeepReasonWhenOrderComesFromSelectionRatherThanMessage() {
        String message = "申请售后 商品质量问题";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, "SF10001"))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);
        when(afterSale.request(principal, 100, "SF10001", "商品质量问题")).thenReturn("pending review");

        assertThat(agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, "SF10001").reply())
                .isEqualTo("pending review");
        verify(afterSale).request(principal, 100, "SF10001", "商品质量问题");
    }

    @Test
    void shouldRequireReasonWhenCommandOnlyContainsOrderNumber() {
        String message = "申请售后 SF10001";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, null))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);

        ChatReply reply = agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, null);
        assertThat(reply.type()).isEqualTo(ChatReplyType.AFTER_SALE_FORM);
        assertThat(reply.orders()).containsExactly(order);
        assertThat(reply.reply()).contains("请补充退款原因");
        verifyNoInteractions(afterSale, logistics, legacy);
    }

    @Test
    void shouldRequireReasonWithSelectedOrderAndBareCommand() {
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, "申请售后", "SF10001"))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        when(orders.lookup(identity, 100, "SF10001")).thenReturn(order);

        assertThat(agent.execute(AssistantRoute.AFTER_SALE, principal, 100, "申请售后", "SF10001").reply())
                .contains("请补充退款原因");
        verifyNoInteractions(afterSale, logistics, legacy);
    }

    @Test
    void shouldNotCreateAfterSaleWhenOrderAuthorizationFails() {
        String message = "申请售后 SF10001 商品破损";
        when(planner.plan(ToolName.AFTER_SALE_REQUEST, message, null))
                .thenReturn(new PlannedToolCall(ToolName.AFTER_SALE_REQUEST, "SF10001"));
        IllegalStateException ownershipFailure = new IllegalStateException("not owned");
        when(orders.lookup(identity, 100, "SF10001")).thenThrow(ownershipFailure);

        assertThatThrownBy(() -> agent.execute(AssistantRoute.AFTER_SALE, principal, 100, message, null))
                .isSameAs(ownershipFailure);
        verifyNoInteractions(afterSale, logistics, legacy);
    }

    @Test
    void shouldNotExecuteAnyToolWhenModelFails() {
        when(planner.plan(ToolName.LOGISTICS_LOOKUP, "物流", "SF10001")).thenThrow(new ModelUnavailableException());
        assertThatThrownBy(() -> agent.execute(AssistantRoute.LOGISTICS, principal, 100, "物流", "SF10001"))
                .isExactlyInstanceOf(ModelUnavailableException.class);
        verifyNoInteractions(orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldRejectUnexpectedKnowledgeRouteWithoutAnyBusinessAction() {
        assertThatThrownBy(() -> agent.execute(AssistantRoute.KNOWLEDGE, principal, 100, "退款政策", "SF10001"))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldNeverSubmitWriteWithoutExplicitCommandEvenIfCallerRoutesItIncorrectly() {
        assertThatThrownBy(() -> agent.execute(AssistantRoute.AFTER_SALE, principal, 100, "不要申请售后 SF10001", "SF10001"))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }

    @Test
    void shouldCheckOrderReadPermissionBeforeCallingModelOrBusinessTools() {
        CustomerPrincipal noPermissions = new CustomerPrincipal("other", "not-a-real-hash", identity, List.of());
        for (AssistantRoute route : List.of(AssistantRoute.LOGISTICS, AssistantRoute.AFTER_SALE)) {
            assertThatThrownBy(() -> agent.execute(route, noPermissions, 100, "申请售后 商品破损", "SF10001"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        verifyNoInteractions(planner, orders, logistics, afterSale, legacy);
    }
}
