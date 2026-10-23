package dev.supportflow.assistant.aftersale;

import dev.supportflow.assistant.execution.AfterSaleExecutionProcessor;
import dev.supportflow.merchant.MerchantAccessService;
import dev.supportflow.merchant.MerchantShopAccess;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AfterSaleMerchantScopeTest {
    private final AfterSaleRepository repository = mock(AfterSaleRepository.class);
    private final OrderQueryService orders = mock(OrderQueryService.class);
    private final AfterSaleExecutionProcessor executions = mock(AfterSaleExecutionProcessor.class);
    private final AfterSalePolicy policy = mock(AfterSalePolicy.class);
    private final MerchantAccessService access = mock(MerchantAccessService.class);
    private final AfterSaleService service = new AfterSaleService(repository, orders, executions, policy, access);
    private final CustomerPrincipal reviewer = new CustomerPrincipal("reviewer", "unused", new CustomerIdentity(999),
            List.of(SecurityPermission.ORDER_READ, SecurityPermission.AFTER_SALE_REVIEW), AccountType.MERCHANT_STAFF);

    @Test
    void retiredGlobalMethodsCannotBypassShopAuthorization() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(service::pending).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::reviewedHistory).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.decide(reviewer, id, ReviewDecision.REJECT, "review"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.retryExecution(reviewer, id)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository, orders, executions, policy, access);
    }

    @Test
    void queueFiltersShopOnlyAfterAccessCheck() {
        service.pending(reviewer, 100);
        service.reviewedHistory(reviewer, 100);
        var ordered = inOrder(access, repository);
        ordered.verify(access).requireShop(reviewer, 100);
        ordered.verify(repository).pending(100);
        ordered.verify(access).requireShop(reviewer, 100);
        ordered.verify(repository).reviewedHistory(100);
    }

    @Test
    void rejectionLocksGrantAndWritesStableStaffAuditIdentity() {
        UUID id = UUID.randomUUID();
        var ticket = new AfterSaleTicket(id.toString(), 100, "SF1", "reason", AfterSaleStatus.PENDING_REVIEW,
                null, null, Instant.EPOCH, Instant.EPOCH);
        var stored = new StoredAfterSale(ticket, 200, 1, 0);
        when(access.lockShop(reviewer, 100)).thenReturn(new MerchantShopAccess(42, 100));
        when(repository.findById(id.toString(), 100)).thenReturn(Optional.of(stored));
        when(repository.decide(stored, AfterSaleStatus.REJECTED, "reviewer", "reviewed" )).thenReturn(true);
        service.decide(reviewer, 100, id, ReviewDecision.REJECT, " reviewed ");
        var ordered = inOrder(access, repository);
        ordered.verify(access).lockShop(reviewer, 100);
        ordered.verify(repository).findById(id.toString(), 100);
        ordered.verify(repository).decide(stored, AfterSaleStatus.REJECTED, "reviewer", "reviewed");
        ordered.verify(repository).audit(id.toString(), "reviewer", "REJECTED", "reviewed", 42L);
        verifyNoInteractions(orders, executions);
    }

    @Test
    void missingTicketInsideAuthorizedShopCannotFallBackToGlobalLookup() {
        UUID id = UUID.randomUUID();
        when(access.lockShop(reviewer, 100)).thenReturn(new MerchantShopAccess(42, 100));
        when(repository.findById(id.toString(), 100)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.decide(reviewer, 100, id, ReviewDecision.REJECT, "review"))
                .isInstanceOf(AssistantBusinessException.class);
        verify(repository, never()).findById(anyString());
        verifyNoInteractions(orders, executions);
    }
}
