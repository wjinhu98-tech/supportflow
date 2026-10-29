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
import dev.supportflow.chat.conversation.ConversationContextEngine;
import dev.supportflow.chat.conversation.ConversationContextRef;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.assistant.orchestration.ConversationOrchestrator;
import dev.supportflow.assistant.planning.ExplicitOrderNumbers;
import dev.supportflow.chat.application.ProductOrderQuestion;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
public class ChatController {

    private final ConversationContextEngine engine;
    private final ConversationOrchestrator orchestrator;

    public ChatController(ConversationContextEngine engine, ConversationOrchestrator orchestrator) {
        this.engine = engine;
        this.orchestrator = orchestrator;
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
        // Preserve the existing stateless model/command contract; only the new product path needs resolution.
        if (ExplicitOrderNumbers.extract(request.message()).isEmpty()
                && (ProductOrderQuestion.parse(request.message()).isPresent()
                || ProductOrderQuestion.isPublicShippingQuestion(request.message()))) {
            return engine.reply(principal, shopId,
                    new ConversationMessageRequest(UUID.randomUUID(), request.message(), request.orderNo()),
                    ConversationContextRef.none());
        }
        return ChatResponse.from(orchestrator.reply(principal, shopId, request.message(), request.orderNo()));
    }

    /** 旧客户端得到明确迁移提示，不能再从无店铺入口执行跨店客服查询。 */
    @Deprecated
    @PostMapping("/api/v1/chat/messages")
    public ChatResponse chatWithoutShop(@Valid @RequestBody ChatRequest request) {
        throw new InvalidChatCommandException("请从店铺页面进入客服");
    }
}
