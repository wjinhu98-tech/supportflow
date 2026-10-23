package dev.supportflow.chat.conversation;

import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/chat/conversations")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                               @PathVariable @Positive long shopId) {
        return service.create(principal, shopId);
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                 @PathVariable @Positive long shopId,
                                 @RequestParam(required = false) String beforeId) {
        return service.list(principal, shopId, ConversationIds.parse(beforeId));
    }

    @GetMapping("/{conversationId}/messages")
    public ConversationMessages messages(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable String conversationId,
            @RequestParam(required = false) String beforeId) {
        return service.messages(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(beforeId));
    }

    @PostMapping("/{conversationId}/messages")
    public ConversationTurnResponse send(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable String conversationId,
            @Valid @RequestBody ConversationMessageRequest request) {
        return service.send(principal, shopId, ConversationIds.parse(conversationId), request);
    }
}
