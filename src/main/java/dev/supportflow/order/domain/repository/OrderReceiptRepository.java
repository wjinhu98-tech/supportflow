package dev.supportflow.order.domain.repository;

import dev.supportflow.order.domain.model.SupportOrder;

import java.time.Instant;
import java.util.Optional;

/** Receipt commands require an enclosing transaction and a current lock on the owned order. */
public interface OrderReceiptRepository {
    Optional<SupportOrder> lockOwnedOrder(long shopId, long buyerId, String orderNo);

    /** Updates only SHIPPED orders without a recorded receipt, using the database UTC clock. */
    boolean confirmReceipt(SupportOrder order);

    /** Append once for a successful first confirmation; failure must roll back the order change. */
    void appendReceiptAudit(SupportOrder order, String actor);

    Instant currentTime();
}
