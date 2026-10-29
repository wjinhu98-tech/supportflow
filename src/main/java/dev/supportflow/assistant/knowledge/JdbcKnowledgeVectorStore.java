package dev.supportflow.assistant.knowledge;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Model, tokenizer and chunking identity are frozen together; changing any requires a new namespace. */
final class JdbcKnowledgeVectorStore implements KnowledgeVectorStore {
    static final String MODEL_FINGERPRINT = digest("bge-small-zh-v1.5:q8:CLS:L2:wordpiece384:chunk270:overlap32:v1:"
            + OnnxKnowledgeEmbedding.MODEL_SHA256 + ":" + OnnxKnowledgeEmbedding.VOCAB_SHA256);
    private final JdbcClient jdbc;

    JdbcKnowledgeVectorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Integer, float[]> load(SourceKey key) {
        var result = new HashMap<Integer, float[]>();
        jdbc.sql("""
                SELECT chunk_no,dimensions,vector_bytes FROM support_knowledge_vector
                WHERE tenant_id=:shop AND source_id=:source AND source_version=:version
                  AND content_hash=:hash AND model_fingerprint=:model ORDER BY chunk_no LIMIT 128
                """)
                .param("shop", key.shop())
                .param("source", key.source())
                .param("version", key.version())
                .param("hash", key.contentHash())
                .param("model", MODEL_FINGERPRINT)
                .query((row, ignored) -> {
                    if (row.getInt("dimensions") != 512) {
                        throw new IllegalStateException("Invalid stored vector shape");
                    }
                    byte[] bytes = row.getBytes("vector_bytes");
                    if (bytes == null || bytes.length != 2048) {
                        throw new IllegalStateException("Invalid stored vector size");
                    }
                    float[] vector = new float[512];
                    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] = buffer.getFloat();
                    }
                    validate(vector);
                    result.put(row.getInt("chunk_no"), vector);
                    return 0;
                }).list();
        return Map.copyOf(result);
    }

    @Override
    public void save(SourceKey key, int chunk, float[] vector) {
        validate(vector);
        if (chunk < 0 || chunk >= 128) {
            throw new IllegalArgumentException("Invalid chunk number");
        }
        var bytes = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            bytes.putFloat(value);
        }
        // All values are validated before insert. A concurrently persisted identical key is reusable derived data.
        jdbc.sql("""
                INSERT IGNORE INTO support_knowledge_vector
                (tenant_id,source_id,source_version,content_hash,model_fingerprint,chunk_no,dimensions,vector_bytes)
                VALUES (:shop,:source,:version,:hash,:model,:chunk,512,:bytes)
                """)
                .param("shop", key.shop())
                .param("source", key.source())
                .param("version", key.version())
                .param("hash", key.contentHash())
                .param("model", MODEL_FINGERPRINT)
                .param("chunk", chunk)
                .param("bytes", bytes.array())
                .update();
    }

    static void validate(float[] vector) {
        if (vector == null || vector.length != 512) {
            throw new IllegalArgumentException("Invalid persistent vector dimensions");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite vector");
            }
            norm += (double) value * value;
        }
        if (Math.abs(norm - 1) > 0.01) {
            throw new IllegalArgumentException("Persistent vectors must be normalized");
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA256 unavailable");
        }
    }
}
