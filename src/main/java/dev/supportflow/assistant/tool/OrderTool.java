package dev.supportflow.assistant.tool;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.stereotype.Service;

/** Tool facade around the same ownership-checked order use case as the REST API. */
@Service
public class OrderTool {
    private final OrderQueryService orders;
    public OrderTool(OrderQueryService orders) { this.orders = orders; }
    public SupportOrder lookup(CustomerIdentity identity, long shopId, String orderNo) {
        return orders.getOwnedOrder(identity, shopId, orderNo);
    }
}
