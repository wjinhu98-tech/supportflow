package dev.supportflow.chat.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.supportflow.order.domain.OrderQueryLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ConversationMessageRequest(
        @NotNull(message = "请提供消息标识") UUID clientMessageId,
        @NotBlank(message = "消息不能为空")
        @Size(max = ConversationLimits.MAX_MESSAGE_LENGTH, message = "消息不能超过1000个字符") String message,
        @Size(max = OrderQueryLimits.MAX_ORDER_NO_LENGTH, message = "订单号不能超过64个字符")
        @Pattern(regexp = "(?U)[^\\s\\p{Cntrl}]+", message = "订单号不能包含空白或控制字符") String orderNo) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static ConversationMessageRequest fromJson(
            @JsonProperty("clientMessageId") String id,
            @JsonProperty("message") String message,
            @JsonProperty("orderNo") String orderNo) {
        return new ConversationMessageRequest(ConversationIds.parse(id), message, orderNo);
    }
}
