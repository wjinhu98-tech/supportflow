package dev.supportflow.order.application;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.exception.OrderDeletionConflictException;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.domain.model.OrderDeletionTarget;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.repository.OrderDeletionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderDeletionServiceTest {
    private final CustomerPrincipal buyer = new CustomerPrincipal("deletion-buyer", "test-hash",
            new CustomerIdentity(200), List.of(SecurityPermission.ORDER_READ));
    private OrderDeletionRepository repository;
    private OrderDeletionService service;

    @BeforeEach void setup() {
        repository = mock(OrderDeletionRepository.class);
        service = new OrderDeletionService(repository);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"COMPLETED", "CANCELLED"})
    void deletesOnlyEndedOrdersAndUsesAuthenticatedActor(OrderStatus state) {
        var order = order(state);
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order));
        service.delete(buyer, 100, "SF10001");
        verify(repository).appendDeletion(order, "deletion-buyer");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "SHIPPED"})
    void unfinishedOrdersCannotBeHidden(OrderStatus state) {
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order(state)));
        assertThatThrownBy(() -> service.delete(buyer, 100, "SF10001"))
                .isInstanceOf(OrderDeletionConflictException.class).hasMessage("仅已完成或已取消的订单可以删除。");
        verify(repository, never()).hasPendingAfterSale(any());
        verify(repository, never()).appendDeletion(any(), anyString());
    }

    @Test void pendingAfterSaleBlocksDeletion() {
        var order = order(OrderStatus.COMPLETED);
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order));
        when(repository.hasPendingAfterSale(order)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(buyer, 100, "SF10001"))
                .isInstanceOf(OrderDeletionConflictException.class).hasMessage("该订单仍有待处理售后，请处理完成后再删除。");
        verify(repository, never()).appendDeletion(any(), anyString());
    }

    @Test void repeatDoesNotChangeTheFirstDeletionOrReevaluateLaterFacts() {
        var order = order(OrderStatus.COMPLETED);
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order));
        when(repository.isDeleted(order)).thenReturn(true);
        service.delete(buyer, 100, "SF10001");
        verify(repository, never()).hasPendingAfterSale(any());
        verify(repository, never()).appendDeletion(any(), anyString());
    }

    @Test void inaccessibleOrderUsesTheSameNotFoundResult() {
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(buyer, 100, "SF10001")).isInstanceOf(OrderNotFoundException.class);
        verify(repository, never()).appendDeletion(any(), anyString());
    }

    @Test void merchantCannotInvokeTheBuyerServiceEvenWithReadPermission() {
        var merchant = new CustomerPrincipal("staff", "test-hash", new CustomerIdentity(200),
                List.of(SecurityPermission.ORDER_READ), AccountType.MERCHANT_STAFF);
        assertThatThrownBy(() -> service.delete(merchant, 100, "SF10001")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test void invalidArgumentsNeverReachTheRepository() {
        assertThatThrownBy(() -> service.delete(buyer, 0, "SF10001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(buyer, 100, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(buyer, 100, "X".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void failedAuditInsertPropagatesForTransactionRollback() {
        var order = order(OrderStatus.COMPLETED);
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order));
        doThrow(new DataIntegrityViolationException("fixed fixture failure")).when(repository).appendDeletion(order, "deletion-buyer");
        assertThatThrownBy(() -> service.delete(buyer, 100, "SF10001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static OrderDeletionTarget order(OrderStatus state) {
        return new OrderDeletionTarget(1001, 100, 200, "SF10001", state);
    }
}
