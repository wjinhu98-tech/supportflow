package dev.supportflow.order.api.response;

import dev.supportflow.order.application.model.OrderPage;

import java.util.List;
import java.util.Objects;

/**
 * 买家的跨商铺订单列表。游标仅用于翻页，每一页仍检查买家归属。
 */
public record OrderPageResponse(
        List<OrderResponse> items,
        Long nextAfterId
) {

    public OrderPageResponse {
        items = List.copyOf(items);
    }

    public static OrderPageResponse from(OrderPage page) {
        Objects.requireNonNull(page, "page must not be null");
        return new OrderPageResponse(
                page.items().stream().map(OrderResponse::from).toList(),
                page.nextAfterId()
        );
    }
}
