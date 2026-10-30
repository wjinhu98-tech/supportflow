package dev.supportflow.chat.support;

import dev.supportflow.chat.conversation.ConversationIds;
import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static dev.supportflow.chat.support.HumanSupportModels.*;

@RestController
@RequestMapping("/api/v1/merchant/shops/{shopId}/support-sessions")
public class MerchantHumanSupportController {
    private final HumanSupportService service;
    public MerchantHumanSupportController(HumanSupportService service) { this.service = service; }

    @GetMapping
    public SessionPage queue(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                             @PathVariable @Positive long shopId, @RequestParam(required = false) Status status,
                             @RequestParam(required = false) String beforeId) {
        return service.queue(principal, shopId, status, ConversationIds.parse(beforeId));
    }
    @GetMapping("/{sessionId}")
    public Snapshot snapshot(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                             @PathVariable @Positive long shopId, @PathVariable String sessionId,
                             @RequestParam(required = false) String beforeId) {
        return service.merchantSnapshot(principal, shopId, ConversationIds.parse(sessionId), ConversationIds.parse(beforeId));
    }
    @PostMapping("/{sessionId}/claim")
    public Snapshot claim(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                          @PathVariable @Positive long shopId, @PathVariable String sessionId) {
        return service.claim(principal, shopId, ConversationIds.parse(sessionId));
    }
    @PostMapping("/{sessionId}/messages")
    public MessageView send(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                            @PathVariable @Positive long shopId, @PathVariable String sessionId,
                            @Valid @RequestBody MessageRequest request) {
        return service.merchantSend(principal, shopId, ConversationIds.parse(sessionId), request);
    }
    @PostMapping("/{sessionId}/close")
    public Snapshot close(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                          @PathVariable @Positive long shopId, @PathVariable String sessionId) {
        return service.merchantClose(principal, shopId, ConversationIds.parse(sessionId));
    }
}
