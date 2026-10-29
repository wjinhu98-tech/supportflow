package dev.supportflow.order.domain.repository;

import dev.supportflow.order.domain.model.SupportOrder;

import java.util.List;
import java.util.Optional;

/**
 * 客服订单读取能力。
 *
 * <p>每次查询必须限定认证结果中的平台买家ID，并排除买家已删除的订单。
 * 原订单与商家售后、审计记录由各自仓储保留。</p>
 */
public interface SupportOrderRepository {

    /**
     * 查询买家在指定商铺中的订单。
     *
     * <p>商铺ID是定位条件，买家ID必须来自可信认证结果。</p>
     *
     * @param tenantId 商铺ID，不能代替买家归属校验
     * @param buyerId 平台全局买家ID
     * @param orderNo 业务订单号，不得为 null 或空白
     * @return 范围内的订单；未找到时返回 Optional.empty()，不得返回 null
     * @throws IllegalArgumentException 订单号为 null 或空白
     */
    Optional<SupportOrder> findByOrderNo(long tenantId, long buyerId, String orderNo);

    /**
     * 跨商铺查询买家的订单，按 id 递增且仅返回 id 大于游标的记录。
     *
     * @param buyerId 来自认证结果的平台买家ID
     * @param afterId 上一页游标，首次为0
     * @param limit 最大读取行数，1至
     *              {@value dev.supportflow.order.domain.OrderQueryLimits#MAX_FETCH_SIZE}，
     *              含用于判断下一页的预读行
     */
    List<SupportOrder> findByBuyerId(long buyerId, long afterId, int limit);

    /**
     * Lists a buyer's orders within one shop, filtered in the database before
     * applying the ascending id cursor and bounded limit.
     *
     * @param shopId requested shop id, must be positive
     * @param buyerId authenticated platform buyer id, must be positive
     * @param afterId exclusive id cursor, zero for the first page
     * @param limit bounded fetch size, including any lookahead row
     */
    List<SupportOrder> findByShopAndBuyerId(
            long shopId, long buyerId, long afterId, int limit);

    /** Literal product-name/specification matching within the authenticated buyer and current shop. */
    List<SupportOrder> findByProductName(long shopId, long buyerId, String keyword, int limit);
}
