package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.runtime.ReadOnlyAgentRuntime;
import dev.supportflow.assistant.runtime.store.AgentRunInput;
import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.assistant.tool.AfterSaleTool;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ChatService;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

/** Durable read-only logistics steps; business mutations retain the explicit approval flow. */
@Service
public class AssistantAgent {
    private final ToolPlanner planner;
    private final OrderTool orderTool;
    private final LogisticsTool logistics;
    private final AfterSaleTool afterSale;
    private final ChatService legacyOrders;
    private final ReadOnlyAgentRuntime runtime;

    public AssistantAgent(ToolPlanner planner, OrderTool orderTool, LogisticsTool logistics,
                          AfterSaleTool afterSale, ChatService legacyOrders) {
        this(planner, orderTool, logistics, afterSale, legacyOrders, null);
    }

    @Autowired
    public AssistantAgent(ToolPlanner planner, OrderTool orderTool, LogisticsTool logistics,
                          AfterSaleTool afterSale, ChatService legacyOrders, ReadOnlyAgentRuntime runtime) {
        this.planner = planner;
        this.orderTool = orderTool;
        this.logistics = logistics;
        this.afterSale = afterSale;
        this.legacyOrders = legacyOrders;
        this.runtime = runtime;
    }

    public ChatReply execute(AssistantRoute route, CustomerPrincipal principal, long shopId,
                             String message, String selectedOrderNo, AgentRunInput input) {
        if (route != AssistantRoute.LOGISTICS || input == null) {
            return execute(route, principal, shopId, message, selectedOrderNo);
        }
        if (runtime == null) throw new IllegalStateException("Durable runtime is required for conversation runs");
        return ChatReply.text(runtime.execute(principal, input).result());
    }

    public ChatReply execute(AssistantRoute route, CustomerPrincipal principal,
                             long shopId, String message, String selectedOrderNo) {
        if (route == AssistantRoute.AFTER_SALE_HISTORY) return ChatReply.text(afterSale.history(principal, shopId));
        if (route == AssistantRoute.AFTER_SALE_GUIDANCE) return refundForm(principal, shopId, message, selectedOrderNo);
        if (route == AssistantRoute.ORDER) return legacyOrders.reply(
                principal.getIdentity(), shopId, message, selectedOrderNo);
        if (route != AssistantRoute.LOGISTICS && route != AssistantRoute.AFTER_SALE) {
            throw new IllegalArgumentException("Unsupported agent route");
        }
        requireOrderRead(principal);
        if (route == AssistantRoute.AFTER_SALE && !(message.strip().equals("申请售后")
                || message.strip().startsWith("申请售后 "))) {
            throw new dev.supportflow.chat.application.InvalidChatCommandException("提交售后必须明确发送：申请售后 <原因>");
        }
        ToolName permitted = route == AssistantRoute.LOGISTICS
                ? ToolName.LOGISTICS_LOOKUP : ToolName.AFTER_SALE_REQUEST;
        var call = planner.plan(permitted, message, selectedOrderNo);
        if (call.tool() != permitted) throw new ModelUnavailableException();
        String orderNo = call.orderNo();
        if (orderNo == null) return legacyOrders.reply(principal.getIdentity(), shopId, "查订单", null);
        // Recheck authorization/ownership in Java even if model parameters look plausible.
        var order = orderTool.lookup(principal.getIdentity(), shopId, orderNo);
        if (route == AssistantRoute.LOGISTICS) return ChatReply.text(logistics.lookup(order));
        String reason = message.strip().substring("申请售后".length()).strip();
        if (reason.startsWith(orderNo)) reason = reason.substring(orderNo.length()).strip();
        if (reason.isBlank()) return ChatReply.refundForm("请补充退款原因，提交后会核对售后期限，并交由客服审核。"
                + "当前为演示环境，不会实际返款。", order);
        return ChatReply.text(afterSale.request(principal, shopId, orderNo, reason));
    }

    private ChatReply refundForm(CustomerPrincipal principal, long shopId, String message, String selectedOrderNo) {
        // Defend this branch even when a caller supplies a route without going through the router.
        if (!ConversationRouter.isRefundApplicationRequest(message)) {
            return ChatReply.text("这条消息没有提交新的退款申请。如需申请退款，请选择订单并填写退款原因。");
        }
        requireOrderRead(principal);
        Set<String> explicit = ExplicitOrderNumbers.extract(message);
        String note = "提交后会核对售后期限，并交由客服审核。当前为演示环境，不会实际返款。";
        if (explicit.size() > 1) {
            return ChatReply.refundForm("你提到了多笔订单，请先选择其中一笔，再填写退款原因。" + note, null);
        }
        String orderNo = explicit.isEmpty() ? selectedOrderNo : explicit.iterator().next();
        if (orderNo == null) {
            return ChatReply.refundForm("请先选择当前店铺需要退款的订单，再填写退款原因。" + note, null);
        }
        // The form receives only an owned order, never identifiers invented by a model.
        new dev.supportflow.assistant.planning.PlannedToolCall(ToolName.ORDER_LOOKUP, orderNo);
        var order = orderTool.lookup(principal.getIdentity(), shopId, orderNo);
        return ChatReply.refundForm("请填写订单 " + order.orderNo() + " 的退款原因。" + note, order);
    }

    private void requireOrderRead(CustomerPrincipal principal) {
        String authority = dev.supportflow.common.security.model.SecurityPermission.ORDER_READ.getAuthority();
        if (principal.getAuthorities().stream().noneMatch(permission -> authority.equals(permission.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("Order tools require read permission");
        }
    }
}
