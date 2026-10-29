package dev.supportflow.assistant.knowledge;

import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Scoped lexical/dense reciprocal-rank fusion; dense vectors never authorize business operations. */
public final class RagKnowledgeSearch {
    private final RagProperties properties;
    private final KnowledgeEmbedding embedding;
    private final PublicReferenceCatalog references = new PublicReferenceCatalog();
    private final MeterRegistry meters;
    private final Map<Key, float[]> vectors;

    RagKnowledgeSearch(RagProperties properties, KnowledgeEmbedding embedding, MeterRegistry meters) {
        this.properties = properties; this.embedding = embedding; this.meters = meters;
        vectors = new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, float[]> eldest) {
                return size() > properties.cacheEntries();
            }
        };
        meters.gauge("supportflow.rag.cached.chunks", vectors, cache -> { synchronized (cache) { return cache.size(); } });
    }

    public boolean enabled() { return properties.enabled(); }
    boolean publicSelectionEnabled() { return enabled() && properties.publicEvidenceSelectionEnabled(); }

    List<KnowledgeDocument> references(long shopId) {
        return enabled() ? references.forShop(shopId) : List.of();
    }

    List<KnowledgeMatch> search(long shopId, List<KnowledgeDocument> documents, String question) {
        return search(shopId, documents, question, (id, similarity) -> { });
    }

    /** Test/evaluation observer receives source IDs and numeric similarity only, never source contents. */
    List<KnowledgeMatch> search(long shopId, List<KnowledgeDocument> documents, String question,
                                java.util.function.BiConsumer<String, Double> observer) {
        if (question == null || question.isBlank()) return List.of();
        List<KnowledgeMatch> lexical = new KnowledgeRetriever(documents).retrieve(question);
        if (!enabled() || embedding == null) return lexical;
        if (KnowledgeRetriever.normalize(question).isBlank()
                || question.codePoints().noneMatch(Character::isLetterOrDigit)) return List.of();
        // Do not silently truncate a long question into a different semantic request.
        if (question == null || question.isBlank() || question.length() > 300) return fallback(lexical, "long_query");
        long started = System.nanoTime();
        try {
            float[] query = embedding.embed(question, true);
            var dense = new ArrayList<Dense>();
            String normalized = KnowledgeQueryExpansion.expand(question).canonicalQuery();
            for (KnowledgeDocument source : documents) {
                if (KnowledgeQueryExpansion.conflicts(normalized, KnowledgeRetriever.normalize(source.title() + " " + source.content()))) continue;
                double best = -1;
                String fingerprint = fingerprint(source.title() + "\n" + source.content());
                for (KnowledgeChunk chunk : KnowledgeChunk.split(source)) {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > properties.buildDeadlineMillis())
                        return fallback(lexical, "deadline");
                    Key key = new Key(shopId, source.sourceId(), source.version(), fingerprint, chunk.ordinal());
                    float[] vector;
                    synchronized (vectors) { vector = vectors.get(key); }
                    if (vector == null) {
                        vector = embedding.embed(chunk.text(), false);
                        synchronized (vectors) { vectors.put(key, vector); }
                        meters.counter("supportflow.rag.embedding.cache", "result", "miss").increment();
                    } else meters.counter("supportflow.rag.embedding.cache", "result", "hit").increment();
                    best = Math.max(best, cosine(query, vector));
                }
                dense.add(new Dense(source, best));
                observer.accept(source.sourceId(), best);
            }
            dense.sort(Comparator.comparingDouble(Dense::similarity).reversed().thenComparing(value -> value.source().sourceId()));
            boolean semanticHit = !dense.isEmpty() && dense.getFirst().similarity() >= properties.minimumSimilarity()
                    && (lexical.size() > 0 || dense.size() == 1
                    || dense.getFirst().similarity() - dense.get(1).similarity() >= properties.minimumMargin());
            Map<String, Double> ranks = new HashMap<>();
            Map<String, KnowledgeMatch> candidates = new HashMap<>();
            for (int index = 0; index < lexical.size(); index++) {
                KnowledgeMatch match = lexical.get(index);
                candidates.put(match.sourceId(), match); ranks.put(match.sourceId(), 0.7 / (61 + index));
            }
            for (int index = 0; index < Math.min(6, dense.size()); index++) {
                Dense item = dense.get(index); String id = item.source().sourceId();
                if (candidates.containsKey(id)) ranks.merge(id, 0.3 / (61 + index), Double::sum);
            }
            if (semanticHit) {
                Dense item = dense.getFirst(); KnowledgeDocument source = item.source();
                if (!candidates.containsKey(source.sourceId())) {
                    candidates.put(source.sourceId(), new KnowledgeMatch(source.sourceId(), source.version(), source.title(),
                            source.content(), item.similarity(), List.of("dense:bge-small-zh-v1.5")));
                    ranks.put(source.sourceId(), 0.3 / 61);
                }
            }
            List<KnowledgeMatch> result = candidates.values().stream()
                    .sorted(Comparator.<KnowledgeMatch>comparingDouble(match -> ranks.get(match.sourceId())).reversed()
                            .thenComparing(KnowledgeMatch::sourceId)).limit(2).toList();
            meters.counter("supportflow.rag.retrieval", "outcome", result.isEmpty() ? "no_match" : "hybrid").increment();
            return result;
        } catch (RuntimeException unavailable) {
            return fallback(lexical, "embedding_unavailable");
        } finally {
            meters.timer("supportflow.rag.retrieval.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private List<KnowledgeMatch> fallback(List<KnowledgeMatch> lexical, String reason) {
        meters.counter("supportflow.rag.fallback", "reason", reason).increment();
        return lexical;
    }

    static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length)
            throw new IllegalArgumentException("Embedding dimensions differ");
        double dot = 0, a = 0, b = 0;
        for (int index = 0; index < left.length; index++) {
            if (!Float.isFinite(left[index]) || !Float.isFinite(right[index])) throw new IllegalArgumentException("Non-finite embedding");
            dot += left[index] * right[index]; a += left[index] * left[index]; b += right[index] * right[index];
        }
        if (a < 1e-12 || b < 1e-12) throw new IllegalArgumentException("Zero embedding");
        return Math.max(-1, Math.min(1, dot / Math.sqrt(a * b)));
    }

    private static String fingerprint(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private record Key(long shop, String source, String version, String contentHash, int chunk) { }
    private record Dense(KnowledgeDocument source, double similarity) { }
}
