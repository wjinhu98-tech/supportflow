package dev.supportflow.merchant;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MerchantAccessService {
    private final MerchantAccessRepository repository;

    public MerchantAccessService(MerchantAccessRepository repository) { this.repository = repository; }

    public List<MerchantShop> shops(CustomerPrincipal principal) {
        requireStaff(principal);
        if (!repository.activeStaff(principal.getUsername(), principal.getIdentity().buyerId())) throw denied();
        return repository.list(principal.getUsername(), principal.getIdentity().buyerId());
    }

    public MerchantShopAccess requireShop(CustomerPrincipal principal, long shopId) {
        requireStaff(principal);
        return repository.find(principal.getUsername(), principal.getIdentity().buyerId(), shopId, false)
                .orElseThrow(MerchantAccessService::denied);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MerchantShopAccess lockShop(CustomerPrincipal principal, long shopId) {
        requireStaff(principal);
        return repository.find(principal.getUsername(), principal.getIdentity().buyerId(), shopId, true)
                .orElseThrow(MerchantAccessService::denied);
    }

    private static void requireStaff(CustomerPrincipal principal) {
        if (principal == null || principal.getAccountType() != AccountType.MERCHANT_STAFF
                || !principal.getAuthorities().contains(SecurityPermission.AFTER_SALE_REVIEW)
                || !principal.getAuthorities().contains(SecurityPermission.ORDER_READ)) {
            throw denied();
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Merchant shop authorization required");
    }
}
