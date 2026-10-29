package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeEvidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped local evidence lookup: no conversation writes, external model calls or business actions. */
@RestController
public class KnowledgeSearchController {
    private final KnowledgeService knowledge;
    public KnowledgeSearchController(KnowledgeService knowledge) { this.knowledge = knowledge; }

    @GetMapping("/api/v1/shops/{shopId}/knowledge/search")
    public KnowledgeEvidence search(@PathVariable @Positive long shopId,
                                    @RequestParam("q") @NotBlank @Size(max = 1000) String query) {
        return knowledge.searchEvidence(shopId, query).evidence();
    }
}
