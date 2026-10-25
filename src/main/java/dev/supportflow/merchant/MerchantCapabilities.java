package dev.supportflow.merchant;

import dev.supportflow.common.security.model.AccountType;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;

/** Account capabilities are hints for UI modules, never a replacement for live shop authorization. */
public record MerchantCapabilities(boolean reviewAfterSales, boolean manageKnowledge) {
    public static MerchantCapabilities from(CustomerPrincipal principal) {
        if (principal == null || principal.getAccountType() != AccountType.MERCHANT_STAFF) {
            return new MerchantCapabilities(false, false);
        }
        return new MerchantCapabilities(principal.getAuthorities().contains(SecurityPermission.ORDER_READ)
                && principal.getAuthorities().contains(SecurityPermission.AFTER_SALE_REVIEW),
                principal.getAuthorities().contains(SecurityPermission.KNOWLEDGE_MANAGE));
    }

    boolean any() { return reviewAfterSales || manageKnowledge; }
}
