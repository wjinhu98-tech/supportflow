package dev.supportflow.assistant.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit, versioned lexical aliases, not embeddings or model-generated semantic search. */
final class KnowledgeQueryExpansion {
    static final String VERSION = "lexical-aliases-2026-10-24-v1";

    private static final List<Alias> ALIASES = List.of(
            new Alias("贺卡", List.of("祝福卡", "留言卡", "祝福小卡")),
            new Alias("尺码", List.of("码数", "衣服尺寸", "衣服大小")),
            new Alias("棉质", List.of("纯棉", "全棉")),
            new Alias("陶瓷杯", List.of("陶瓷水杯", "瓷制水杯")),
            new Alias("洗护", List.of("衣服怎么洗", "衣物清洗")));

    private KnowledgeQueryExpansion() { }

    static Expansion expand(String message) {
        String canonical = KnowledgeRetriever.normalize(message);
        Set<String> expandedConcepts = new LinkedHashSet<>();
        // Replace aliases instead of appending them: repeating equivalent words cannot add scores.
        for (Alias alias : ALIASES) {
            boolean found = false;
            for (String variant : alias.variants()) {
                if (canonical.contains(variant)) {
                    canonical = canonical.replace(variant, alias.concept());
                    found = true;
                }
            }
            if (found) expandedConcepts.add(alias.concept());
        }
        Set<String> concepts = new LinkedHashSet<>();
        for (Alias alias : ALIASES) {
            if (canonical.contains(alias.concept())) concepts.add(alias.concept());
        }
        return new Expansion(canonical, Set.copyOf(concepts), Set.copyOf(expandedConcepts));
    }

    /** Reject known category conflicts instead of applying garment/cup advice to another item. */
    static boolean conflicts(String query, String document) {
        return hasUnsupportedCategory(query, document, List.of("玻璃杯", "塑料杯", "保温杯", "不锈钢杯"), "陶瓷杯")
                || hasUnsupportedCategory(query, document, List.of("棉被", "床单", "毛巾"), "衣物")
                || hasUnsupportedCategory(query, document, List.of("真丝", "羊毛", "羊绒", "皮革", "羽绒", "涤纶", "亚麻"), "棉质")
                || hasUnsupportedCategory(query, document, List.of("购物卡", "银行卡", "会员卡"), "贺卡");
    }

    private static boolean hasUnsupportedCategory(String query, String document, List<String> categories,
                                                 String sourceCategory) {
        return document.contains(sourceCategory)
                && categories.stream().anyMatch(category -> query.contains(category) && !document.contains(category));
    }

    record Expansion(String canonicalQuery, Set<String> concepts, Set<String> expandedConcepts) { }
    private record Alias(String concept, List<String> variants) { }
}
