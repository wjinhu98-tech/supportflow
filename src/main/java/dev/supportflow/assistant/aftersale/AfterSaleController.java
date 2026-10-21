package dev.supportflow.assistant.aftersale;

import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class AfterSaleController {
    private final AfterSaleService service;

    public AfterSaleController(AfterSaleService service) { this.service = service; }

    @GetMapping("/api/v1/shops/{shopId}/after-sales")
    public List<AfterSaleTicket> mine(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId) {
        return service.listOwned(principal.getIdentity(), shopId);
    }

    @GetMapping("/api/v1/reviews/after-sales")
    public List<AfterSaleTicket> pending() { return service.pending(); }

    @GetMapping("/api/v1/reviews/after-sales/history")
    public List<AfterSaleTicket> history() { return service.reviewedHistory(); }

    @PostMapping("/api/v1/reviews/after-sales/{id}/execution/retry")
    public AfterSaleTicket retryExecution(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable UUID id) {
        return service.retryExecution(principal, id);
    }

    @PostMapping("/api/v1/reviews/after-sales/{id}/decision")
    public AfterSaleTicket decide(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return service.decide(principal, id, request.decision(), request.note());
    }

    public record DecisionRequest(@NotNull ReviewDecision decision, @NotBlank @Size(max = 500) String note) { }
}
