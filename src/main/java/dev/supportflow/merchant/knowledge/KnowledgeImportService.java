package dev.supportflow.merchant.knowledge;

import dev.supportflow.assistant.aftersale.AssistantBusinessException;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.merchant.MerchantAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeImportService {
    private final MerchantAccessService access;
    private final KnowledgeImportFileParser parser;
    private final KnowledgeRepository articles;
    private final KnowledgeImportRepository imports;
    private final ObjectReader json;

    public KnowledgeImportService(MerchantAccessService access, KnowledgeImportFileParser parser,
                                  KnowledgeRepository articles, KnowledgeImportRepository imports, ObjectMapper json) {
        this.access = access; this.parser = parser; this.articles = articles; this.imports = imports;
        this.json = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Transactional(readOnly = true)
    public KnowledgeImportPreview preview(CustomerPrincipal principal, long shopId, MultipartFile file) {
        access.requireKnowledgeShop(principal, shopId);
        var parsed = parser.parse(file);
        return new KnowledgeImportPreview(parsed.title(), parsed.content(), parsed.content().length(), parsed.byteCount());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KnowledgeImportResult confirm(CustomerPrincipal principal, long shopId, MultipartFile file,
                                          String title, String keywordsJson) {
        var authorization = access.lockKnowledgeShop(principal, shopId);
        // Re-parse the actual upload; no browser-supplied preview, body, hash or token authorizes this write.
        var parsed = parser.parse(file);
        KnowledgeDraft draft;
        try { draft = new KnowledgeDraft(title, keywords(keywordsJson), parsed.content()); }
        catch (AssistantBusinessException invalid) { throw KnowledgeImportException.metadata(); }
        String id = articleId(shopId, parsed.hash());
        // The existing mutable article table has UPDATE already. A no-change UPSERT holds an X lock
        // for the stable article UUID, serializing all imports of this normalized shop document.
        // Do not catch duplicate INSERT errors in an outer transaction or grant UPDATE to import identities.
        articles.claimImportDraft(shopId, id, draft);
        var previous = imports.find(shopId, parsed.hash());
        if (previous.isPresent()) {
            if (!previous.get().equals(id) || articles.find(shopId, id, false).isEmpty())
                throw new IllegalStateException("Knowledge import identity is inconsistent");
            return new KnowledgeImportResult(id, true);
        }
        var stored = articles.find(shopId, id, false).orElseThrow();
        // A deterministic ID collision or externally inconsistent row must fail without overwriting content.
        if (stored.status() != KnowledgeStatus.DRAFT || stored.version() != 0 || !stored.draft().equals(draft))
            throw new IllegalStateException("Knowledge import draft identity is inconsistent");
        imports.append(shopId, parsed.hash(), id, principal.getUsername(), authorization.staffId());
        articles.audit(stored, KnowledgeAction.CREATED, principal.getUsername(), authorization.staffId());
        return new KnowledgeImportResult(id, false);
    }

    private List<String> keywords(String input) {
        if (input == null || input.length() > 4096) throw KnowledgeImportException.metadata();
        try {
            var tree = json.readTree(input);
            if (tree == null || !tree.isArray() || tree.isEmpty() || tree.size() > KnowledgeLimits.KEYWORDS)
                throw KnowledgeImportException.metadata();
            var result = new java.util.ArrayList<String>();
            for (var item : tree) {
                if (!item.isString()) throw KnowledgeImportException.metadata();
                result.add(item.asString());
            }
            return List.copyOf(result);
        } catch (AssistantBusinessException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw KnowledgeImportException.metadata(); }
    }

    static String articleId(long shopId, String hash) {
        byte[] bytes = HexFormat.of().parseHex(KnowledgeImportFileParser.hash(
                "supportflow:shop-knowledge-import:UTF8_TEXT_V1:" + shopId + ":" + hash));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80); // UUID version 8: application-defined SHA256 namespace.
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer uuid = ByteBuffer.wrap(bytes);
        return new UUID(uuid.getLong(), uuid.getLong()).toString();
    }
}
