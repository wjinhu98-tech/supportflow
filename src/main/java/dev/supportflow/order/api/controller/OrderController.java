package dev.supportflow.order.api.controller;

import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.api.response.OrderResponse;
import dev.supportflow.order.application.OrderQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 根据当前认证客户的身份范围查询订单。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;

    public OrderController(OrderQueryService orderQueryService) {
        this.orderQueryService = Objects.requireNonNull(
                orderQueryService,
                "orderQueryService must not be null"
        );
    }

    @GetMapping("/{orderNo}")
    public OrderResponse getOwnedOrder(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            CustomerPrincipal principal,
            @PathVariable("orderNo")
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号不能超过64个字符")
            String orderNo
    ) {
        Objects.requireNonNull(principal, "principal must not be null");

        return OrderResponse.from(
                orderQueryService.getOwnedOrder(
                        principal.getIdentity(),
                        orderNo
                )
        );
    }
}
