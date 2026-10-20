package dev.supportflow.chat.api.controller;

import dev.supportflow.chat.api.request.ChatRequest;
import dev.supportflow.chat.api.response.ChatResponse;
import dev.supportflow.chat.application.InvalidChatCommandException;
import dev.supportflow.common.security.model.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import dev.supportflow.chat.application.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/api/v1/chat/welcome")
    public String welcome() {
        return "SupportFlow 已启动，欢迎使用 AI 售后客服助手。";
    }

    @PostMapping("/api/v1/shops/{shopId}/chat/messages")
    public ChatResponse chat(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable("shopId") @Positive(message = "商铺ID必须为正整数") long shopId,
            @Valid @RequestBody ChatRequest request
    ) {
        return ChatResponse.from(chatService.reply(
                principal.getIdentity(), shopId, request.message(), request.orderNo()));
    }

    /** 旧客户端得到明确迁移提示，不能再从无店铺入口执行跨店客服查询。 */
    @Deprecated
    @PostMapping("/api/v1/chat/messages")
    public ChatResponse chatWithoutShop(@Valid @RequestBody ChatRequest request) {
        throw new InvalidChatCommandException("请从店铺页面进入客服");
    }
}
