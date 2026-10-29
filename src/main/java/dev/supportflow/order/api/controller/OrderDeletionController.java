package dev.supportflow.order.api.controller;

import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.application.OrderDeletionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderDeletionController {
    private final OrderDeletionService orders;

    public OrderDeletionController(OrderDeletionService orders) { this.orders = orders; }

    @DeleteMapping("/api/v1/shops/{shopId}/orders/{orderNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                       @PathVariable @Positive long shopId,
                       @PathVariable @NotBlank @Size(max = 64) String orderNo) {
        orders.delete(principal, shopId, orderNo);
    }
}
