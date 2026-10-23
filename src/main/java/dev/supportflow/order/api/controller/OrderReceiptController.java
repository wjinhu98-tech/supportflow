package dev.supportflow.order.api.controller;

import dev.supportflow.assistant.aftersale.AfterSalePolicy;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.api.response.OrderReceiptConfirmationResponse;
import dev.supportflow.order.api.response.OrderResponse;
import dev.supportflow.order.application.OrderReceiptService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderReceiptController {
    private final OrderReceiptService receipts;
    private final AfterSalePolicy afterSalePolicy;

    public OrderReceiptController(OrderReceiptService receipts, AfterSalePolicy afterSalePolicy) {
        this.receipts = receipts;
        this.afterSalePolicy = afterSalePolicy;
    }

    @PostMapping("/api/v1/shops/{shopId}/orders/{orderNo}/receipt-confirmation")
    public OrderReceiptConfirmationResponse confirm(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId,
            @PathVariable @NotBlank @Size(max = 64) String orderNo) {
        var result = receipts.confirm(principal, shopId, orderNo);
        return new OrderReceiptConfirmationResponse(OrderResponse.from(result.order()), result.alreadyConfirmed(),
                afterSalePolicy.evaluate(result.order(), result.checkedAt()));
    }
}
