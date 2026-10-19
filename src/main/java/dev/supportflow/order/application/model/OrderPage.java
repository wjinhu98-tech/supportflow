package dev.supportflow.order.application.model;

import dev.supportflow.order.domain.model.SupportOrder;

import java.util.List;

/**
 * 按内部主键递增的游标分页结果；nextAfterId 为 null 时已无下一页。
 */
public record OrderPage(List<SupportOrder> items, Long nextAfterId) {

    public OrderPage {
        items = List.copyOf(items);
    }
}
