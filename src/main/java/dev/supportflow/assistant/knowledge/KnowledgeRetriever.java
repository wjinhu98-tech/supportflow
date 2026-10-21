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
        String query = normalize(message);
        Set<String> queryTerms = tokenize(query);
        List<KnowledgeMatch> matches = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            int keywordHits = 0;
            double score = 0;
            for (String keyword : document.keywords()) {
                String normalized = normalize(keyword);
                if (query.contains(normalized)) {
                    evidence.add(keyword);
                    keywordHits++;
                    score += 4.0;
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
            if (keywordHits > 0 || lexicalHits >= 3) {
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
