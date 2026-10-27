package dev.supportflow.chat.conversation;

import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/chat/window")
public class ShopConversationController {
    private final ConversationService service;
    public ShopConversationController(ConversationService service) { this.service = service; }

    @PostMapping
    public Conversation open(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                             @PathVariable @Positive long shopId) {
        return service.openWindow(principal, shopId);
    }

    @GetMapping
    public ShopConversationWindow history(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                          @PathVariable @Positive long shopId,
                                          @RequestParam(required = false) String beforeId) {
        return service.window(principal, shopId, ConversationIds.parse(beforeId));
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                       @PathVariable @Positive long shopId, @PathVariable String conversationId) {
        service.deleteWindow(principal, shopId, ConversationIds.parse(conversationId));
    }
}
