package dev.supportflow.assistant.knowledge;

import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import dev.supportflow.merchant.knowledge.KnowledgeRepository;
import dev.supportflow.merchant.knowledge.PublishedKnowledge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reproducible synthetic regression metrics; not a claim about real-world generalization or load. */
class KnowledgeEvaluationTest {
    private static final String CASE_VERSION = "knowledge-cases-2026-10-25-v1";

    @Test
    void fixedCasesMeasureCitationPrecisionRecallAbstentionGroundingAndShopIsolation() throws IOException {
        List<Fixture> corpus = loadCorpus();
        List<Case> cases = loadCases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
        assertThat(cases).extracting(Case::id).doesNotHaveDuplicates();
        assertThat(corpus).extracting(fixture -> fixture.source().articleId()).doesNotHaveDuplicates();
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.publishedForShop(anyLong())).thenAnswer(call -> {
            long shop = call.getArgument(0);
            return corpus.stream().filter(fixture -> fixture.shop() == shop).map(Fixture::source).toList();
        });
        KnowledgeService service = new KnowledgeService(repository);
        long predicted = 0;
        long expected = 0;
        long truePositive = 0;
        long negativeCases = 0;
        long rejected = 0;
        long grounded = 0;
        long isolated = 0;
        var mismatches = new ArrayList<String>();
        for (Case example : cases) {
            for (String expectedId : example.expected()) {
                assertThat(corpus).as("label %s belongs to case shop", expectedId).anySatisfy(fixture -> {
                    assertThat("kb-" + fixture.source().articleId()).isEqualTo(expectedId);
                    assertThat(fixture.shop()).isEqualTo(example.shop());
                });
            }
            KnowledgeAnswer result = service.tryAnswerDetailed(example.shop(), example.question())
                    .orElseGet(() -> new KnowledgeAnswer("no merchant evidence",
                            new dev.supportflow.chat.application.model.KnowledgeEvidence(KnowledgeStatus.NO_MATCH, List.of())));
            Set<String> actual = result.evidence().sources().stream().map(KnowledgeSource::sourceId).collect(Collectors.toSet());
            KnowledgeStatus expectedStatus = example.expected().isEmpty() ? KnowledgeStatus.NO_MATCH : KnowledgeStatus.ANSWERED;
            if (!actual.equals(example.expected()) || result.evidence().status() != expectedStatus) {
                mismatches.add(example.id() + " [" + example.category() + "]: expected=" + example.expected()
                        + " actual=" + actual + " status=" + result.evidence().status());
            }
            predicted += actual.size();
            expected += example.expected().size();
            truePositive += actual.stream().filter(example.expected()::contains).count();
            if (example.expected().isEmpty()) {
                negativeCases++;
                if (actual.isEmpty() && result.evidence().status() == KnowledgeStatus.NO_MATCH) rejected++;
            }
            for (KnowledgeSource source : result.evidence().sources()) {
                var fixture = corpus.stream().filter(item -> source.sourceId().equals("kb-" + item.source().articleId()))
                        .findFirst().orElse(null);
                if (fixture != null) {
                    if (fixture.shop() == example.shop()) isolated++;
                    if (source.version().equals("v" + fixture.source().revision())
                            && source.title().equals(fixture.source().title())
                            && source.content().equals(fixture.source().content())) grounded++;
                }
            }
            assertThat(result.evidence().sources()).hasSizeLessThanOrEqualTo(2);
        }
        assertThat(predicted).isPositive();
        assertThat(expected).isPositive();
        assertThat(negativeCases).isPositive();
        double precision = (double) truePositive / predicted;
        double recall = (double) truePositive / expected;
        double rejection = (double) rejected / negativeCases;
        double grounding = (double) grounded / predicted;
        double isolation = (double) isolated / predicted;
        System.out.printf(Locale.ROOT,
                "KNOWLEDGE_EVAL cases=%s aliases=%s total=%d precision=%.4f recall=%.4f rejection=%.4f grounding=%.4f tenantIsolation=%.4f "
                        + "tp=%d predicted=%d expected=%d rejected=%d negativeCases=%d grounded=%d isolated=%d%n",
                CASE_VERSION, KnowledgeQueryExpansion.VERSION, cases.size(), precision, recall, rejection, grounding, isolation,
                truePositive, predicted, expected, rejected, negativeCases, grounded, isolated);
        assertThat(mismatches).as("Fixed-set regressions; add new cases rather than deleting failures").isEmpty();
        assertThat(precision).isEqualTo(1.0);
        assertThat(recall).isEqualTo(1.0);
        assertThat(rejection).isEqualTo(1.0);
        assertThat(grounding).isEqualTo(1.0);
        assertThat(isolation).isEqualTo(1.0);
    }

    private static List<Fixture> loadCorpus() throws IOException {
        List<Fixture> fixtures = new ArrayList<>();
        for (String line : lines("corpus.tsv")) {
            String[] columns = line.split("\t", -1);
            assertThat(columns).as("corpus TSV columns").hasSize(6);
            fixtures.add(new Fixture(Long.parseLong(columns[0]), new PublishedKnowledge(columns[1],
                    Integer.parseInt(columns[2]), columns[3], List.of(columns[4].split("\\|")),
                    columns[5].replace("\\n", "\n"), Instant.EPOCH)));
        }
        return List.copyOf(fixtures);
    }

    private static List<Case> loadCases() throws IOException {
        List<Case> examples = new ArrayList<>();
        for (String line : lines("cases.tsv")) {
            String[] columns = line.split("\t", -1);
            assertThat(columns).as("cases TSV columns").hasSize(5);
            Set<String> expected = columns[2].equals("-") ? Set.of() : new HashSet<>(Arrays.asList(columns[2].split("\\|")));
            examples.add(new Case(columns[0], Long.parseLong(columns[1]), Set.copyOf(expected), columns[3], columns[4]));
        }
        return List.copyOf(examples);
    }

    private static List<String> lines(String resource) throws IOException {
        try (var stream = KnowledgeEvaluationTest.class.getResourceAsStream("/knowledge-evaluation/" + resource)) {
            if (stream == null) throw new IllegalStateException("Missing evaluation resource: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        }
    }

    private record Fixture(long shop, PublishedKnowledge source) { }
    private record Case(String id, long shop, Set<String> expected, String question, String category) { }
}
