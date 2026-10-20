package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Objects;

/**
 * 当前平台买家的跨商铺订单查询用例。
 */
@Service
@PreAuthorize("hasAuthority(T(dev.supportflow.common.security.model.SecurityPermission).ORDER_READ.getAuthority())")
public class OrderQueryService {

    private final SupportOrderRepository supportOrderRepository;

    public OrderQueryService(SupportOrderRepository supportOrderRepository) {
        this.supportOrderRepository = Objects.requireNonNull(
                supportOrderRepository, "supportOrderRepository must not be null");
    }

    /**
     * 获取当前身份有权访问的订单。
     *
     * @param identity 来自可信认证结果的客户身份
     * @param shopId 商铺定位条件，不是当前买家的身份
     * @param orderNo 业务订单号
     * @return 查询范围内的订单
     * @throws OrderNotFoundException 范围内没有可访问的订单
     * @throws IllegalArgumentException 订单号为 null 或空白
     */
    public SupportOrder getOwnedOrder(CustomerIdentity identity, long shopId, String orderNo) {
        Objects.requireNonNull(identity, "identity must not be null");
        return supportOrderRepository.findByOrderNo(shopId, identity.buyerId(), orderNo)
                .orElseThrow(OrderNotFoundException::new);
    }

    /**
     * 查询买家在所有商铺的订单；多读一行判断下一页，避免无界读取。
     */
    public OrderPage listOwnedOrders(CustomerIdentity identity, long afterId, int size) {
        Objects.requireNonNull(identity, "identity must not be null");
        validatePagination(afterId, size);
        List<SupportOrder> rows = supportOrderRepository.findByBuyerId(
                identity.buyerId(), afterId, size + OrderQueryLimits.LOOKAHEAD_ROWS);
        return toPage(rows, size);
    }

    /**
     * Lists only the authenticated buyer's orders in the requested shop.
     * The shop identifies the requested scope; it never replaces buyer ownership.
     */
    public OrderPage listOwnedOrdersInShop(
            CustomerIdentity identity, long shopId, long afterId, int size) {
        Objects.requireNonNull(identity, "identity must not be null");
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        validatePagination(afterId, size);
        List<SupportOrder> rows = supportOrderRepository.findByShopAndBuyerId(
                shopId, identity.buyerId(), afterId,
                size + OrderQueryLimits.LOOKAHEAD_ROWS);
        return toPage(rows, size);
    }

    private static void validatePagination(long afterId, int size) {
        if (afterId < 0 || size < 1 || size > OrderQueryLimits.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid pagination range");
        }
    }

    private static OrderPage toPage(List<SupportOrder> rows, int size) {
        boolean hasNext = rows.size() > size;
        List<SupportOrder> items = hasNext ? rows.subList(0, size) : rows;
        // 游标取实际返回的最后一行，预读行留给下一页，避免漏单。
        Long nextAfterId = hasNext ? items.getLast().id() : null;
        return new OrderPage(items, nextAfterId);
    }
}
