package dev.supportflow.chat.api.request;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        String message
) {
}