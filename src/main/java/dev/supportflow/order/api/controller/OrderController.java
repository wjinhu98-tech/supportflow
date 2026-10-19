package dev.supportflow.order.api.controller;

import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.order.api.response.OrderPageResponse;
import dev.supportflow.order.api.response.OrderResponse;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.application.model.OrderPage;
import dev.supportflow.order.domain.OrderQueryLimits;
import dev.supportflow.order.domain.model.SupportOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 平台买家的跨商铺订单查询入口；买家ID仅取自认证身份。
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderQueryService orderQueryService;

    public OrderController(OrderQueryService orderQueryService) {
        this.orderQueryService = Objects.requireNonNull(
                orderQueryService, "orderQueryService must not be null");
    }

    @GetMapping("/shops/{shopId}/orders/{orderNo}")
    public OrderResponse getOwnedOrder(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            CustomerPrincipal principal,
            @PathVariable("shopId")
            @Positive(message = "商铺ID必须为正数")
            long shopId,
            @PathVariable("orderNo")
            @NotBlank(message = "订单号不能为空")
            @Size(max = 64, message = "订单号不能超过64个字符")
            String orderNo
    ) {
        SupportOrder order = orderQueryService.getOwnedOrder(principal.getIdentity(), shopId, orderNo);
        return OrderResponse.from(order);
    }

    @GetMapping("/orders")
    public OrderPageResponse listOwnedOrders(
            @AuthenticationPrincipal(errorOnInvalidType = true)
            CustomerPrincipal principal,
            @RequestParam(name = "afterId", defaultValue = "0")
            @Min(value = 0, message = "分页游标不能为负数")
            long afterId,
            @RequestParam(name = "size", defaultValue = "" + OrderQueryLimits.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "每页至少返回1条")
            @Max(value = OrderQueryLimits.MAX_PAGE_SIZE,
                    message = "每页最多返回" + OrderQueryLimits.MAX_PAGE_SIZE + "条")
            int size
    ) {
        OrderPage page = orderQueryService.listOwnedOrders(principal.getIdentity(), afterId, size);
        return OrderPageResponse.from(page);
    }
}
