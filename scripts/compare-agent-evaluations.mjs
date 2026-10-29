import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const MODE = "OFFLINE_RULES_AND_FAULT_FIXTURES";
const RATE_NAMES = [
  "scenarioSuccess",
  "toolSelectionAgreement",
  "securityProbeSuccess",
  "sourceGrounding",
];

function requireCondition(condition, code) {
  if (!condition) throw new Error(code);
}

function rate(passed, total) {
  return { passed, total, value: total === 0 ? null : passed / total };
}

function recompute(report) {
  const results = report.results;
  const sources = results.reduce(
    (sum, row) => sum + (row.observed?.sourceCount ?? 0),
    0,
  );
  const grounded = results.reduce(
    (sum, row) => sum + (row.observed?.groundedSourceCount ?? 0),
    0,
  );
  return {
    scenarioSuccess: rate(
      results.filter((row) => row.passed).length,
      results.length,
    ),
    toolSelectionAgreement: rate(
      results.filter((row) => row.capabilityMatch).length,
      results.length,
    ),
    securityProbeSuccess: rate(
      results.filter((row) => row.securityPassed).length,
      results.filter((row) => row.securityProbe).length,
    ),
    sourceGrounding: rate(grounded, sources),
  };
}

function validate(report) {
  requireCondition(
    report?.schemaVersion === 1 && report.mode === MODE,
    "unsupported_schema_or_mode",
  );
  requireCondition(
    typeof report.datasetVersion === "string" &&
      /^[a-z0-9-]{3,100}$/.test(report.datasetVersion),
    "invalid_dataset_version",
  );
  requireCondition(
    typeof report.datasetSha256 === "string" &&
      /^[a-f0-9]{64}$/.test(report.datasetSha256),
    "invalid_dataset_fingerprint",
  );
  requireCondition(
    Array.isArray(report.results) &&
      report.results.length > 0 &&
      report.results.length <= 1000,
    "invalid_case_count",
  );
  const ids = new Set();
  for (const row of report.results) {
    requireCondition(
      typeof row.id === "string" &&
        /^[a-z][a-z0-9-]{2,63}$/.test(row.id) &&
        !ids.has(row.id),
      "invalid_or_duplicate_case_id",
    );
    requireCondition(
      typeof row.category === "string" &&
        typeof row.plannerFixture === "string",
      "missing_case_metadata",
    );
    ids.add(row.id);
    for (const key of [
      "passed",
      "capabilityMatch",
      "securityProbe",
      "securityPassed",
    ]) {
      requireCondition(typeof row[key] === "boolean", "invalid_case_flags");
    }
    requireCondition(
      !row.securityPassed || (row.securityProbe && row.passed),
      "inconsistent_security_result",
    );
    requireCondition(
      Array.isArray(row.failures) && row.passed === (row.failures.length === 0),
      "inconsistent_case_result",
    );
    requireCondition(
      row.observed != null || !row.passed,
      "passing_case_without_evidence",
    );
    if (row.observed != null) {
      const actual = row.observed;
      requireCondition(
        Number.isInteger(actual.sourceCount) &&
          actual.sourceCount >= 0 &&
          Number.isInteger(actual.groundedSourceCount) &&
          actual.groundedSourceCount >= 0 &&
          actual.groundedSourceCount <= actual.sourceCount,
        "invalid_source_counts",
      );
      requireCondition(
        Number.isFinite(actual.latencyMs) && actual.latencyMs >= 0,
        "invalid_latency",
      );
    }
  }
  const computed = recompute(report);
  for (const name of RATE_NAMES) {
    const actual = report.summary?.[name];
    const expected = computed[name];
    requireCondition(
      actual?.passed === expected.passed &&
        actual?.total === expected.total &&
        (expected.value === null
          ? actual?.value === null
          : Number.isFinite(actual?.value) &&
            Math.abs(actual.value - expected.value) < 1e-10),
      `inconsistent_summary_${name}`,
    );
  }
  requireCondition(
    report.summary.realProviderCalls === 0 &&
      Number(report.summary.actualProviderCostUsd) === 0,
    "offline_report_claims_real_usage",
  );
  return computed;
}

/** Same cases and same mode only. A new case-set version needs its own reviewed baseline. */
export function compareReports(previous, current) {
  const oldMetrics = validate(previous);
  const newMetrics = validate(current);
  requireCondition(
    previous.datasetVersion === current.datasetVersion &&
      previous.datasetSha256 === current.datasetSha256,
    "different_datasets",
  );
  const oldRows = new Map(previous.results.map((row) => [row.id, row]));
  requireCondition(
    previous.results.length === current.results.length &&
      current.results.every((row) => oldRows.has(row.id)),
    "different_case_ids",
  );
  const regressions = [];
  for (const row of current.results) {
    const old = oldRows.get(row.id);
    requireCondition(
      old.category === row.category &&
        old.plannerFixture === row.plannerFixture,
      "different_case_metadata",
    );
    if (old.passed && !row.passed)
      regressions.push({ id: row.id, check: "scenario" });
    if (old.capabilityMatch && !row.capabilityMatch)
      regressions.push({ id: row.id, check: "tool_selection" });
    if (old.securityPassed && !row.securityPassed)
      regressions.push({ id: row.id, check: "security_probe" });
  }
  const metrics = Object.fromEntries(
    RATE_NAMES.map((name) => [
      name,
      {
        previous: oldMetrics[name],
        current: newMetrics[name],
        change:
          oldMetrics[name].value === null || newMetrics[name].value === null
            ? null
            : newMetrics[name].value - oldMetrics[name].value,
      },
    ]),
  );
  return {
    status:
      regressions.length === 0 && current.results.every((row) => row.passed)
        ? "PASS"
        : "FAIL",
    datasetVersion: current.datasetVersion,
    mode: current.mode,
    implementations: {
      previous: previous.implementationSha256 ?? "not-recorded",
      current: current.implementationSha256 ?? "not-recorded",
    },
    metrics,
    regressions,
    currentFailedCases: current.results
      .filter((row) => !row.passed)
      .map((row) => ({ id: row.id, checks: row.failures })),
    latency: {
      comparableEnvironment:
        previous.javaVersion === current.javaVersion &&
        previous.operatingSystem === current.operatingSystem &&
        previous.latencyScope === current.latencyScope,
      previousP50Ms: previous.summary.latencyP50Ms,
      currentP50Ms: current.summary.latencyP50Ms,
      previousP95Ms: previous.summary.latencyP95Ms,
      currentP95Ms: current.summary.latencyP95Ms,
      scope:
        "informational only; includes security and temporary MySQL, not provider or production latency",
    },
    usage: {
      realProviderCalls: 0,
      actualProviderCostUsd: 0,
      previousSyntheticReportedTokens: previous.summary.syntheticReportedTokens,
      currentSyntheticReportedTokens: current.summary.syntheticReportedTokens,
      previousSyntheticChargedTokens: previous.summary.syntheticChargedTokens,
      currentSyntheticChargedTokens: current.summary.syntheticChargedTokens,
      scope:
        "nonzero token/cost values are scripted fault-fixture accounting; not actual provider usage",
    },
  };
}

function load(path) {
  const absolute = resolve(path);
  requireCondition(
    statSync(absolute).size <= 8 * 1024 * 1024,
    "report_too_large",
  );
  return JSON.parse(readFileSync(absolute, "utf8"));
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  if (process.argv.length !== 4) {
    process.stderr.write(
      "Usage: node scripts/compare-agent-evaluations.mjs <previous.json> <current.json>\n",
    );
    process.exitCode = 2;
  } else {
    try {
      const result = compareReports(
        load(process.argv[2]),
        load(process.argv[3]),
      );
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      process.exitCode = result.status === "PASS" ? 0 : 1;
    } catch (error) {
      // Known validation codes only: never print the contents of an invalid input file.
      const code =
        error instanceof Error && /^[a-z_]+$/.test(error.message)
          ? error.message
          : "unreadable_report";
      process.stderr.write(
        `Agent evaluation reports cannot be compared: ${code}\n`,
      );
      process.exitCode = 2;
    }
  }
}
