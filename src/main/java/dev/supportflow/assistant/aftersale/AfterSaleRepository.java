package dev.supportflow.assistant.aftersale;

import dev.supportflow.assistant.execution.AfterSaleExecutionRepository;
import dev.supportflow.order.domain.model.SupportOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AfterSaleRepository {
    private static final String COLUMNS = "id, tenant_id, order_id, buyer_id, order_no, reason, status, "
            + "reviewer, review_note, created_at, updated_at, version, eligibility_status, "
            + "eligibility_can_submit, eligibility_message, eligibility_received_at, eligibility_deadline_at, "
            + "eligibility_checked_at, eligibility_window_days";
    private final JdbcClient jdbc;
    private final AfterSaleExecutionRepository executions;

    public AfterSaleRepository(JdbcClient jdbc, AfterSaleExecutionRepository executions) {
        this.jdbc = jdbc;
        this.executions = executions;
    }

    void insert(String id, String key, SupportOrder order, String reason, AfterSaleEligibility eligibility) {
            jdbc.sql("""
                    INSERT INTO support_after_sale
                      (id, request_key, buyer_id, tenant_id, order_id, order_no, reason, status,
                       eligibility_status, eligibility_can_submit, eligibility_message, eligibility_received_at,
                       eligibility_deadline_at, eligibility_checked_at, eligibility_window_days)
                    VALUES (:id, :key, :buyer, :shop, :orderId, :orderNo, :reason, 'PENDING_REVIEW',
                            :eligibilityStatus, :canSubmit, :message, :receivedAt, :deadlineAt, :checkedAt, :windowDays)
                    ON DUPLICATE KEY UPDATE id=id
                    """)
                    .param("id", id).param("key", key).param("buyer", order.buyerId())
                    .param("shop", order.tenantId()).param("orderId", order.id())
                    .param("orderNo", order.orderNo()).param("reason", reason)
                    .param("eligibilityStatus", eligibility.status().name()).param("canSubmit", eligibility.canSubmit())
                    .param("message", eligibility.message())
                    .param("receivedAt", utc(eligibility.receivedAt()), Types.TIMESTAMP)
                    .param("deadlineAt", utc(eligibility.deadlineAt()), Types.TIMESTAMP)
                    .param("checkedAt", utc(eligibility.checkedAt()), Types.TIMESTAMP)
                    .param("windowDays", eligibility.windowDays()).update();
    }

    /** A current shared read prevents order changes until the surrounding transaction commits. */
    Optional<SupportOrder> lockOwnedOrder(SupportOrder order) {
        return jdbc.sql("""
                SELECT id, tenant_id, customer_id, buyer_id, order_no, status,
                       total_amount, currency, created_at, updated_at, received_at FROM support_order
                WHERE id=:id AND tenant_id=:shop AND buyer_id=:buyer AND order_no=:orderNo
                FOR SHARE
                """)
                .param("id", order.id()).param("shop", order.tenantId()).param("buyer", order.buyerId())
                .param("orderNo", order.orderNo()).query((row, ignored) -> new SupportOrder(
                        row.getLong("id"), row.getLong("tenant_id"), row.getLong("customer_id"),
                        row.getLong("buyer_id"), row.getString("order_no"),
                        dev.supportflow.order.domain.model.OrderStatus.fromCode(row.getString("status")),
                        row.getBigDecimal("total_amount"), row.getString("currency"),
                        instant(row, "created_at"), instant(row, "updated_at"), instant(row, "received_at")))
                .optional();
    }

    /** Use the database UTC clock so a browser timestamp or a different JVM timezone cannot extend eligibility. */
    Instant now() {
        return jdbc.sql("SELECT UTC_TIMESTAMP(3)").query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC);
    }

    /** No gap lock on a missing request key: racing new submissions still serialize at the existing UPSERT. */
    Optional<StoredAfterSale> findExistingByKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale WHERE request_key=:key")
                .param("key", key).query(AfterSaleRepository::map).optional().map(this::enrich);
    }

    Optional<StoredAfterSale> findByKey(String key) {
        // Current/locking read sees a concurrently committed duplicate even under REPEATABLE READ.
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale WHERE request_key=:key FOR UPDATE")
                .param("key", key).query(AfterSaleRepository::map).optional().map(this::enrich);
    }

    Optional<StoredAfterSale> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale WHERE id=:id")
                .param("id", id).query(AfterSaleRepository::map).optional().map(this::enrich);
    }

    List<AfterSaleTicket> listOwned(long shopId, long buyerId) {
        return enrich(jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale "
                        + "WHERE tenant_id=:shop AND buyer_id=:buyer ORDER BY created_at DESC, id DESC LIMIT 50")
                .param("shop", shopId).param("buyer", buyerId)
                .query(AfterSaleRepository::map).list().stream().map(StoredAfterSale::ticket).toList());
    }

    List<AfterSaleTicket> pending() {
        return enrich(jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale "
                        + "WHERE status='PENDING_REVIEW' ORDER BY created_at, id LIMIT 50")
                .query(AfterSaleRepository::map).list().stream().map(StoredAfterSale::ticket).toList());
    }

    List<AfterSaleTicket> reviewedHistory() {
        return enrich(jdbc.sql("SELECT " + COLUMNS + " FROM support_after_sale "
                        + "ORDER BY updated_at DESC, id DESC LIMIT 50")
                .query(AfterSaleRepository::map).list().stream().map(StoredAfterSale::ticket).toList());
    }

    private StoredAfterSale enrich(StoredAfterSale row) {
        return new StoredAfterSale(row.ticket().withExecution(executions.find(row.ticket().id()).orElse(null)),
                row.buyerId(), row.orderId(), row.version());
    }

    private List<AfterSaleTicket> enrich(List<AfterSaleTicket> tickets) {
        var progress = executions.findAll(tickets.stream().map(AfterSaleTicket::id).toList());
        return tickets.stream().map(ticket -> ticket.withExecution(progress.get(ticket.id()))).toList();
    }

    boolean decide(StoredAfterSale record, AfterSaleStatus status, String reviewer, String note) {
        return jdbc.sql("""
                        UPDATE support_after_sale
                        SET status=:status, reviewer=:reviewer, review_note=:note, version=version+1
                        WHERE id=:id AND status='PENDING_REVIEW' AND version=:version
                        """)
                .param("status", status.name()).param("reviewer", reviewer).param("note", note)
                .param("id", record.ticket().id()).param("version", record.version()).update() == 1;
    }

    void audit(String id, String actor, String action, String notes) {
        jdbc.sql("""
                        INSERT INTO support_after_sale_audit (request_id, actor, action, notes)
                        VALUES (:id, :actor, :action, :notes)
                        """)
                .param("id", id).param("actor", actor).param("action", action).param("notes", notes).update();
    }

    private static StoredAfterSale map(ResultSet row, int ignored) throws SQLException {
        AfterSaleTicket view = new AfterSaleTicket(
                row.getString("id"), row.getLong("tenant_id"), row.getString("order_no"),
                row.getString("reason"), AfterSaleStatus.valueOf(row.getString("status")),
                row.getString("reviewer"), row.getString("review_note"),
                row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                null, eligibility(row));
        return new StoredAfterSale(view, row.getLong("buyer_id"), row.getLong("order_id"), row.getLong("version"));
    }

    private static AfterSaleEligibility eligibility(ResultSet row) throws SQLException {
        String status = row.getString("eligibility_status");
        if (status == null) return null;
        return new AfterSaleEligibility(row.getLong("tenant_id"), row.getString("order_no"),
                AfterSaleEligibilityStatus.valueOf(status), row.getBoolean("eligibility_can_submit"),
                row.getString("eligibility_message"), instant(row, "eligibility_received_at"),
                instant(row, "eligibility_deadline_at"), instant(row, "eligibility_checked_at"),
                row.getInt("eligibility_window_days"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
