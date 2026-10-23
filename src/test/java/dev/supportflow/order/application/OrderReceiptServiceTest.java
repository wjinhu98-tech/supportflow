package dev.supportflow.order.application;

import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.exception.OrderNotFoundException;
import dev.supportflow.order.application.exception.OrderReceiptConflictException;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import dev.supportflow.order.domain.repository.OrderReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderReceiptServiceTest {
    private static final Instant DATABASE_TIME = Instant.parse("2026-10-22T05:06:07.123Z");
    private final CustomerPrincipal buyer = new CustomerPrincipal("receipt-buyer", "unused-test-hash",
            new CustomerIdentity(200), List.of(SecurityPermission.ORDER_READ, SecurityPermission.ORDER_CONFIRM_RECEIPT));
    private OrderReceiptRepository repository;
    private OrderReceiptService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderReceiptRepository.class);
        service = new OrderReceiptService(repository);
    }

    @Test
    void firstConfirmationReturnsPersistedTimestampAndAuditsTheAuthenticatedActor() {
        SupportOrder shipped = order(OrderStatus.SHIPPED, null);
        SupportOrder confirmed = order(OrderStatus.COMPLETED, DATABASE_TIME);
        when(repository.lockOwnedOrder(100, 200, "SF10001"))
                .thenReturn(Optional.of(shipped), Optional.of(confirmed));
        when(repository.confirmReceipt(shipped)).thenReturn(true);
        when(repository.currentTime()).thenReturn(DATABASE_TIME.plusMillis(8));

        var result = service.confirm(buyer, 100, "SF10001");

        assertThat(result.order()).isSameAs(confirmed);
        assertThat(result.order().receivedAt()).isEqualTo(DATABASE_TIME);
        assertThat(result.alreadyConfirmed()).isFalse();
        assertThat(result.checkedAt()).isEqualTo(DATABASE_TIME.plusMillis(8));
        verify(repository).appendReceiptAudit(confirmed, "receipt-buyer");
    }

    @Test
    void repeatedConfirmationPreservesOldReceiptAndDoesNotWriteOrAudit() {
        Instant oldReceipt = Instant.parse("2026-09-01T00:00:00Z");
        SupportOrder existing = order(OrderStatus.COMPLETED, oldReceipt);
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(existing));
        when(repository.currentTime()).thenReturn(DATABASE_TIME);

        var result = service.confirm(buyer, 100, "SF10001");

        assertThat(result.alreadyConfirmed()).isTrue();
        assertThat(result.order().receivedAt()).isEqualTo(oldReceipt);
        assertThat(result.checkedAt()).isEqualTo(DATABASE_TIME);
        verify(repository, never()).confirmReceipt(any());
        verify(repository, never()).appendReceiptAudit(any(), anyString());
    }

    @Test
    void completedOrderWithFutureReceiptRequiresVerificationInsteadOfClaimingConfirmation() {
        when(repository.lockOwnedOrder(100, 200, "SF10001"))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, DATABASE_TIME.plusMillis(1))));
        when(repository.currentTime()).thenReturn(DATABASE_TIME);

        assertThatThrownBy(() -> service.confirm(buyer, 100, "SF10001"))
                .isInstanceOf(OrderReceiptConflictException.class);

        verify(repository, never()).confirmReceipt(any());
        verify(repository, never()).appendReceiptAudit(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAID", "CANCELLED", "COMPLETED"})
    void cannotCreateReceiptForUnsupportedOrHistoricalMissingReceiptState(OrderStatus status) {
        when(repository.lockOwnedOrder(100, 200, "SF10001")).thenReturn(Optional.of(order(status, null)));

        assertThatThrownBy(() -> service.confirm(buyer, 100, "SF10001"))
                .isInstanceOf(OrderReceiptConflictException.class);

        verify(repository, never()).confirmReceipt(any());
        verify(repository, never()).appendReceiptAudit(any(), anyString());
    }

    @Test
    void inconsistentShippedOrderCannotOverwriteAnExistingReceipt() {
        when(repository.lockOwnedOrder(100, 200, "SF10001"))
                .thenReturn(Optional.of(order(OrderStatus.SHIPPED, DATABASE_TIME.minusSeconds(60))));

        assertThatThrownBy(() -> service.confirm(buyer, 100, "SF10001"))
                .isInstanceOf(OrderReceiptConflictException.class);

        verify(repository, never()).confirmReceipt(any());
        verify(repository, never()).appendReceiptAudit(any(), anyString());
    }

    @Test
    void inaccessibleOrderReturnsTheSameNotFoundOutcomeWithoutWriting() {
        when(repository.lockOwnedOrder(300, 200, "SF10001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(buyer, 300, "SF10001"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(repository, never()).confirmReceipt(any());
        verify(repository, never()).appendReceiptAudit(any(), anyString());
    }

    @Test
    void auditFailurePropagatesSoTheTransactionCannotCommitPartialConfirmation() {
        SupportOrder shipped = order(OrderStatus.SHIPPED, null);
        SupportOrder confirmed = order(OrderStatus.COMPLETED, DATABASE_TIME);
        when(repository.lockOwnedOrder(100, 200, "SF10001"))
                .thenReturn(Optional.of(shipped), Optional.of(confirmed));
        when(repository.confirmReceipt(shipped)).thenReturn(true);
        doThrow(new DataIntegrityViolationException("Isolated audit write failure"))
                .when(repository).appendReceiptAudit(confirmed, "receipt-buyer");

        assertThatThrownBy(() -> service.confirm(buyer, 100, "SF10001"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(repository, never()).currentTime();
    }

    private SupportOrder order(OrderStatus status, Instant receivedAt) {
        return new SupportOrder(1001, 100, 200, 200, "SF10001", status,
                new BigDecimal("199.90"), "CNY", DATABASE_TIME.minusSeconds(3600), DATABASE_TIME, receivedAt);
    }
}
