package dev.supportflow.assistant.knowledge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual fixed ONNX weights, never fake/hash embeddings or a paid model call. */
class LocalRagModelIT {
    @Test
    void frozenModelProducesFiniteNormalizedVectorsAndRanksRelatedChineseText() {
        try (var model = new OnnxKnowledgeEmbedding(Path.of(".local/models/bge-small-zh-v1.5"))) {
            float[] question = model.embed("安卓手机找不到无线耳塞", true);
            assertThat(question).hasSize(512);
            assertThat(RagKnowledgeSearch.cosine(question, question)).isBetween(0.9999, 1.0001);
            assertThat(RagKnowledgeSearch.cosine(question, model.embed("安卓蓝牙耳机配对与重新连接", false)))
                    .isGreaterThan(RagKnowledgeSearch.cosine(question, model.embed("纯棉被套机洗和烘干标签", false)));
        }
    }

    @Test
    void evaluateFrozenPublicQueriesAgainstLexicalBaseline() throws Exception {
        evaluate("cases", "calibration");
    }

    @Test
    void evaluateSeparateHeldOutQueriesWithoutChangingTheFrozenThreshold() throws Exception {
        evaluate("holdout", "holdout");
    }

    private void evaluate(String dataset, String reportName) throws Exception {
        var mapper = new ObjectMapper();
        var cases = List.of(mapper.readValue(new org.springframework.core.io.ClassPathResource(
                "knowledge-rag-evaluation/" + dataset + ".json").getInputStream(), Case[].class));
        try (var model = new OnnxKnowledgeEmbedding(Path.of(".local/models/bge-small-zh-v1.5"))) {
            var properties = new RagProperties(true, ".local/models/bge-small-zh-v1.5", 0.55, 0.025, 4096, 15000);
            var search = new RagKnowledgeSearch(properties, model, new SimpleMeterRegistry());
            var reports = new ArrayList<Map<String, Object>>();
            int expected = 0, lexicalHits = 0, hybridHits = 0, negatives = 0, correctRejections = 0;
            for (Case item : cases) {
                var documents = new PublicReferenceCatalog().forShop(item.shop());
                var baseline = new KnowledgeRetriever(documents).retrieve(item.question());
                var similarities = new java.util.HashMap<String, Double>();
                var result = search.search(item.shop(), documents, item.question(), similarities::put);
                var actual = result.stream().map(KnowledgeMatch::sourceId).toList();
                var lexical = baseline.stream().map(KnowledgeMatch::sourceId).toList();
                boolean hit = item.expected().stream().anyMatch(actual::contains);
                if (item.expected().isEmpty()) { negatives++; if (actual.isEmpty()) correctRejections++; }
                else { expected++; if (hit) hybridHits++; if (item.expected().stream().anyMatch(lexical::contains)) lexicalHits++; }
                var row = new LinkedHashMap<String, Object>();
                row.put("id", item.id()); row.put("shop", item.shop()); row.put("expected", item.expected());
                row.put("lexical", lexical); row.put("hybrid", actual); row.put("correct", item.expected().isEmpty() ? actual.isEmpty() : hit);
                row.put("denseTop3", similarities.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(3).map(value -> Map.of("sourceId", value.getKey(), "similarity", value.getValue())).toList());
                reports.add(row);
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("model", "bge-small-zh-v1.5-quantized-75c43b0"); report.put("cases", cases.size());
            report.put("dataset", reportName); report.put("minimumSimilarity", 0.55); report.put("minimumMargin", 0.025);
            report.put("modelSha256", OnnxKnowledgeEmbedding.MODEL_SHA256);
            try (var input = new org.springframework.core.io.ClassPathResource("knowledge-rag-evaluation/" + dataset + ".json").getInputStream()) {
                report.put("datasetSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
            report.put("positiveCases", expected); report.put("lexicalHitsAt2", lexicalHits); report.put("hybridHitsAt2", hybridHits);
            report.put("negativeCases", negatives); report.put("correctRejections", correctRejections); report.put("results", reports);
            Files.createDirectories(Path.of("target/knowledge-rag-evaluation"));
            Files.writeString(Path.of("target/knowledge-rag-evaluation/" + reportName + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            String markdown = "# Local RAG " + reportName + "\n\n"
                    + "Model: BGE-small-zh-v1.5 (fixed quantized ONNX weights). No paid model calls.\n\n"
                    + "| Metric | Lexical | Hybrid |\n| --- | --- | --- |\n"
                    + "| Positive Hit@2 | " + lexicalHits + "/" + expected + " | " + hybridHits + "/" + expected + " |\n"
                    + "| Correct negative rejection | — | " + correctRejections + "/" + negatives + " |\n\n"
                    + "Synthetic fixed cases only; this is not unknown-question accuracy or production capacity.\n";
            Files.writeString(Path.of("target/knowledge-rag-evaluation/" + reportName + ".md"), markdown);
            assertThat(hybridHits).as("Hybrid recall must improve on frozen lexical baseline; see target report").isGreaterThan(lexicalHits);
            assertThat(hybridHits).as("At least half the positive cases must have supported evidence").isGreaterThanOrEqualTo((expected + 1) / 2);
            assertThat(correctRejections).as("False evidence must not be introduced on negative cases").isEqualTo(negatives);
        }
    }

    record Case(String id, long shop, String question, List<String> expected) { }
}
