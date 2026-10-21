package dev.supportflow.assistant.execution;

import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AfterSaleExecutionRepository {
    public static final int MAX_ATTEMPTS = 5;
    private final JdbcClient jdbc;

    public AfterSaleExecutionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void enqueue(String requestId, SupportOrder order) {
        int inserted = jdbc.sql("""
                INSERT INTO support_after_sale_execution
                  (request_id, order_id, buyer_id, tenant_id, amount, currency, status,
                   attempt_count, max_attempts, available_at)
                SELECT id, order_id, buyer_id, tenant_id, :amount, :currency, 'PENDING', 0, :maximum, UTC_TIMESTAMP(3)
                FROM support_after_sale WHERE id=:id AND status='APPROVED'
                  AND order_id=:orderId AND buyer_id=:buyer AND tenant_id=:shop
                """)
                .param("id", requestId).param("orderId", order.id()).param("buyer", order.buyerId())
                .param("shop", order.tenantId()).param("amount", order.totalAmount())
                .param("currency", order.currency()).param("maximum", MAX_ATTEMPTS).update();
        if (inserted != 1) throw new IllegalStateException("Approved case was not scheduled");
    }

    public Optional<AfterSaleExecution> find(String requestId) {
        return jdbc.sql("SELECT * FROM support_after_sale_execution WHERE request_id=:id")
                .param("id", requestId).query((row, ignored) -> view(row)).optional();
    }

    public Map<String, AfterSaleExecution> findAll(Collection<String> requestIds) {
        if (requestIds.isEmpty()) return Map.of();
        return jdbc.sql("SELECT * FROM support_after_sale_execution WHERE request_id IN (:ids)")
                .param("ids", requestIds).query((row, ignored) -> new ViewEntry(row.getString("request_id"), view(row)))
                .list().stream().collect(Collectors.toMap(ViewEntry::id, ViewEntry::view));
    }

    LocalDateTime now() {
        return jdbc.sql("SELECT UTC_TIMESTAMP(3)").query(LocalDateTime.class).single();
    }

    Optional<StoredExecution> lockNext(LocalDateTime now) {
        return jdbc.sql("""
                SELECT * FROM support_after_sale_execution
                WHERE (status='PENDING' AND available_at<=:now)
                   OR (status='FAILED' AND last_error_code='TRANSIENT_FAILURE'
                       AND attempt_count<max_attempts AND available_at<=:now)
                   OR (status='PROCESSING' AND lease_until<=:now)
                ORDER BY available_at, created_at, request_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param("now", now).query((row, ignored) -> stored(row)).optional();
    }

    Optional<StoredExecution> lock(String id) {
        return jdbc.sql("SELECT * FROM support_after_sale_execution WHERE request_id=:id FOR UPDATE")
                .param("id", id).query((row, ignored) -> stored(row)).optional();
    }

    void claim(String id, String token, LocalDateTime leaseUntil) {
        int changed = jdbc.sql("""
                UPDATE support_after_sale_execution SET status='PROCESSING', attempt_count=attempt_count+1,
                  claim_token=:token, lease_until=:lease, last_error_code=NULL, last_error=NULL
                WHERE request_id=:id AND attempt_count<max_attempts
                """).param("id", id).param("token", token).param("lease", leaseUntil).update();
        if (changed != 1) throw new IllegalStateException("Execution claim was not persisted");
    }

    boolean isApproved(StoredExecution task) {
        return jdbc.sql("""
                SELECT id FROM support_after_sale WHERE id=:id AND status='APPROVED'
                  AND order_id=:orderId AND buyer_id=:buyer AND tenant_id=:shop FOR SHARE
                """).param("id", task.requestId()).param("orderId", task.orderId())
                .param("buyer", task.buyerId()).param("shop", task.shopId()).query(String.class).optional().isPresent();
    }

    boolean eligibleOrder(StoredExecution task) {
        return jdbc.sql("""
                SELECT id FROM support_order WHERE id=:id AND buyer_id=:buyer AND tenant_id=:shop
                  AND total_amount=:amount AND currency=:currency
                  AND status IN ('PAID', 'SHIPPED', 'COMPLETED') FOR SHARE
                """).param("id", task.orderId()).param("buyer", task.buyerId()).param("shop", task.shopId())
                .param("amount", task.amount()).param("currency", task.currency())
                .query(Long.class).optional().isPresent();
    }

    void succeed(StoredExecution task, RefundReceipt receipt) {
        int changed = jdbc.sql("""
                UPDATE support_after_sale_execution SET status='SUCCEEDED', reference=:reference,
                  completed_at=:completed, lease_until=NULL, claim_token=NULL, last_error_code=NULL, last_error=NULL
                WHERE request_id=:id AND status='PROCESSING' AND claim_token=:token
                """).param("id", task.requestId()).param("token", task.claimToken())
                .param("reference", receipt.reference())
                .param("completed", LocalDateTime.ofInstant(receipt.completedAt(), ZoneOffset.UTC)).update();
        if (changed != 1) throw new IllegalStateException("Execution claim no longer belongs to this worker");
    }

    void fail(StoredExecution task, ExecutionFailureCode code, String message, LocalDateTime availableAt) {
        jdbc.sql("""
                UPDATE support_after_sale_execution SET status='FAILED', last_error_code=:code,
                  last_error=:message, available_at=:available, lease_until=NULL, claim_token=NULL
                WHERE request_id=:id
                """).param("id", task.requestId()).param("code", code.name()).param("message", message)
                .param("available", availableAt).update();
    }

    boolean isRequester(String id, long buyerId) {
        return jdbc.sql("SELECT id FROM support_after_sale WHERE id=:id AND buyer_id=:buyer")
                .param("id", id).param("buyer", buyerId).query(String.class).optional().isPresent();
    }

    void retry(StoredExecution task, LocalDateTime now) {
        jdbc.sql("""
                UPDATE support_after_sale_execution SET status='PENDING', available_at=:now,
                  last_error_code=NULL, last_error=NULL, lease_until=NULL, claim_token=NULL
                WHERE request_id=:id AND status='FAILED' AND attempt_count<max_attempts
                """).param("id", task.requestId()).param("now", now).update();
    }

    void audit(String id, String actor, String action, String note) {
        jdbc.sql("""
                INSERT INTO support_after_sale_audit (request_id, actor, action, notes)
                VALUES (:id, :actor, :action, :note)
                """).param("id", id).param("actor", actor).param("action", action).param("note", note).update();
    }

    private static StoredExecution stored(ResultSet row) throws SQLException {
        return new StoredExecution(row.getString("request_id"), row.getString("claim_token"),
                row.getLong("order_id"), row.getLong("buyer_id"), row.getLong("tenant_id"),
                row.getBigDecimal("amount"), row.getString("currency"), ExecutionStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"), row.getInt("max_attempts"),
                row.getObject("lease_until", LocalDateTime.class), row.getString("last_error_code"));
    }

    private static AfterSaleExecution view(ResultSet row) throws SQLException {
        ExecutionStatus status = ExecutionStatus.valueOf(row.getString("status"));
        int attempts = row.getInt("attempt_count");
        int maximum = row.getInt("max_attempts");
        boolean retryable = status == ExecutionStatus.FAILED && attempts < maximum
                && ExecutionFailureCode.TRANSIENT_FAILURE.name().equals(row.getString("last_error_code"));
        LocalDateTime next = status == ExecutionStatus.PENDING || retryable
                ? row.getObject("available_at", LocalDateTime.class) : null;
        LocalDateTime completed = row.getObject("completed_at", LocalDateTime.class);
        return new AfterSaleExecution(status, row.getBigDecimal("amount"), row.getString("currency"), attempts, maximum,
                next == null ? null : next.toInstant(ZoneOffset.UTC),
                completed == null ? null : completed.toInstant(ZoneOffset.UTC),
                row.getString("reference"), row.getString("last_error"), retryable);
    }

    private record ViewEntry(String id, AfterSaleExecution view) { }
}
