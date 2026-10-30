package dev.supportflow.chat.support;

import dev.supportflow.chat.conversation.ConversationIds;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static dev.supportflow.chat.support.HumanSupportModels.*;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/chat/conversations/{conversationId}/support")
public class BuyerHumanSupportController {
    private final HumanSupportService service;
    public BuyerHumanSupportController(HumanSupportService service) { this.service = service; }

    @GetMapping
    public Snapshot snapshot(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                             @PathVariable @Positive long shopId, @PathVariable String conversationId) {
        return service.buyerSnapshot(principal, shopId, ConversationIds.parse(conversationId));
    }
    @PostMapping
    public Snapshot start(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                          @PathVariable @Positive long shopId, @PathVariable String conversationId,
                          @Valid @RequestBody StartRequest request) {
        return service.start(principal, shopId, ConversationIds.parse(conversationId), request);
    }
    @GetMapping("/messages")
    public MessagePage messages(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                @PathVariable @Positive long shopId, @PathVariable String conversationId,
                                @RequestParam(required = false) String beforeId) {
        return service.buyerMessages(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(beforeId));
    }
    @PostMapping("/{sessionId}/messages")
    public MessageView send(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                            @PathVariable @Positive long shopId, @PathVariable String conversationId,
                            @PathVariable String sessionId, @Valid @RequestBody MessageRequest request) {
        return service.buyerSend(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(sessionId), request);
    }
    @PostMapping("/{sessionId}/close")
    public Snapshot close(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                          @PathVariable @Positive long shopId, @PathVariable String conversationId, @PathVariable String sessionId) {
        return service.buyerClose(principal, shopId, ConversationIds.parse(conversationId), ConversationIds.parse(sessionId));
    }
}
