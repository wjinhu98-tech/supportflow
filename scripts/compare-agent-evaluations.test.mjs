import { test } from "node:test";
import assert from "node:assert/strict";
import { compareReports } from "./compare-agent-evaluations.mjs";

function sample() {
  const rows = ["owned-order", "denied-order"].map((id, index) => ({
    id,
    category: index ? "SECURITY" : "ORDER",
    plannerFixture: "RULES",
    passed: true,
    capabilityMatch: true,
    securityProbe: index === 1,
    securityPassed: index === 1,
    failures: [],
    observed: { sourceCount: 0, groundedSourceCount: 0, latencyMs: 1 },
  }));
  return refresh({
    schemaVersion: 1,
    datasetVersion: "agent-scenarios-test-v1",
    datasetSha256: "a".repeat(64),
    mode: "OFFLINE_RULES_AND_FAULT_FIXTURES",
    javaVersion: "21",
    operatingSystem: "test-os",
    latencyScope: "test-scope",
    summary: {
      realProviderCalls: 0,
      actualProviderCostUsd: 0,
      syntheticReportedTokens: 0,
      syntheticChargedTokens: 0,
      latencyP50Ms: 1,
      latencyP95Ms: 1,
    },
    results: rows,
  });
}

function refresh(report) {
  const rate = (passed, total) => ({
    passed,
    total,
    value: total ? passed / total : null,
  });
  report.summary.scenarioSuccess = rate(
    report.results.filter((row) => row.passed).length,
    report.results.length,
  );
  report.summary.toolSelectionAgreement = rate(
    report.results.filter((row) => row.capabilityMatch).length,
    report.results.length,
  );
  report.summary.securityProbeSuccess = rate(
    report.results.filter((row) => row.securityPassed).length,
    report.results.filter((row) => row.securityProbe).length,
  );
  report.summary.sourceGrounding = rate(
    report.results.reduce(
      (sum, row) => sum + (row.observed?.groundedSourceCount ?? 0),
      0,
    ),
    report.results.reduce(
      (sum, row) => sum + (row.observed?.sourceCount ?? 0),
      0,
    ),
  );
  return report;
}

test("same dataset compares successfully and absent source metrics remain null", () => {
  const result = compareReports(sample(), sample());
  assert.equal(result.status, "PASS");
  assert.equal(result.metrics.sourceGrounding.change, null);
});

test("a new failed security case is reported and produces failure", () => {
  const current = sample();
  Object.assign(current.results[1], {
    passed: false,
    securityPassed: false,
    failures: ["private_data_exposed"],
  });
  const result = compareReports(sample(), refresh(current));
  assert.equal(result.status, "FAIL");
  assert.deepEqual(result.regressions, [
    { id: "denied-order", check: "scenario" },
    { id: "denied-order", check: "security_probe" },
  ]);
});

test("a pre-existing failure still exits as failure even if it is not a new regression", () => {
  const previous = sample();
  Object.assign(previous.results[0], {
    passed: false,
    failures: ["required_text_0"],
  });
  refresh(previous);
  assert.equal(
    compareReports(previous, structuredClone(previous)).status,
    "FAIL",
  );
});

test("deleting a failed case cannot improve a comparable report", () => {
  const current = sample();
  current.results.pop();
  assert.throws(
    () => compareReports(sample(), refresh(current)),
    /different_case_ids/,
  );
});

test("a relabelled dataset and a live-mode report cannot be mixed into the baseline", () => {
  const changed = sample();
  changed.datasetSha256 = "b".repeat(64);
  assert.throws(() => compareReports(sample(), changed), /different_datasets/);
  changed.mode = "LIVE";
  assert.throws(
    () => compareReports(sample(), changed),
    /unsupported_schema_or_mode/,
  );
});

test("summary totals are recalculated from case evidence", () => {
  const changed = sample();
  changed.summary.scenarioSuccess.passed = 999;
  assert.throws(
    () => compareReports(sample(), changed),
    /inconsistent_summary/,
  );
});

test("duplicate case ids and passing without observed evidence are rejected", () => {
  const changed = sample();
  changed.results[1].id = changed.results[0].id;
  assert.throws(() => compareReports(sample(), changed), /duplicate_case_id/);
  changed.results[1].id = "denied-order";
  changed.results[0].observed = null;
  assert.throws(
    () => compareReports(sample(), changed),
    /passing_case_without_evidence/,
  );
});

test("a faster request cannot erase a correctness failure and mixed environments are labelled", () => {
  const changed = sample();
  changed.operatingSystem = "different-os";
  changed.summary.latencyP50Ms = 0.1;
  Object.assign(changed.results[0], {
    passed: false,
    capabilityMatch: false,
    failures: ["capability_selection"],
  });
  const result = compareReports(sample(), refresh(changed));
  assert.equal(result.status, "FAIL");
  assert.equal(result.latency.comparableEnvironment, false);
});
