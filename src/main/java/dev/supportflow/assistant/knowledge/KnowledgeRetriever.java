package dev.supportflow.assistant.knowledge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small lexical retriever: domain keywords plus IDF-weighted Chinese bigrams and English words. */
final class KnowledgeRetriever {

    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]+");
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "如何", "可以", "是否", "一下", "这个", "请问", "帮我", "我的", "需要", "订单");
    private static final Set<String> GENERIC_SHOP_KEYWORDS = Set.of(
            "清洁", "保养", "测量", "建议", "说明", "方法", "清洗", "洗涤", "洗护");
    private final List<KnowledgeDocument> documents;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final Map<String, Set<String>> documentTerms = new HashMap<>();

    KnowledgeRetriever(List<KnowledgeDocument> documents) {
        this.documents = List.copyOf(documents);
        for (KnowledgeDocument document : documents) {
            Set<String> terms = tokenize(document.title() + " " + String.join(" ", document.keywords())
                    + " " + document.content());
            documentTerms.put(document.sourceId(), terms);
            terms.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
    }

    List<KnowledgeMatch> retrieve(String message) {
        var expansion = KnowledgeQueryExpansion.expand(message);
        String query = expansion.canonicalQuery();
        Set<String> queryTerms = tokenize(query);
        List<KnowledgeMatch> matches = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            String normalizedContent = normalize(document.title() + " " + document.content());
            boolean shopDocument = document.sourceId().startsWith("kb-") || document.sourceId().startsWith("ref-");
            if (shopDocument && KnowledgeQueryExpansion.conflicts(query, normalizedContent)) continue;
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            int keywordHits = 0;
            double score = 0;
            Set<String> keywordConcepts = new LinkedHashSet<>();
            for (String keyword : document.keywords()) {
                String normalized = KnowledgeQueryExpansion.expand(keyword).canonicalQuery();
                // NFKC can turn non-breaking Unicode spaces into ordinary whitespace. An empty
                // normalized keyword must never act as a universal query.contains("") match.
                if (normalized.isBlank()) continue;
                if (query.contains(normalized) && keywordConcepts.add(normalized)) {
                    evidence.add(keyword);
                    if (!shopDocument || !GENERIC_SHOP_KEYWORDS.contains(normalized)) keywordHits++;
                    score += 4.0;
                }
            }
            // A deliberately configured, non-generic alias may match the source body even when
            // a merchant did not repeat that term in the metadata. Never expand arbitrary words.
            boolean expandedAnchor = false;
            for (String concept : expansion.concepts()) {
                if (!GENERIC_SHOP_KEYWORDS.contains(concept) && normalizedContent.contains(concept)) {
                    expandedAnchor = true;
                    evidence.add(expansion.expandedConcepts().contains(concept)
                            ? "[" + KnowledgeQueryExpansion.VERSION + "]" + concept : concept);
                }
            }
            int lexicalHits = 0;
            for (String term : queryTerms) {
                if (documentTerms.get(document.sourceId()).contains(term)) {
                    lexicalHits++;
                    evidence.add(term);
                    score += Math.log((documents.size() + 1.0) / (documentFrequency.get(term) + 1.0)) + 1.0;
                }
            }
            // A generic one-bigram overlap is not enough evidence to claim an answer.
            // Shop prose needs a concrete subject anchor, not three accidental bigrams or a
            // generic cleaning/advice keyword. The controlled catalog keeps its legacy recall.
            if (keywordHits > 0 || expandedAnchor || (!shopDocument && lexicalHits >= 3)) {
                matches.add(new KnowledgeMatch(document.sourceId(), document.version(), document.title(),
                        document.content(), score, List.copyOf(evidence)));
            }
        }
        matches.sort(Comparator.comparingDouble(KnowledgeMatch::score).reversed()
                .thenComparing(KnowledgeMatch::sourceId));
        if (matches.isEmpty()) return List.of();
        double minimumScore = matches.getFirst().score() * 0.55;
        return matches.stream().filter(match -> match.score() >= minimumScore).limit(2).toList();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    static Set<String> tokenize(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORDS.matcher(normalize(value));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                for (int index = 0; index < points.length - 1; index++) {
                    String term = new String(points, index, 2);
                    if (!STOP_TERMS.contains(term)) terms.add(term);
                }
            } else if (word.length() > 1 && !STOP_TERMS.contains(word)) {
                terms.add(word);
            }
        }
        return Collections.unmodifiableSet(terms);
    }
}
