package dev.supportflow.assistant.orchestration;

import dev.supportflow.assistant.planning.ToolName;
import dev.supportflow.assistant.planning.ToolPlanner;
import dev.supportflow.assistant.tool.AfterSaleTool;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ChatService;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.stereotype.Service;

/** A bounded plan -> validate -> one tool execution flow, not an unconstrained model loop. */
@Service
public class AssistantAgent {
    private final ToolPlanner planner;
    private final OrderTool orderTool;
    private final LogisticsTool logistics;
    private final AfterSaleTool afterSale;
    private final ChatService legacyOrders;

    public AssistantAgent(ToolPlanner planner, OrderTool orderTool, LogisticsTool logistics,
                          AfterSaleTool afterSale, ChatService legacyOrders) {
        this.planner = planner;
        this.orderTool = orderTool;
        this.logistics = logistics;
        this.afterSale = afterSale;
        this.legacyOrders = legacyOrders;
    }

    public ChatReply execute(AssistantRoute route, CustomerPrincipal principal,
                             long shopId, String message, String selectedOrderNo) {
        if (route == AssistantRoute.AFTER_SALE_HISTORY) return ChatReply.text(afterSale.history(principal, shopId));
        if (route == AssistantRoute.AFTER_SALE_GUIDANCE) return ChatReply.text(
                "如需提交售后，请先选择订单，再明确发送：申请售后 <原因>。"
                + "系统会进行Java业务校验并创建待人工审核的申请，不会直接退款。");
        if (route == AssistantRoute.ORDER) return legacyOrders.reply(
                principal.getIdentity(), shopId, message, selectedOrderNo);
        if (route != AssistantRoute.LOGISTICS && route != AssistantRoute.AFTER_SALE) {
            throw new IllegalArgumentException("Unsupported agent route");
        }
        String authority = dev.supportflow.common.security.model.SecurityPermission.ORDER_READ.getAuthority();
        if (principal.getAuthorities().stream().noneMatch(permission -> authority.equals(permission.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("Order tools require read permission");
        }
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
        if (reason.isBlank()) return ChatReply.text("请补充售后原因，例如：申请售后 " + orderNo + " 商品破损");
        return ChatReply.text(afterSale.request(principal, shopId, orderNo, reason));
    }
}
