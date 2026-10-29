package dev.supportflow.assistant.evaluation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fast, no-Docker checks that the evaluator cannot manufacture green reports from incomplete evidence. */
class AgentEvaluationContractTest {
    @Test
    void fixedLabelsHaveCoverageUniqueIdsAndAReviewedFingerprint() throws Exception {
        var data = AgentEvaluationCases.load();
        assertThat(AgentEvaluationReport.datasetViolations(data, AgentEvaluationReport.baseline(), AgentEvaluationCases.fingerprint())).isEmpty();
        assertThat(data.scenarios()).hasSize(47);
        assertThat(data.scenarios()).extracting(AgentEvaluationCases.Scenario::id).doesNotHaveDuplicates();
        assertThat(data.scenarios()).extracting(AgentEvaluationCases.Scenario::category)
                .contains(AgentEvaluationCases.Category.values());
        var allowed = Set.of("ORDER_LOOKUP", "ORDER_LIST", "KNOWLEDGE_LOOKUP", "LOGISTICS_LOOKUP", "AFTER_SALE_REQUEST", "AFTER_SALE_HISTORY");
        for (var scenario : data.scenarios()) {
            assertThat(scenario.id()).matches("[a-z][a-z0-9-]{2,63}");
            assertThat(scenario.message()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
            assertThat(scenario.actor()).isNotNull();
            assertThat(scenario.planner()).isNotNull();
            assertThat(scenario.shop()).isIn(100, 101);
            var expected = scenario.expected();
            assertThat(expected.capabilities()).isSubsetOf(allowed);
            assertThat(expected.ticketDelta()).isBetween(0, 1);
            assertThat(expected.phases().stream().filter("PLAN"::equals).count()).isEqualTo(expected.plannerCalls());
            if (expected.httpStatus() >= 400) assertThat(expected.ticketDelta()).isZero();
            if (expected.ticketDelta() == 1) assertThat(expected.eligibility()).isIn("ELIGIBLE", "REQUIRES_REVIEW");
        }
    }

    @Test
    void unknownLabelFieldsFailRatherThanBeingSilentlyIgnored() throws Exception {
        String input = new String(AgentEvaluationCases.resource("scenarios.json"), StandardCharsets.UTF_8)
                .replaceFirst("\"schemaVersion\"", "\"misspelledSchemaVersion\"");
        assertThatThrownBy(() -> new ObjectMapper().readValue(input, AgentEvaluationCases.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void deletingFailingCasesCannotSatisfyTheBaseline() throws Exception {
        var data = AgentEvaluationCases.load();
        var smaller = new AgentEvaluationCases(data.schemaVersion(), data.version(), data.scenarios().subList(1, data.scenarios().size()));
        assertThat(AgentEvaluationReport.datasetViolations(smaller, AgentEvaluationReport.baseline(), AgentEvaluationCases.fingerprint()))
                .contains("case_count_reduced", "required_cases_removed");
    }

    @Test
    void relabellingCasesRequiresAnExplicitVersionedBaselineReview() throws Exception {
        assertThat(AgentEvaluationReport.datasetViolations(AgentEvaluationCases.load(), AgentEvaluationReport.baseline(), "0".repeat(64)))
                .contains("dataset_fingerprint_changed");
    }

    @Test
    void matchingHttpStatusAloneDoesNotPassWrongToolsOrInventedFacts() throws Exception {
        var scenario = first();
        var good = valid(scenario);
        var wrong = new AgentEvaluationReport.Observation(good.httpStatus(), good.responseType(), Set.of("LOGISTICS_LOOKUP"),
                List.of("LOGISTICS_LOOKUP"), good.run(), good.writes(), false, 1, 0, 1.0);
        var result = AgentEvaluationReport.score(scenario, wrong, "unrelated answer");
        assertThat(result.passed()).isFalse();
        assertThat(result.capabilityMatch()).isFalse();
        assertThat(result.failures()).contains("capability_selection", "private_data_exposed", "ungrounded_source", "required_text_0");
    }

    @Test
    void matchingResponseCannotHideUnapprovedWritesOrAnExceededBudget() throws Exception {
        var scenario = first();
        var good = valid(scenario);
        var run = new AgentEvaluationReport.RunEvidence("NONE", List.of(), 0, 0, 0, 0, BigDecimal.ZERO, false);
        var writes = new AgentEvaluationReport.WriteEvidence(1, "ELIGIBLE", false, 1, 1, false);
        var wrong = new AgentEvaluationReport.Observation(good.httpStatus(), good.responseType(), good.capabilities(), good.callTrace(),
                run, writes, true, 0, 0, 1.0);
        assertThat(AgentEvaluationReport.score(scenario, wrong, String.join(" ", scenario.expected().contains())).failures())
                .contains("ticket_count", "human_approval_bypassed", "unapproved_execution", "orders_modified", "run_budget_exceeded");
    }

    @Test
    void aRejectedSecurityProbeMustMeetAllOfItsExpectations() throws Exception {
        var scenario = AgentEvaluationCases.load().scenarios().stream().filter(s -> s.id().equals("deny-cross-shop")).findFirst().orElseThrow();
        var good = valid(scenario);
        var leaked = new AgentEvaluationReport.Observation(404, null, good.capabilities(), good.callTrace(), good.run(), good.writes(),
                false, 0, 0, 1.0);
        assertThat(AgentEvaluationReport.score(scenario, leaked, "").securityPassed()).isFalse();
    }

    @Test
    void absentEvidenceUsesNullRatesAndFailsTheGateInsteadOfClaimingOneHundredPercent() throws Exception {
        var data = AgentEvaluationCases.load();
        var empty = AgentEvaluationReport.assemble(data, AgentEvaluationCases.fingerprint(), List.of());
        assertThat(empty.summary().scenarioSuccess().value()).isNull();
        assertThat(empty.summary().securityProbeSuccess().value()).isNull();
        assertThat(empty.summary().sourceGrounding().value()).isNull();
        assertThat(AgentEvaluationReport.gate(empty, AgentEvaluationReport.baseline()))
                .contains("incomplete_report", "missing_or_duplicate_results", "scenario_success", "security_probes", "source_grounding");
        assertThat(AgentEvaluationReport.markdown(empty)).contains("N/A");
    }

    @Test
    void harnessExceptionsAreCountedAndDoNotLeakTheirMessagesIntoReports() throws Exception {
        var scenario = first();
        var failure = AgentEvaluationReport.harnessFailure(scenario, new IllegalStateException("PRIVATE_SENTINEL_DO_NOT_PRINT"));
        var report = AgentEvaluationReport.assemble(AgentEvaluationCases.load(), AgentEvaluationCases.fingerprint(), List.of(failure));
        String serialized = new ObjectMapper().writeValueAsString(report);
        assertThat(serialized).contains("harness_error:IllegalStateException").doesNotContain("PRIVATE_SENTINEL_DO_NOT_PRINT");
        assertThat(report.summary().scenarioSuccess().passed()).isZero();
    }

    private static AgentEvaluationCases.Scenario first() throws Exception { return AgentEvaluationCases.load().scenarios().getFirst(); }

    private static AgentEvaluationReport.Observation valid(AgentEvaluationCases.Scenario scenario) {
        var expected = scenario.expected();
        return new AgentEvaluationReport.Observation(expected.httpStatus(), expected.responseType(), expected.capabilities(),
                expected.capabilities().stream().sorted().toList(), AgentEvaluationReport.RunEvidence.absent(0),
                new AgentEvaluationReport.WriteEvidence(expected.ticketDelta(), expected.eligibility(), true, 0, 0, true), true, 0, 0, 1.0);
    }
}
