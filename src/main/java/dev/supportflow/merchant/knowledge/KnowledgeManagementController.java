package dev.supportflow.merchant.knowledge;

import dev.supportflow.common.security.model.CustomerPrincipal;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/shops/{shopId}/knowledge")
public class KnowledgeManagementController {
    private final KnowledgeManagementService service;

    public KnowledgeManagementController(KnowledgeManagementService service) { this.service = service; }

    @PostMapping
    public KnowledgeArticle create(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                   @PathVariable @Positive long shopId, @Valid @RequestBody CreateRequest request) {
        return service.create(principal, shopId, new KnowledgeDraft(request.title(), request.keywords(), request.content()));
    }

    @GetMapping
    public KnowledgePage<KnowledgeArticle> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @RequestParam(required = false) UUID beforeId) {
        return service.list(principal, shopId, beforeId);
    }

    @GetMapping("/{id}")
    public KnowledgeArticle get(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                @PathVariable @Positive long shopId, @PathVariable UUID id) {
        return service.get(principal, shopId, id);
    }

    @PutMapping("/{id}")
    public KnowledgeArticle save(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                 @PathVariable @Positive long shopId, @PathVariable UUID id,
                                 @Valid @RequestBody SaveRequest request) {
        return service.save(principal, shopId, id, request.expectedVersion(),
                new KnowledgeDraft(request.title(), request.keywords(), request.content()));
    }

    @PostMapping("/{id}/publish")
    public KnowledgeArticle publish(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                    @PathVariable @Positive long shopId, @PathVariable UUID id,
                                    @Valid @RequestBody VersionRequest request) {
        return service.publish(principal, shopId, id, request.expectedVersion());
    }

    @PostMapping("/{id}/offline")
    public KnowledgeArticle offline(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                    @PathVariable @Positive long shopId, @PathVariable UUID id,
                                    @Valid @RequestBody VersionRequest request) {
        return service.offline(principal, shopId, id, request.expectedVersion());
    }

    @GetMapping("/{id}/audit")
    public KnowledgePage<KnowledgeAudit> audit(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive long shopId, @PathVariable UUID id,
            @RequestParam(required = false) @Positive Long beforeId) {
        return service.audit(principal, shopId, id, beforeId);
    }

    public record CreateRequest(@NotBlank @Size(max = KnowledgeLimits.TITLE) String title,
                                @NotNull @Size(min = 1, max = KnowledgeLimits.KEYWORDS)
                                List<@NotBlank @Size(max = KnowledgeLimits.KEYWORD_LENGTH) String> keywords,
                                @NotBlank @Size(max = KnowledgeLimits.CONTENT) String content) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode ignored) { throw KnowledgeException.invalid(); }
    }

    public record SaveRequest(@NotNull @PositiveOrZero @Max(KnowledgeLimits.MAX_VERSION - 1) Integer expectedVersion,
                              @NotBlank @Size(max = KnowledgeLimits.TITLE) String title,
                              @NotNull @Size(min = 1, max = KnowledgeLimits.KEYWORDS)
                              List<@NotBlank @Size(max = KnowledgeLimits.KEYWORD_LENGTH) String> keywords,
                              @NotBlank @Size(max = KnowledgeLimits.CONTENT) String content) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode ignored) { throw KnowledgeException.invalid(); }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public static SaveRequest fromJson(@JsonProperty("expectedVersion") JsonNode expectedVersion,
                                            @JsonProperty("title") String title,
                                            @JsonProperty("keywords") List<String> keywords,
                                            @JsonProperty("content") String content) {
            return new SaveRequest(strictVersion(expectedVersion), title, keywords, content);
        }
    }

    public record VersionRequest(@NotNull @PositiveOrZero @Max(KnowledgeLimits.MAX_VERSION - 1) Integer expectedVersion) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode ignored) { throw KnowledgeException.invalid(); }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public static VersionRequest fromJson(@JsonProperty("expectedVersion") JsonNode expectedVersion) {
            return new VersionRequest(strictVersion(expectedVersion));
        }
    }

    private static int strictVersion(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() >= KnowledgeLimits.MAX_VERSION) {
            throw KnowledgeException.invalid();
        }
        return value.intValue();
    }
}
