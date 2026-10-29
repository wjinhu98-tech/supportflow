package dev.supportflow.order.application;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.exception.OrderDeletionConflictException;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.repository.OrderDeletionRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Buyer deletion appends a visibility/audit record; it never deletes an order or its related facts. */
@Service
public class OrderDeletionService {
    private final OrderDeletionRepository repository;

    public OrderDeletionService(OrderDeletionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasAuthority('order:read')")
    public void delete(CustomerPrincipal principal, long shopId, String orderNo) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (principal.getAccountType() != AccountType.BUYER) throw new AccessDeniedException("Buyer account required");
        if (shopId <= 0 || orderNo == null || orderNo.isBlank() || orderNo.length() > 64)
            throw new IllegalArgumentException("Invalid shop or order number");
        var order = repository.lockOwnedOrder(shopId, principal.getIdentity().buyerId(), orderNo)
                .orElseThrow(OrderNotFoundException::new);
        // A retry by this buyer returns the original result without changing the first actor/time.
        if (repository.isDeleted(order)) return;
        if (order.status() != OrderStatus.COMPLETED && order.status() != OrderStatus.CANCELLED)
            throw OrderDeletionConflictException.unfinishedOrder();
        if (repository.hasPendingAfterSale(order)) throw OrderDeletionConflictException.pendingAfterSale();
        repository.appendDeletion(order, principal.getUsername());
    }
}
