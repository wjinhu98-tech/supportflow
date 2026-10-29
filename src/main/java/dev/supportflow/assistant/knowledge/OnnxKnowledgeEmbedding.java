package dev.supportflow.assistant.knowledge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Local quantized BGE-small-zh-v1.5, CLS pooling and L2 normalization, CPU only; never downloads at runtime. */
final class OnnxKnowledgeEmbedding implements KnowledgeEmbedding {
    static final String MODEL_SHA256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc";
    static final String VOCAB_SHA256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c";
    static final int DIMENSIONS = 512;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final BertWordPieceTokenizer tokenizer;
    private final Semaphore slots = new Semaphore(2);

    OnnxKnowledgeEmbedding(Path directory) {
        try {
            Path model = directory.resolve("model_quantized.onnx"), vocabulary = directory.resolve("vocab.txt");
            verify(model, MODEL_SHA256, 24_010_842);
            verify(vocabulary, VOCAB_SHA256, 109_540);
            tokenizer = new BertWordPieceTokenizer(Files.readAllLines(vocabulary));
            try (var options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);
                options.setInterOpNumThreads(1);
                session = environment.createSession(model.toString(), options);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Local RAG model unavailable or checksum invalid; run scripts/setup-rag-model.ps1");
        }
    }

    @Override public float[] embed(String text, boolean query) {
        if (!slots.tryAcquire()) throw new IllegalStateException("Local embedding capacity exhausted");
        try {
            long[] ids = tokenizer.encode((query ? "为这个句子生成表示以用于检索相关文章：" : "") + text);
            long[] mask = new long[ids.length]; Arrays.fill(mask, 1);
            try (var input = OnnxTensor.createTensor(environment, new long[][]{ids});
                 var attention = OnnxTensor.createTensor(environment, new long[][]{mask});
                 var types = OnnxTensor.createTensor(environment, new long[][]{new long[ids.length]})) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", input); inputs.put("attention_mask", attention);
                if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", types);
                try (var result = session.run(inputs)) {
                    Object value = result.get(0).getValue();
                    float[] vector = value instanceof float[][][] hidden ? hidden[0][0]
                            : value instanceof float[][] pooled ? pooled[0] : null;
                    if (vector == null || vector.length != DIMENSIONS) throw new IllegalStateException("Invalid embedding shape");
                    double norm = 0;
                    for (float component : vector) { if (!Float.isFinite(component)) throw new IllegalStateException(); norm += component * component; }
                    if (norm < 1e-12) throw new IllegalStateException("Empty embedding vector");
                    float[] normalized = vector.clone();
                    for (int index = 0; index < normalized.length; index++) normalized[index] /= (float) Math.sqrt(norm);
                    return normalized;
                }
            }
        } catch (OrtException failure) {
            throw new IllegalStateException("Local embedding inference failed");
        } finally { slots.release(); }
    }

    private static void verify(Path path, String expected, long size) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) != size) throw new IllegalStateException();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IllegalStateException();
    }

    @Override public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
