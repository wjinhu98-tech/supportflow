package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.knowledge.KnowledgeIndexStatus;
import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantAccessService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Index maintenance cannot publish drafts, change business rules or invoke an external model. */
@RestController
@RequestMapping("/api/v1/merchant/shops/{shopId}/knowledge/index")
public class KnowledgeIndexController {
    private final MerchantAccessService access;
    private final KnowledgeService knowledge;

    public KnowledgeIndexController(MerchantAccessService access, KnowledgeService knowledge) {
        this.access = access;
        this.knowledge = knowledge;
    }

    @GetMapping
    public KnowledgeIndexStatus status(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                       @PathVariable @Positive long shopId) {
        access.requireKnowledgeShop(principal, shopId);
        return knowledge.index(shopId, false);
    }

    @PostMapping("/warmup")
    public KnowledgeIndexStatus warmup(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                       @PathVariable @Positive long shopId) {
        access.requireKnowledgeShop(principal, shopId);
        return knowledge.index(shopId, true);
    }
}
