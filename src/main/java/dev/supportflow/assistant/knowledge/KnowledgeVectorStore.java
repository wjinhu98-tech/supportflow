package dev.supportflow.assistant.knowledge;

import java.util.Map;

interface KnowledgeVectorStore {
    Map<Integer, float[]> load(SourceKey key);

    void save(SourceKey key, int chunk, float[] vector);

    record SourceKey(long shop, String source, String version, String contentHash) {
    }
}
