package dev.supportflow.merchant;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MerchantAccessServiceTest {
    private final MerchantAccessRepository repository = mock(MerchantAccessRepository.class);
    private final MerchantAccessService service = new MerchantAccessService(repository);
    private final CustomerPrincipal staff = principal(AccountType.MERCHANT_STAFF,
            SecurityPermission.ORDER_READ, SecurityPermission.AFTER_SALE_REVIEW);

    @Test
    void buyerDoesNotBecomeStaffByHavingReviewPermission() {
        var buyer = principal(AccountType.BUYER, SecurityPermission.ORDER_READ, SecurityPermission.AFTER_SALE_REVIEW);
        assertThatThrownBy(() -> service.requireShop(buyer, 100)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.shops(buyer)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void staffNeedsBothReadAndReviewPermissions() {
        for (var permissions : List.of(List.<SecurityPermission>of(), List.of(SecurityPermission.ORDER_READ),
                List.of(SecurityPermission.AFTER_SALE_REVIEW))) {
            var incomplete = principal(AccountType.MERCHANT_STAFF, permissions.toArray(SecurityPermission[]::new));
            assertThatThrownBy(() -> service.lockShop(incomplete, 100)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void noPrincipalFailsClosed() {
        assertThatThrownBy(() -> service.requireShop(null, 100)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void missingOrDisabledStaffBindingCannotListShops() {
        when(repository.activeStaff("staff", 999)).thenReturn(false);
        assertThatThrownBy(() -> service.shops(staff)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).list(anyString(), anyLong());
    }

    @Test
    void activeStaffMayHaveNoShopGrants() {
        when(repository.activeStaff("staff", 999)).thenReturn(true);
        when(repository.list("staff", 999)).thenReturn(List.of());
        assertThat(service.shops(staff)).isEmpty();
    }

    @Test
    void shopListUsesAuthenticatedUsernameAndLinkedBuyerId() {
        var shop = new MerchantShop(100, "Shop", 1, "Merchant");
        when(repository.activeStaff("staff", 999)).thenReturn(true);
        when(repository.list("staff", 999)).thenReturn(List.of(shop));
        assertThat(service.shops(staff)).containsExactly(shop);
    }

    @Test
    void readAuthorizationIsRecheckedRatherThanCached() {
        var access = new MerchantShopAccess(7, 100);
        when(repository.find("staff", 999, 100, false)).thenReturn(Optional.of(access)).thenReturn(Optional.empty());
        assertThat(service.requireShop(staff, 100)).isEqualTo(access);
        assertThatThrownBy(() -> service.requireShop(staff, 100)).isInstanceOf(AccessDeniedException.class);
        verify(repository, times(2)).find("staff", 999, 100, false);
    }

    @Test
    void writeAuthorizationUsesCurrentSharedLock() {
        var access = new MerchantShopAccess(7, 100);
        when(repository.find("staff", 999, 100, true)).thenReturn(Optional.of(access));
        assertThat(service.lockShop(staff, 100)).isEqualTo(access);
        verify(repository).find("staff", 999, 100, true);
    }

    @Test
    void anUnrelatedShopIsDeniedWithoutFallback() {
        when(repository.find("staff", 999, 101, true)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lockShop(staff, 101)).isInstanceOf(AccessDeniedException.class);
        verify(repository).find("staff", 999, 101, true);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void knowledgeOnlyStaffMayListAssignedShopsButCannotReviewAfterSales() {
        var editor = principal(AccountType.MERCHANT_STAFF, SecurityPermission.KNOWLEDGE_MANAGE);
        when(repository.activeStaff("staff", 999)).thenReturn(true);
        when(repository.list("staff", 999)).thenReturn(List.of(new MerchantShop(100, "Shop", 1, "Merchant")));
        assertThat(service.shops(editor)).hasSize(1);
        assertThatThrownBy(() -> service.requireShop(editor, 100)).isInstanceOf(AccessDeniedException.class);
        assertThat(MerchantCapabilities.from(editor)).isEqualTo(new MerchantCapabilities(false, true));
    }

    @Test
    void knowledgeAuthorizationIsIndependentAndStillRequiresLiveShopGrant() {
        var editor = principal(AccountType.MERCHANT_STAFF, SecurityPermission.KNOWLEDGE_MANAGE);
        var authorization = new MerchantShopAccess(7, 100);
        when(repository.find("staff", 999, 100, false)).thenReturn(Optional.of(authorization)).thenReturn(Optional.empty());
        when(repository.find("staff", 999, 100, true)).thenReturn(Optional.of(authorization));
        assertThat(service.requireKnowledgeShop(editor, 100)).isEqualTo(authorization);
        assertThat(service.lockKnowledgeShop(editor, 100)).isEqualTo(authorization);
        assertThatThrownBy(() -> service.requireKnowledgeShop(editor, 100)).isInstanceOf(AccessDeniedException.class);
        verify(repository, times(2)).find("staff", 999, 100, false);
    }

    @Test
    void buyerWithKnowledgePermissionAndReviewOnlyStaffCannotManageKnowledge() {
        var buyer = principal(AccountType.BUYER, SecurityPermission.KNOWLEDGE_MANAGE);
        assertThatThrownBy(() -> service.requireKnowledgeShop(buyer, 100)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.lockKnowledgeShop(staff, 100)).isInstanceOf(AccessDeniedException.class);
        assertThat(MerchantCapabilities.from(buyer)).isEqualTo(new MerchantCapabilities(false, false));
        assertThat(MerchantCapabilities.from(staff)).isEqualTo(new MerchantCapabilities(true, false));
        verifyNoInteractions(repository);
    }

    private static CustomerPrincipal principal(AccountType type, SecurityPermission... permissions) {
        return new CustomerPrincipal("staff", "unused", new CustomerIdentity(999), List.of(permissions), type);
    }
}
