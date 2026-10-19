package dev.supportflow.chat.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 普通消息继续回显，明确的查单指令复用现有订单查询能力。
 * 不调用模型，不猜测订单号或买家身份。
 */
@Service
public class ChatService {

    private final OrderQueryService orderQueryService;

    public ChatService(OrderQueryService orderQueryService) {
        this.orderQueryService = Objects.requireNonNull(orderQueryService, "orderQueryService must not be null");
    }

    public String reply(CustomerIdentity identity, String message) {
        Objects.requireNonNull(identity, "identity must not be null");
        Optional<OrderLookupCommand> command = OrderLookupCommand.parse(message);
        if (command.isEmpty()) {
            return "收到你的问题：" + message;
        }

        OrderLookupCommand lookup = command.get();
        // 买家身份来自认证结果；消息只能指定商铺和订单号。
        SupportOrder order = orderQueryService.getOwnedOrder(identity, lookup.shopId(), lookup.orderNo());
        return "商铺 " + order.tenantId() + " 的订单 " + order.orderNo()
                + "：" + order.status().getDescription()
                + "，订单金额 " + order.totalAmount().toPlainString() + " " + order.currency() + "。";
    }
}
