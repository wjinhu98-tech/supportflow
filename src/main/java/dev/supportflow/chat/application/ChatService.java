package dev.supportflow.chat.application;

import dev.supportflow.chat.application.model.OrderIntent;
import dev.supportflow.chat.application.model.OrderIntentType;
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
 * 普通消息继续回显，单笔及列表指令复用现有订单查询能力。
 * 自然语言由识别器提取查单参数；真实订单与买家身份始终由现有服务确定。
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

    public String reply(CustomerIdentity identity, String message) {
        Objects.requireNonNull(identity, "identity must not be null");
        Optional<OrderListCommand> listCommand = OrderListCommand.parse(message);
        if (listCommand.isPresent()) {
            return replyWithOrderList(identity, listCommand.get().afterId());
        }

        Optional<OrderLookupCommand> command = OrderLookupCommand.parse(message);
        if (command.isEmpty()) {
            return replyToNaturalLanguage(identity, message);
        }

        OrderLookupCommand lookup = command.get();
        // 买家身份来自认证结果；消息只能指定商铺和订单号。
        SupportOrder order = orderQueryService.getOwnedOrder(identity, lookup.shopId(), lookup.orderNo());
        return describeOrder(order);
    }

    private String replyToNaturalLanguage(CustomerIdentity identity, String message) {
        // 识别器只收到消息，不接收身份、权限或任何数据库中的订单详情。
        OrderIntent intent = orderIntentRecognizer.recognize(message);
        if (intent.type() == OrderIntentType.OTHER) {
            return "收到你的问题：" + message;
        }
        if (intent.shopId() == null && intent.orderNo() == null) {
            return "请提供商铺ID和订单号，例如：帮我查一下商铺100的SF10001订单。";
        }
        if (intent.shopId() == null) {
            return "请补充商铺ID，例如：帮我查一下商铺100的SF10001订单。";
        }
        if (intent.orderNo() == null) {
            return "请补充订单号，例如：帮我查一下商铺100的SF10001订单。";
        }
        SupportOrder order = orderQueryService.getOwnedOrder(
                identity, intent.shopId(), intent.orderNo());
        return describeOrder(order);
    }

    private String replyWithOrderList(CustomerIdentity identity, long afterId) {
        // 复用带方法级授权的服务；每次翻页仍使用当前认证身份。
        OrderPage page = orderQueryService.listOwnedOrders(
                identity, afterId, OrderQueryLimits.DEFAULT_PAGE_SIZE);
        if (page.items().isEmpty()) {
            return afterId == 0L ? "暂无订单。" : "没有更多订单。";
        }

        StringBuilder reply = new StringBuilder("你的订单（本批 ")
                .append(page.items().size()).append(" 笔）：");
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
        return reply.toString();
    }

    private String describeOrder(SupportOrder order) {
        return "商铺 " + order.tenantId() + " 的订单 " + order.orderNo()
                + "：" + order.status().getDescription()
                + "，订单金额 " + order.totalAmount().toPlainString() + " " + order.currency() + "。";
    }
}
