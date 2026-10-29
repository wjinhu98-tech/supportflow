package dev.supportflow.order.application.model;

import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.SupportOrder;

import java.util.List;

/** Bounded product matches. A general order-list cursor must not be used to page a product search. */
public record OrderProductMatches(List<SupportOrder> items, boolean hasMore) {
    public OrderProductMatches {
        items = List.copyOf(items);
        if (items.size() > OrderQueryLimits.MAX_PRODUCT_MATCHES
                || hasMore && items.size() != OrderQueryLimits.MAX_PRODUCT_MATCHES)
            throw new IllegalArgumentException("Invalid bounded product matches");
    }
}
