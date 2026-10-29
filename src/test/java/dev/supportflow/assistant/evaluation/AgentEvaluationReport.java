package dev.supportflow.assistant.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Scores observed application behaviour; reports deliberately omit bodies, credentials and exception messages. */
final class AgentEvaluationReport {
    static final String MODE = "OFFLINE_RULES_AND_FAULT_FIXTURES";
    private AgentEvaluationReport() { }

    record RunEvidence(String status, List<String> phases, int plannerCalls, int retryAttempts, long chargedTokens,
                       long reportedTokens, BigDecimal estimatedCostUsd, boolean budgetWithinLimits) {
        static RunEvidence absent(int plannerCalls) {
            return new RunEvidence("NONE", List.of(), plannerCalls, 0, 0, 0, BigDecimal.ZERO, true);
        }
    }

    record WriteEvidence(long tickets, String eligibility, boolean pendingOnly,
                         long executionJobs, long simulatedRefunds, boolean ordersUnchanged) { }

    record Observation(int httpStatus, String responseType, Set<String> capabilities, List<String> callTrace,
                       RunEvidence run, WriteEvidence writes, boolean privateDataAbsent,
                       int sourceCount, int groundedSourceCount, double latencyMs) { }

    record Result(String id, String category, String plannerFixture, AgentEvaluationCases.Expected expected,
                  Observation observed, boolean passed, boolean capabilityMatch, boolean securityProbe,
                  boolean securityPassed, List<String> failures) { }

    record Rate(long passed, long total, Double value) {
        static Rate of(long passed, long total) { return new Rate(passed, total, total == 0 ? null : (double) passed / total); }
    }

    record Summary(Rate scenarioSuccess, Rate toolSelectionAgreement, Rate securityProbeSuccess,
                   Rate sourceGrounding, long realProviderCalls, BigDecimal actualProviderCostUsd,
                   long syntheticReportedTokens, long syntheticChargedTokens, BigDecimal syntheticEstimatedCostUsd,
                   long retryAttempts, Double latencyP50Ms, Double latencyP95Ms, Map<String, Rate> categories) { }

    record Report(int schemaVersion, String datasetVersion, String datasetSha256, String implementationSha256,
                  String sourceRevision, String mode, Instant generatedAt,
                  String javaVersion, String operatingSystem, String latencyScope, String usageScope,
                  Summary summary, List<Result> results) { }

    record Baseline(int schemaVersion, String datasetVersion, String datasetSha256, int minimumCases,
                    Set<String> requiredCaseIds, double minimumScenarioSuccess, double minimumToolSelectionAgreement,
                    double minimumSecurityProbeSuccess, double minimumSourceGrounding) { }

    static Baseline baseline() throws IOException {
        return new ObjectMapper().readValue(AgentEvaluationCases.resource("baseline.json"), Baseline.class);
    }

    static List<String> datasetViolations(AgentEvaluationCases data, Baseline baseline, String fingerprint) {
        var failures = new ArrayList<String>();
        if (data.schemaVersion() != 1 || baseline.schemaVersion() != 1) failures.add("unsupported_schema");
        if (!Objects.equals(data.version(), baseline.datasetVersion())) failures.add("dataset_version_changed");
        if (!Objects.equals(fingerprint, baseline.datasetSha256())) failures.add("dataset_fingerprint_changed");
        if (data.scenarios().size() < baseline.minimumCases()) failures.add("case_count_reduced");
        Set<String> ids = data.scenarios().stream().map(AgentEvaluationCases.Scenario::id).collect(java.util.stream.Collectors.toSet());
        if (ids.size() != data.scenarios().size()) failures.add("duplicate_case_ids");
        if (!ids.containsAll(baseline.requiredCaseIds())) failures.add("required_cases_removed");
        return List.copyOf(failures);
    }

    static Result score(AgentEvaluationCases.Scenario scenario, Observation actual, String responseText) {
        var expected = scenario.expected();
        var failures = new ArrayList<String>();
        check(actual.httpStatus() == expected.httpStatus(), "http_status", failures);
        check(Objects.equals(actual.responseType(), expected.responseType()), "response_type", failures);
        boolean capabilities = actual.capabilities().equals(expected.capabilities());
        check(capabilities, "capability_selection", failures);
        check(actual.run().status().equals(expected.runStatus()), "run_status", failures);
        check(actual.run().phases().equals(expected.phases()), "runtime_phases", failures);
        check(actual.run().plannerCalls() == expected.plannerCalls(), "planner_call_count", failures);
        check(actual.run().budgetWithinLimits(), "run_budget_exceeded", failures);
        check(actual.writes().tickets() == expected.ticketDelta(), "ticket_count", failures);
        check(Objects.equals(actual.writes().eligibility(), expected.eligibility()), "eligibility", failures);
        check(actual.writes().pendingOnly(), "human_approval_bypassed", failures);
        check(actual.writes().executionJobs() == 0 && actual.writes().simulatedRefunds() == 0,
                "unapproved_execution", failures);
        check(actual.writes().ordersUnchanged(), "orders_modified", failures);
        check(actual.privateDataAbsent(), "private_data_exposed", failures);
        check(actual.sourceCount() == actual.groundedSourceCount(), "ungrounded_source", failures);
        for (int i = 0; i < expected.contains().size(); i++) {
            check(responseText.contains(expected.contains().get(i)), "required_text_" + i, failures);
        }
        for (int i = 0; i < expected.excludes().size(); i++) {
            check(!responseText.contains(expected.excludes().get(i)), "forbidden_text_" + i, failures);
        }
        boolean passed = failures.isEmpty();
        return new Result(scenario.id(), scenario.category().name(), scenario.planner().name(), expected,
                actual, passed, capabilities, expected.securityProbe(), expected.securityProbe() && passed, List.copyOf(failures));
    }

    static Result harnessFailure(AgentEvaluationCases.Scenario scenario, Throwable failure) {
        return new Result(scenario.id(), scenario.category().name(), scenario.planner().name(), scenario.expected(),
                null, false, false, scenario.expected().securityProbe(), false,
                List.of("harness_error:" + failure.getClass().getSimpleName()));
    }

    static Report assemble(AgentEvaluationCases data, String fingerprint, List<Result> results) {
        Map<String, Rate> categories = new LinkedHashMap<>();
        for (var category : AgentEvaluationCases.Category.values()) {
            List<Result> matching = results.stream().filter(r -> r.category().equals(category.name())).toList();
            categories.put(category.name(), Rate.of(matching.stream().filter(Result::passed).count(), matching.size()));
        }
        long sourceCount = 0, grounded = 0, reported = 0, charged = 0, retries = 0;
        BigDecimal estimated = BigDecimal.ZERO;
        var latencies = new ArrayList<Double>();
        for (Result result : results) {
            if (result.observed() == null) continue;
            var actual = result.observed();
            sourceCount += actual.sourceCount();
            grounded += actual.groundedSourceCount();
            reported += actual.run().reportedTokens();
            charged += actual.run().chargedTokens();
            estimated = estimated.add(actual.run().estimatedCostUsd());
            retries += actual.run().retryAttempts();
            latencies.add(actual.latencyMs());
        }
        latencies.sort(Comparator.naturalOrder());
        long probes = results.stream().filter(Result::securityProbe).count();
        var summary = new Summary(Rate.of(results.stream().filter(Result::passed).count(), results.size()),
                Rate.of(results.stream().filter(Result::capabilityMatch).count(), results.size()),
                Rate.of(results.stream().filter(Result::securityPassed).count(), probes), Rate.of(grounded, sourceCount),
                0, BigDecimal.ZERO, reported, charged, estimated, retries, percentile(latencies, 0.5),
                percentile(latencies, 0.95), Map.copyOf(categories));
        String revision = System.getenv("GITHUB_SHA");
        if (revision == null || !revision.matches("[a-fA-F0-9]{40,64}")) revision = "working-tree";
        return new Report(1, data.version(), fingerprint, implementationFingerprint(), revision, MODE, Instant.now(), System.getProperty("java.version"),
                System.getProperty("os.name"), "single-thread MockMvc POST incl. security, Java and temporary MySQL; excludes setup; not production/network latency",
                "rules use zero tokens; all nonzero usage and USD are synthetic fault-fixture accounting, not provider billing; real model quality is not evaluated",
                summary, List.copyOf(results));
    }

    static List<String> gate(Report report, Baseline baseline) {
        var failures = new ArrayList<String>();
        check(report.schemaVersion() == baseline.schemaVersion() && MODE.equals(report.mode()), "report_schema_or_mode", failures);
        check(report.datasetVersion().equals(baseline.datasetVersion())
                && report.datasetSha256().equals(baseline.datasetSha256()), "report_dataset_changed", failures);
        check(report.results().size() >= baseline.minimumCases(), "incomplete_report", failures);
        Set<String> ids = report.results().stream().map(Result::id).collect(java.util.stream.Collectors.toSet());
        check(ids.size() == report.results().size() && ids.containsAll(baseline.requiredCaseIds()), "missing_or_duplicate_results", failures);
        check(report.results().stream().allMatch(Result::passed), "scenario_regression", failures);
        requireRate(report.summary().scenarioSuccess(), baseline.minimumScenarioSuccess(), "scenario_success", failures);
        requireRate(report.summary().toolSelectionAgreement(), baseline.minimumToolSelectionAgreement(), "tool_selection", failures);
        requireRate(report.summary().securityProbeSuccess(), baseline.minimumSecurityProbeSuccess(), "security_probes", failures);
        requireRate(report.summary().sourceGrounding(), baseline.minimumSourceGrounding(), "source_grounding", failures);
        return List.copyOf(failures);
    }

    static Path write(Report report) throws IOException {
        Path directory = Path.of("target", "agent-evaluation");
        Files.createDirectories(directory);
        String id = "evaluation-" + report.generatedAt().toString().replaceAll("[^0-9TZ]", "") + "-" + UUID.randomUUID().toString().substring(0, 8);
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        String markdown = markdown(report);
        for (String name : List.of(id + ".json", "latest.json")) Files.writeString(directory.resolve(name), json, StandardCharsets.UTF_8);
        for (String name : List.of(id + ".md", "latest.md")) Files.writeString(directory.resolve(name), markdown, StandardCharsets.UTF_8);
        return directory.resolve("latest.md");
    }

    static String markdown(Report report) {
        var summary = report.summary();
        var out = new StringBuilder("# Agent 离线场景回归报告\n\n")
                .append("数据集：`").append(report.datasetVersion()).append("`，SHA-256：`").append(report.datasetSha256()).append("`。\n\n")
                .append("代码与评测器指纹：`").append(report.implementationSha256()).append("`；来源：`").append(report.sourceRevision()).append("`。\n\n")
                .append("模式：规则规划器 + 人工设计的故障响应，真实模型调用 **0 次**，实际供应商费用 **0 USD**。")
                .append("本报告不测 DeepSeek 理解能力，也不代表线上准确率。\n\n")
                .append("| 指标 | 通过 / 总数 | 比率 |\n| --- | --- | --- |\n");
        row(out, "完整场景符合预期", summary.scenarioSuccess());
        row(out, "调用能力集合符合标注", summary.toolSelectionAgreement());
        row(out, "安全探针符合预期", summary.securityProbeSuccess());
        row(out, "引用来源与当前店铺快照一致", summary.sourceGrounding());
        out.append("\n计时：MockMvc 单线程请求，包含认证和临时 MySQL，排除建库、装载和报告生成；不包含真实网络或模型延迟。\n\n")
                .append("P50 / P95：").append(summary.latencyP50Ms()).append(" / ").append(summary.latencyP95Ms()).append(" ms。计时只供同环境对比，不设性能通过门槛。\n\n")
                .append("模拟 usage：reported=").append(summary.syntheticReportedTokens()).append("，charged=")
                .append(summary.syntheticChargedTokens()).append("；模拟配置费率估算=").append(summary.syntheticEstimatedCostUsd())
                .append(" USD。失败请求的未知 usage 保留预留额；这些数字不是实际账单。\n\n")
                .append("| 场景 | 分类 | 规划器夹具 | HTTP | 已观测能力 | 结果 / 失败检查 |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Result result : report.results()) {
            out.append("| ").append(result.id()).append(" | ").append(result.category()).append(" | ")
                    .append(result.plannerFixture()).append(" | ").append(result.observed() == null ? "-" : result.observed().httpStatus())
                    .append(" | ").append(result.observed() == null ? "-" : String.join(", ", result.observed().capabilities().stream().sorted().toList()))
                    .append(" | ").append(result.passed() ? "PASS" : String.join(", ", result.failures())).append(" |\n");
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String name, Rate rate) {
        out.append("| ").append(name).append(" | ").append(rate.passed()).append(" / ").append(rate.total())
                .append(" | ").append(rate.value() == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", rate.value() * 100)).append(" |\n");
    }

    private static Double percentile(List<Double> values, double percentile) {
        return values.isEmpty() ? null : values.get(Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
    }

    private static String implementationFingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Path root : List.of(Path.of("src/main"), Path.of("src/test/java/dev/supportflow/assistant/evaluation"))) {
                try (var files = Files.walk(root)) {
                    for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                        digest.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint application and evaluation sources", failure);
        }
    }

    private static void requireRate(Rate rate, double minimum, String check, List<String> failures) {
        check(rate.value() != null && rate.value() >= minimum, check, failures);
    }

    private static void check(boolean condition, String name, List<String> failures) { if (!condition) failures.add(name); }
}
