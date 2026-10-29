package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.ProductOrderKeyword;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.SupportOrderRepository;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
                   updated_at,
                   received_at,
                   product_items_json
            FROM support_order o
            WHERE tenant_id = :tenantId
              AND buyer_id = :buyerId
              AND order_no = :orderNo
              AND NOT EXISTS (SELECT 1 FROM support_order_buyer_deletion d WHERE d.order_id=o.id)
            """;

    private static final String FIND_BY_BUYER_ID_SQL = """
            SELECT id, tenant_id, customer_id, buyer_id, order_no, status,
                   total_amount, currency, created_at, updated_at, received_at, product_items_json
            FROM support_order o
            WHERE buyer_id = :buyerId
              AND id > :afterId
              AND NOT EXISTS (SELECT 1 FROM support_order_buyer_deletion d WHERE d.order_id=o.id)
            ORDER BY id ASC
            LIMIT :limit
            """;

    private static final String FIND_BY_SHOP_AND_BUYER_ID_SQL = """
            SELECT id, tenant_id, customer_id, buyer_id, order_no, status,
                   total_amount, currency, created_at, updated_at, received_at, product_items_json
            FROM support_order o
            WHERE buyer_id = :buyerId
              AND tenant_id = :shopId
              AND id > :afterId
              AND NOT EXISTS (SELECT 1 FROM support_order_buyer_deletion d WHERE d.order_id=o.id)
            ORDER BY id ASC
            LIMIT :limit
            """;

    // A correlated scalar count returns each owned order once even with several matching product items.
    // Explicit ESCAPE avoids treating product punctuation as wildcards or depending on backslash SQL modes.
    // Counting at most 20 items avoids MySQL 8.0's incorrect EXISTS/JSON_TABLE semijoin (MySQL bug #114897).
    private static final String FIND_BY_PRODUCT_NAME_SQL = """
            SELECT o.id, o.tenant_id, o.customer_id, o.buyer_id, o.order_no, o.status,
                   o.total_amount, o.currency, o.created_at, o.updated_at, o.received_at, o.product_items_json
            FROM support_order o
            WHERE o.tenant_id=:shopId AND o.buyer_id=:buyerId
              AND NOT EXISTS (SELECT 1 FROM support_order_buyer_deletion d WHERE d.order_id=o.id)
              AND (
                SELECT COUNT(*)
                FROM JSON_TABLE(COALESCE(o.product_items_json, JSON_ARRAY()), '$[*]' COLUMNS (
                    product_name VARCHAR(100) PATH '$.productName' ERROR ON EMPTY ERROR ON ERROR,
                    specification VARCHAR(80) PATH '$.specification' NULL ON EMPTY ERROR ON ERROR
                )) AS product
                WHERE CONVERT(CONCAT(product.product_name, ' ', COALESCE(product.specification, '')) USING utf8mb4)
                          COLLATE utf8mb4_0900_ai_ci LIKE :pattern ESCAPE '!'
                   OR (
                     CONVERT(CONCAT(product.product_name, ' ', COALESCE(product.specification, '')) USING utf8mb4)
                          COLLATE utf8mb4_0900_ai_ci LIKE :term0 ESCAPE '!'
                     AND (:term1 IS NULL OR CONVERT(CONCAT(product.product_name, ' ', COALESCE(product.specification, '')) USING utf8mb4)
                          COLLATE utf8mb4_0900_ai_ci LIKE :term1 ESCAPE '!')
                     AND (:term2 IS NULL OR CONVERT(CONCAT(product.product_name, ' ', COALESCE(product.specification, '')) USING utf8mb4)
                          COLLATE utf8mb4_0900_ai_ci LIKE :term2 ESCAPE '!')
                     AND (:term3 IS NULL OR CONVERT(CONCAT(product.product_name, ' ', COALESCE(product.specification, '')) USING utf8mb4)
                          COLLATE utf8mb4_0900_ai_ci LIKE :term3 ESCAPE '!')
                   )
              ) > 0
            ORDER BY o.id ASC
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

    @Override
    public List<SupportOrder> findByShopAndBuyerId(
            long shopId, long buyerId, long afterId, int limit) {
        if (shopId <= 0 || buyerId <= 0 || afterId < 0
                || limit < 1 || limit > OrderQueryLimits.MAX_FETCH_SIZE) {
            throw new IllegalArgumentException("Invalid shop, buyer or pagination range");
        }
        return jdbcClient.sql(FIND_BY_SHOP_AND_BUYER_ID_SQL)
                .param("shopId", shopId)
                .param("buyerId", buyerId)
                .param("afterId", afterId)
                .param("limit", limit)
                .query(JdbcSupportOrderRepository::mapRow)
                .list();
    }

    @Override
    public List<SupportOrder> findByProductName(long shopId, long buyerId, String keyword, int limit) {
        if (shopId <= 0 || buyerId <= 0 || limit < 1 || limit > OrderQueryLimits.MAX_PRODUCT_FETCH_SIZE) {
            throw new IllegalArgumentException("Invalid scoped product query");
        }
        ProductOrderKeyword search = new ProductOrderKeyword(keyword);
        List<String> terms = search.terms();
        var query = jdbcClient.sql(FIND_BY_PRODUCT_NAME_SQL).param("shopId", shopId).param("buyerId", buyerId)
                .param("pattern", literalPattern(search.value())).param("limit", limit);
        for (int index = 0; index < OrderQueryLimits.MAX_PRODUCT_SEARCH_TERMS; index++)
            query = query.param("term" + index, index < terms.size() ? literalPattern(terms.get(index)) : null, Types.VARCHAR);
        return query.query(JdbcSupportOrderRepository::mapRow).list();
    }

    private static String literalPattern(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
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
                    resultSet.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    nullableInstant(resultSet, "received_at"),
                    OrderItemSnapshotJson.read(resultSet.getString("product_items_json"))
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new DataRetrievalFailureException(
                    "Invalid persisted support order data", exception);
        }
    }

    private static java.time.Instant nullableInstant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
