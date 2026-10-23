package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.application.exception.OrderReceiptConflictException;
import dev.supportflow.order.application.model.OrderReceiptResult;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.OrderReceiptRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** Confirms an owned receipt atomically. Historical receipt dates are never reconstructed. */
@Service
public class OrderReceiptService {
    private final OrderReceiptRepository repository;

    public OrderReceiptService(OrderReceiptRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional
    @PreAuthorize("hasAuthority('order:read') and hasAuthority('order:confirm-receipt')")
    public OrderReceiptResult confirm(CustomerPrincipal principal, long shopId, String orderNo) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (shopId <= 0 || orderNo == null || orderNo.isBlank() || orderNo.length() > 64) {
            throw new IllegalArgumentException("Invalid shop or order number");
        }
        long buyerId = principal.getIdentity().buyerId();
        SupportOrder current = repository.lockOwnedOrder(shopId, buyerId, orderNo)
                .orElseThrow(OrderNotFoundException::new);
        if (current.status() == OrderStatus.COMPLETED && current.receivedAt() != null) {
            Instant checkedAt = repository.currentTime();
            if (current.receivedAt().isAfter(checkedAt)) throw new OrderReceiptConflictException();
            return new OrderReceiptResult(current, true, checkedAt);
        }
        if (current.status() != OrderStatus.SHIPPED || current.receivedAt() != null) {
            throw new OrderReceiptConflictException();
        }
        if (!repository.confirmReceipt(current)) throw new OrderReceiptConflictException();
        // Re-read the persisted millisecond precision value; do not substitute a JVM timestamp.
        SupportOrder confirmed = repository.lockOwnedOrder(shopId, buyerId, orderNo)
                .orElseThrow(OrderNotFoundException::new);
        if (confirmed.id() != current.id() || confirmed.status() != OrderStatus.COMPLETED
                || confirmed.receivedAt() == null) {
            throw new IllegalStateException("Confirmed receipt was not persisted consistently");
        }
        repository.appendReceiptAudit(confirmed, principal.getUsername());
        return new OrderReceiptResult(confirmed, false, repository.currentTime());
    }
}
