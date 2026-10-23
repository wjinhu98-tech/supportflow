package dev.supportflow.merchant;

import dev.supportflow.assistant.aftersale.AfterSaleController.DecisionRequest;
import dev.supportflow.assistant.aftersale.AfterSaleService;
import dev.supportflow.assistant.aftersale.AfterSaleTicket;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant")
public class MerchantController {
    private final MerchantAccessService access;
    private final AfterSaleService afterSales;

    public MerchantController(MerchantAccessService access, AfterSaleService afterSales) {
        this.access = access;
        this.afterSales = afterSales;
    }

    @GetMapping("/shops")
    public List<MerchantShop> shops(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return access.shops(principal);
    }

    @GetMapping("/shops/{shopId}/after-sales")
    public List<AfterSaleTicket> pending(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId) {
        return afterSales.pending(principal, shopId);
    }

    @GetMapping("/shops/{shopId}/after-sales/history")
    public List<AfterSaleTicket> history(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId) {
        return afterSales.reviewedHistory(principal, shopId);
    }

    @PostMapping("/shops/{shopId}/after-sales/{id}/decision")
    public AfterSaleTicket decide(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest request) {
        return afterSales.decide(principal, shopId, id, request.decision(), request.note());
    }

    @PostMapping("/shops/{shopId}/after-sales/{id}/execution/retry")
    public AfterSaleTicket retry(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable UUID id) {
        return afterSales.retryExecution(principal, shopId, id);
    }
}
