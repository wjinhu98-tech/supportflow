package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.OrderReceiptRepository;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOrderReceiptRepository implements OrderReceiptRepository {
    private final JdbcClient jdbc;

    public JdbcOrderReceiptRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<SupportOrder> lockOwnedOrder(long shopId, long buyerId, String orderNo) {
        return jdbc.sql("""
                SELECT id, tenant_id, customer_id, buyer_id, order_no, status,
                       total_amount, currency, created_at, updated_at, received_at, product_items_json
                FROM support_order
                WHERE tenant_id=:shop AND buyer_id=:buyer AND order_no=:number
                FOR UPDATE
                """).param("shop", shopId).param("buyer", buyerId).param("number", orderNo)
                .query(JdbcOrderReceiptRepository::map).optional();
    }

    @Override
    public boolean confirmReceipt(SupportOrder order) {
        return jdbc.sql("""
                UPDATE support_order SET status='COMPLETED', received_at=UTC_TIMESTAMP(3)
                WHERE id=:id AND tenant_id=:shop AND buyer_id=:buyer AND order_no=:number
                  AND status='SHIPPED' AND received_at IS NULL
                """).param("id", order.id()).param("shop", order.tenantId())
                .param("buyer", order.buyerId()).param("number", order.orderNo()).update() == 1;
    }

    @Override
    public void appendReceiptAudit(SupportOrder order, String actor) {
        int inserted = jdbc.sql("""
                INSERT INTO support_order_receipt_audit
                  (order_id, tenant_id, buyer_id, order_no, actor, received_at)
                VALUES (:id, :shop, :buyer, :number, :actor, :received)
                """).param("id", order.id()).param("shop", order.tenantId())
                .param("buyer", order.buyerId()).param("number", order.orderNo()).param("actor", actor)
                .param("received", LocalDateTime.ofInstant(order.receivedAt(), ZoneOffset.UTC)).update();
        if (inserted != 1) throw new IllegalStateException("Receipt audit was not persisted");
    }

    @Override
    public Instant currentTime() {
        return jdbc.sql("SELECT UTC_TIMESTAMP(3)").query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC);
    }

    private static SupportOrder map(ResultSet row, int ignored) throws SQLException {
        try {
            return new SupportOrder(row.getLong("id"), row.getLong("tenant_id"), row.getLong("customer_id"),
                    row.getLong("buyer_id"), row.getString("order_no"), OrderStatus.fromCode(row.getString("status")),
                    row.getBigDecimal("total_amount"), row.getString("currency"), instant(row, "created_at"),
                    instant(row, "updated_at"), instant(row, "received_at"),
                    OrderItemSnapshotJson.read(row.getString("product_items_json")));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new DataRetrievalFailureException("Invalid persisted order receipt data", failure);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
