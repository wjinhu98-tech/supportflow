package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 Spring JDBC 的客服订单读取实现。
 *
 * <p>所有查询强制限定平台买家ID，商铺仅作为订单定位条件。</p>
 */
@Repository
public class JdbcSupportOrderRepository implements SupportOrderRepository {

    private static final String FIND_BY_ORDER_NO_SQL = """
            SELECT id,
                   tenant_id,
                   customer_id,
                   buyer_id,
                   order_no,
                   status,
                   total_amount,
                   currency,
                   created_at,
                   updated_at
            FROM support_order
            WHERE tenant_id = :tenantId
              AND buyer_id = :buyerId
              AND order_no = :orderNo
            """;

    private static final String FIND_BY_BUYER_ID_SQL = """
            SELECT id, tenant_id, customer_id, buyer_id, order_no, status,
                   total_amount, currency, created_at, updated_at
            FROM support_order
            WHERE buyer_id = :buyerId
              AND id > :afterId
            ORDER BY id ASC
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    public JdbcSupportOrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<SupportOrder> findByOrderNo(long tenantId, long buyerId, String orderNo) {
        if (tenantId <= 0 || buyerId <= 0) {
            throw new IllegalArgumentException("shopId and buyerId must be positive");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo must not be null or blank");
        }

        return jdbcClient.sql(FIND_BY_ORDER_NO_SQL)
                .param("tenantId", tenantId)
                .param("buyerId", buyerId)
                .param("orderNo", orderNo)
                .query(JdbcSupportOrderRepository::mapRow)
                .optional();
    }

    @Override
    public List<SupportOrder> findByBuyerId(long buyerId, long afterId, int limit) {
        if (buyerId <= 0 || afterId < 0 || limit < 1 || limit > OrderQueryLimits.MAX_FETCH_SIZE) {
            throw new IllegalArgumentException("Invalid buyer or pagination range");
        }
        return jdbcClient.sql(FIND_BY_BUYER_ID_SQL)
                .param("buyerId", buyerId)
                .param("afterId", afterId)
                .param("limit", limit)
                .query(JdbcSupportOrderRepository::mapRow)
                .list();
    }

    /**
     * 将数据库当前行转换为订单对象。
     *
     * <p>DATETIME 列按本项目的 UTC 存储约定解释。</p>
     */
    private static SupportOrder mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        try {
            return new SupportOrder(
                    resultSet.getLong("id"),
                    resultSet.getLong("tenant_id"),
                    resultSet.getLong("customer_id"),
                    resultSet.getLong("buyer_id"),
                    resultSet.getString("order_no"),
                    OrderStatus.fromCode(resultSet.getString("status")),
                    resultSet.getBigDecimal("total_amount"),
                    resultSet.getString("currency"),
                    resultSet.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    resultSet.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new DataRetrievalFailureException(
                    "Invalid persisted support order data", exception);
        }
    }
}
