package dev.supportflow.chat.conversation;

import dev.supportflow.assistant.runtime.AgentRunView;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/chat/conversations/{conversationId}/runs/{clientMessageId}")
public class AgentRunController {
    private final AgentRunConversationService service;

    public AgentRunController(AgentRunConversationService service) { this.service = service; }

    @GetMapping
    public AgentRunView view(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                             @PathVariable @Positive long shopId, @PathVariable String conversationId,
                             @PathVariable String clientMessageId) {
        return service.view(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(clientMessageId));
    }

    @PostMapping("/resume")
    public ConversationTurnResponse resume(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                           @PathVariable @Positive long shopId, @PathVariable String conversationId,
                                           @PathVariable String clientMessageId) {
        return service.resume(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(clientMessageId));
    }
}
