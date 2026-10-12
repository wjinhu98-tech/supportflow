package dev.supportflow.order.domain.repository;

import dev.supportflow.order.domain.model.SupportOrder;

import java.util.Optional;

/**
 * 客服订单读取能力。
 *
 * <p>查询必须同时限定租户、客户和订单号。</p>
 */
public interface SupportOrderRepository {

    /**
     * 查询指定租户、客户名下的订单。
     *
     * <p>租户和客户标识必须来自可信身份上下文，
     * 不得直接使用客户端或模型自报的身份作为查询范围。</p>
     *
     * @param tenantId 所属租户ID
     * @param customerId 所属客户ID
     * @param orderNo 业务订单号，不得为 null 或空白
     * @return 范围内的订单；未找到时返回 Optional.empty()，不得返回 null
     * @throws IllegalArgumentException 订单号为 null 或空白
     */
    Optional<SupportOrder> findByOrderNo(
            long tenantId,
            long customerId,
            String orderNo
    );
}