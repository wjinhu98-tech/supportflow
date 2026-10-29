package dev.supportflow.order.infrastructure.persistence.jdbc;

import dev.supportflow.order.domain.model.OrderDeletionTarget;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.repository.OrderDeletionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOrderDeletionRepository implements OrderDeletionRepository {
    private final JdbcClient jdbc;

    public JdbcOrderDeletionRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public Optional<OrderDeletionTarget> lockOwnedOrder(long shopId, long buyerId, String orderNo) {
        return jdbc.sql("""
                SELECT id,tenant_id,buyer_id,order_no,status FROM support_order
                WHERE tenant_id=:shop AND buyer_id=:buyer AND order_no=:number
                FOR UPDATE
                """).param("shop", shopId).param("buyer", buyerId).param("number", orderNo)
                .query((row, ignored) -> new OrderDeletionTarget(row.getLong("id"), row.getLong("tenant_id"),
                        row.getLong("buyer_id"), row.getString("order_no"), OrderStatus.fromCode(row.getString("status"))))
                .optional();
    }

    @Override
    public boolean isDeleted(OrderDeletionTarget order) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM support_order_buyer_deletion
                WHERE order_id=:id AND tenant_id=:shop AND buyer_id=:buyer
                """).param("id", order.id()).param("shop", order.shopId()).param("buyer", order.buyerId())
                .query(Long.class).single() != 0;
    }

    @Override
    public boolean hasPendingAfterSale(OrderDeletionTarget order) {
        // The order X lock serializes new submissions with their order S lock.
        // Do not lock case/task rows here: execution workers acquire task -> case -> order.
        return jdbc.sql("""
                SELECT COUNT(*) FROM support_after_sale s
                LEFT JOIN support_after_sale_execution e ON e.request_id=s.id
                WHERE s.order_id=:id AND s.tenant_id=:shop AND s.buyer_id=:buyer
                  AND (s.status='PENDING_REVIEW'
                    OR (s.status='APPROVED' AND (e.status IS NULL OR e.status<>'SUCCEEDED')))
                """).param("id", order.id()).param("shop", order.shopId()).param("buyer", order.buyerId())
                .query(Long.class).single() != 0;
    }

    @Override
    public void appendDeletion(OrderDeletionTarget order, String actor) {
        int inserted = jdbc.sql("""
                INSERT INTO support_order_buyer_deletion(order_id,tenant_id,buyer_id,order_no,actor,deleted_at)
                VALUES (:id,:shop,:buyer,:number,:actor,UTC_TIMESTAMP(3))
                """).param("id", order.id()).param("shop", order.shopId()).param("buyer", order.buyerId())
                .param("number", order.orderNo()).param("actor", actor).update();
        if (inserted != 1) throw new IllegalStateException("Order deletion was not persisted");
    }
}
