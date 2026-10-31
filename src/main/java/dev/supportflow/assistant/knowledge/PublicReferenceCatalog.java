package dev.supportflow.assistant.knowledge;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Curated summaries of public primary sources, scoped explicitly; no live URL fetch and no merchant policy. */
public final class PublicReferenceCatalog {
    private static final Set<String> HOSTS = Set.of("support.microsoft.com", "support.google.com", "support.apple.com",
            "www.ginetex.net", "www.ikea.com");
    private final List<Entry> entries;

    public PublicReferenceCatalog() {
        try (var input = new ClassPathResource("knowledge/references.json").getInputStream()) {
            byte[] bytes = input.readNBytes(100_001);
            if (bytes.length > 100_000) throw new IllegalStateException();
            entries = List.of(new ObjectMapper().readValue(bytes, Entry[].class));
            if (entries.isEmpty() || entries.size() > 32) throw new IllegalStateException();
            var ids = new HashSet<String>();
            for (Entry entry : entries) {
                URI uri = URI.create(entry.url());
                if (!ids.add(entry.id()) || !entry.id().startsWith("ref-") || entry.shops().isEmpty()
                        || entry.shops().stream().anyMatch(shop -> shop == null || shop <= 0)
                        || !"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || entry.summary().length() > 800)
                    throw new IllegalStateException();
                entry.document();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid curated public knowledge catalog");
        }
    }

    List<KnowledgeDocument> forShop(long shop) {
        return entries.stream().filter(entry -> entry.shops().contains(shop)).map(Entry::document).toList();
    }

    /** Prefixes alone do not prove that a caller's text is public: compare the current canonical snapshot. */
    public boolean containsExact(long shop, KnowledgeMatch match) {
        return match != null && forShop(shop).stream().anyMatch(source -> source.sourceId().equals(match.sourceId())
                && source.version().equals(match.version()) && source.title().equals(match.title())
                && source.content().equals(match.content()));
    }

    public java.util.Optional<KnowledgeMatch> find(long shop, String sourceId) {
        return forShop(shop).stream().filter(source -> source.sourceId().equals(sourceId))
                .map(source -> new KnowledgeMatch(source.sourceId(), source.version(), source.title(), source.content(),
                        0, List.of("canonical-public-reference"))).findFirst();
    }

    /** Display only the reviewed answer text; the complete canonical document remains audit evidence. */
    java.util.Optional<String> answerContent(KnowledgeMatch match) {
        return entries.stream().filter(entry -> {
            KnowledgeDocument document = entry.document();
            return document.sourceId().equals(match.sourceId()) && document.version().equals(match.version())
                    && document.title().equals(match.title()) && document.content().equals(match.content());
        }).map(Entry::summary).findFirst();
    }

    record Entry(String id, List<Long> shops, String title, List<String> keywords, String url, String publisher, String summary) {
        Entry { shops = List.copyOf(shops); keywords = List.copyOf(keywords); }
        KnowledgeDocument document() {
            return new KnowledgeDocument(id, "2026-10-28-v1", title, keywords,
                    "【公开使用参考】\n" + summary + "\n\n来源：" + publisher + "\n原文：" + url
                            + "\n核对日期：2026-10-28。此为编辑摘要，不是商家承诺或完整产品说明。");
        }
    }
}
