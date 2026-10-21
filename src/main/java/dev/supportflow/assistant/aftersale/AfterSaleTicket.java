package dev.supportflow.assistant.aftersale;

import dev.supportflow.assistant.execution.AfterSaleExecution;
import java.time.Instant;

/** Public case view. Internal order/buyer identifiers are not serialized. */
public record AfterSaleTicket(
        String id, long shopId, String orderNo, String reason, AfterSaleStatus status,
        String reviewer, String reviewNote, Instant createdAt, Instant updatedAt,
        AfterSaleExecution execution
) {
    public AfterSaleTicket(String id, long shopId, String orderNo, String reason, AfterSaleStatus status,
                           String reviewer, String reviewNote, Instant createdAt, Instant updatedAt) {
        this(id, shopId, orderNo, reason, status, reviewer, reviewNote, createdAt, updatedAt, null);
    }

    AfterSaleTicket withExecution(AfterSaleExecution progress) {
        return new AfterSaleTicket(id, shopId, orderNo, reason, status, reviewer, reviewNote, createdAt, updatedAt, progress);
    }
}
