package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.model.OrderIntentType;
import dev.supportflow.chat.application.model.ChatReply;
import dev.supportflow.chat.application.port.OrderIntentRecognizer;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 店铺由HTTP路径提供，身份由认证提供；模型只识别订单号。
 * 缺订单号时返回当前店铺本人订单供前端选择，不要求用户输入商铺ID。
 */
@Service
public class ChatService {

    private final OrderQueryService orderQueryService;
    private final OrderIntentRecognizer orderIntentRecognizer;

    public ChatService(OrderQueryService orderQueryService, OrderIntentRecognizer orderIntentRecognizer) {
        this.orderQueryService = Objects.requireNonNull(orderQueryService, "orderQueryService must not be null");
        this.orderIntentRecognizer = Objects.requireNonNull(
                orderIntentRecognizer, "orderIntentRecognizer must not be null");
    }

    public ChatReply reply(CustomerIdentity identity, long shopId, String message, String selectedOrderNo) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        validateSelectedOrderNo(selectedOrderNo);
        String normalized = message.strip();
        if (selectedOrderNo != null
                && (normalized.equals("咨询此订单") || normalized.equals("查询此订单"))) {
            return replyWithOrder(identity, shopId, selectedOrderNo);
        }
        if (normalized.equals("查订单")) {
            return replyWithOrderList(identity, shopId, 0L, true);
        }
        Optional<OrderListCommand> listCommand = OrderListCommand.parse(message);
        if (listCommand.isPresent()) {
            return replyWithOrderList(identity, shopId, listCommand.get().afterId(), false);
        }

        Optional<OrderLookupCommand> command = OrderLookupCommand.parse(message);
        if (command.isEmpty()) {
            return replyToNaturalLanguage(identity, shopId, message, selectedOrderNo);
        }

        OrderLookupCommand lookup = command.get();
        return replyWithOrder(identity, shopId, lookup.orderNo());
    }

    private ChatReply replyToNaturalLanguage(
            CustomerIdentity identity, long shopId, String message, String selectedOrderNo) {
        // 识别器只收到消息，不接收身份、权限或任何数据库中的订单详情。
        OrderIntent intent = orderIntentRecognizer.recognize(message);
        if (intent.type() == OrderIntentType.OTHER) {
            return ChatReply.text("收到你的问题：" + message);
        }
        String orderNo = intent.orderNo() != null ? intent.orderNo() : selectedOrderNo;
        if (orderNo == null) {
            return replyWithOrderList(identity, shopId, 0L, true);
        }
        return replyWithOrder(identity, shopId, orderNo);
    }

    private ChatReply replyWithOrder(CustomerIdentity identity, long shopId, String orderNo) {
        SupportOrder order = orderQueryService.getOwnedOrder(identity, shopId, orderNo);
        return ChatReply.details(describeOrder(order), order);
    }

    private ChatReply replyWithOrderList(CustomerIdentity identity, long shopId, long afterId, boolean prompt) {
        // 每次查询和翻页均在SQL层同时限制当前店铺及认证买家。
        OrderPage page = orderQueryService.listOwnedOrdersInShop(
                identity, shopId, afterId, OrderQueryLimits.DEFAULT_PAGE_SIZE);
        if (page.items().isEmpty()) {
            return ChatReply.selection(
                    afterId == 0L ? "你在当前店铺暂无订单。" : "当前店铺没有更多订单。",
                    page.items(), null);
        }

        StringBuilder reply = new StringBuilder();
        if (prompt) {
            reply.append("请选择当前店铺的一笔订单，或输入订单号。\n");
        }
        reply.append("当前店铺的订单（本批 ").append(page.items().size()).append(" 笔）：");
        for (int index = 0; index < page.items().size(); index++) {
            reply.append('\n').append(index + 1).append(". ")
                    .append(describeOrder(page.items().get(index)));
        }
        if (page.nextAfterId() != null) {
            // 原样使用服务生成的游标，不把它当页码加一，也不使用预读行。
            reply.append("\n还有更多订单，请发送：我的订单 ").append(page.nextAfterId());
        } else {
            reply.append("\n没有更多订单。");
        }
        return ChatReply.selection(reply.toString(), page.items(), page.nextAfterId());
    }

    private void validateSelectedOrderNo(String orderNo) {
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > OrderQueryLimits.MAX_ORDER_NO_LENGTH
                || orderNo.codePoints().anyMatch(codePoint ->
                        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                                || Character.isISOControl(codePoint)))) {
            throw new InvalidChatCommandException("订单号不能为空、超过64个字符或包含空白、控制字符");
        }
    }

    private String describeOrder(SupportOrder order) {
        return "商铺 " + order.tenantId() + " 的订单 " + order.orderNo()
                + "：" + order.status().getDescription()
                + "，订单金额 " + order.totalAmount().toPlainString() + " " + order.currency() + "。";
    }
}
