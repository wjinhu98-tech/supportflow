package dev.supportflow.chat.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import dev.supportflow.order.domain.OrderQueryLimits;

public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 1000, message = "消息不能超过1000个字符")
        String message,
        @Size(max = OrderQueryLimits.MAX_ORDER_NO_LENGTH, message = "订单号不能超过64个字符")
        @Pattern(regexp = "(?U)[^\\s\\p{Cntrl}]+", message = "订单号不能为空或包含空白、控制字符")
        String orderNo
) {
}
