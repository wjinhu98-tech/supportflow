package dev.supportflow.merchant.knowledge;

import dev.supportflow.common.security.model.CustomerPrincipal;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/merchant/shops/{shopId}/knowledge/imports")
public class KnowledgeImportController {
    private final KnowledgeImportService imports;

    public KnowledgeImportController(KnowledgeImportService imports) { this.imports = imports; }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeImportPreview preview(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                           @PathVariable @Positive long shopId, @RequestParam MultipartFile file,
                                           MultipartHttpServletRequest request) {
        checkFields(request, Set.of("_csrf"));
        return imports.preview(principal, shopId, file);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeImportResult confirm(@AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
                                          @PathVariable @Positive long shopId, @RequestParam MultipartFile file,
                                          @RequestParam String title, @RequestParam String keywords,
                                          MultipartHttpServletRequest request) {
        checkFields(request, Set.of("_csrf", "title", "keywords"));
        return imports.confirm(principal, shopId, file, title, keywords);
    }

    private static void checkFields(MultipartHttpServletRequest request, Set<String> allowed) {
        var files = request.getMultiFileMap();
        if (files.size() != 1 || !files.containsKey("file") || files.get("file").size() != 1
                || request.getParameterMap().entrySet().stream().anyMatch(entry ->
                !allowed.contains(entry.getKey()) || entry.getValue().length != 1)) throw KnowledgeImportException.fields();
    }
}
