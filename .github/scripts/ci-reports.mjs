import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const MAX_FILES = 512;
const MAX_INPUT_BYTES = 32 * 1024 * 1024;
const MAX_CASES = 10_000;
const MODES = {
  backend: [
    ["unit", "target/surefire-reports", /^TEST-[A-Za-z0-9_.$-]+\.xml$/],
    ["integration", "target/failsafe-reports", /^TEST-[A-Za-z0-9_.$-]+\.xml$/],
  ],
  frontend: [
    ["unit", "target/ci-raw", /^frontend-unit\.xml$/],
    ["mock", "target/ci-raw", /^frontend-mock\.xml$/],
  ],
  live: [["live", "target/ci-raw", /^frontend-live\.xml$/]],
  automation: [["automation", "target/ci-raw", /^automation\.xml$/]],
};

function finiteTime(value) {
  const number = Number(value ?? 0);
  if (!Number.isFinite(number) || number < 0 || number > 86_400) {
    throw new Error("Invalid JUnit duration");
  }
  return number;
}

// Raw JUnit contains Java properties, fixture values and browser failure output.
// Only anonymous sequence identifiers, outcome and bounded duration may leave CI.
export function sanitizeJUnit(xml, group) {
  if (!/^[a-z]+$/.test(group) || Buffer.byteLength(xml) > MAX_INPUT_BYTES || /<!DOCTYPE|<!ENTITY/i.test(xml)) {
    throw new Error("Invalid or oversized JUnit report");
  }
  const content = xml
    .replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<(properties|system-out|system-err)\b[^>]*>[\s\S]*?<\/\1>/g, "");
  if (!/<testsuites?\b/.test(content)) throw new Error("Missing JUnit suite");
  const cases = [];
  const pattern = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase\s*>)/g;
  for (const match of content.matchAll(pattern)) {
    if (cases.length >= MAX_CASES) throw new Error("Too many JUnit cases");
    const time = finiteTime(/\btime\s*=\s*["']([^"']*)["']/.exec(match[1])?.[1]);
    const body = match[2] ?? "";
    const outcome = /<error\b/.test(body) ? "errors" : /<failure\b/.test(body) ? "failures" : /<skipped\b/.test(body) ? "skipped" : "passed";
    cases.push({ group, time, outcome });
  }
  if ((content.match(/<testcase\b/g) ?? []).length !== cases.length) {
    throw new Error("Malformed JUnit cases");
  }
  return cases;
}

export function summarizeCases(cases) {
  const result = { tests: cases.length, failures: 0, errors: 0, skipped: 0, time: 0 };
  for (const item of cases) {
    if (item.outcome !== "passed") result[item.outcome]++;
    result.time += item.time;
  }
  result.time = Math.round(result.time * 1000) / 1000;
  return result;
}

export function renderJUnit(cases, mode) {
  const total = summarizeCases(cases);
  const rows = cases.map((item, index) => {
    const outcome = item.outcome === "passed" ? "" : item.outcome === "skipped" ? "<skipped/>" : `<${item.outcome === "errors" ? "error" : "failure"} message="See the failed CI step; private output is omitted."/>`;
    return `  <testcase classname="${item.group}" name="case-${String(index + 1).padStart(5, "0")}" time="${item.time}">${outcome}</testcase>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="${mode}" tests="${total.tests}" failures="${total.failures}" errors="${total.errors}" skipped="${total.skipped}" time="${total.time}">\n${rows.join("\n")}\n</testsuite>\n`;
}

export function collectReports(mode, workspace = process.cwd()) {
  const inputs = MODES[mode];
  if (!inputs) throw new Error("Report mode must be backend, frontend, live or automation");
  const groups = {};
  const cases = [];
  let bytes = 0;
  let files = 0;
  for (const [group, directory, filename] of inputs) {
    const path = resolve(workspace, directory);
    const groupCases = [];
    for (const file of existsSync(path) ? readdirSync(path).filter((name) => filename.test(name)).sort() : []) {
      const input = resolve(path, file);
      const info = statSync(input, { throwIfNoEntry: true });
      bytes += info.size;
      if (!info.isFile() || ++files > MAX_FILES || bytes > MAX_INPUT_BYTES) throw new Error("JUnit input limit exceeded");
      groupCases.push(...sanitizeJUnit(readFileSync(input, "utf8"), group));
      if (cases.length + groupCases.length > MAX_CASES) throw new Error("JUnit case limit exceeded");
    }
    groups[group] = summarizeCases(groupCases);
    cases.push(...groupCases);
  }
  return { cases, summary: { schemaVersion: 1, mode, groups, total: summarizeCases(cases) } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const mode = process.argv[2];
  if (process.argv.length !== 3) throw new Error("Usage: node .github/scripts/ci-reports.mjs backend|frontend|live|automation");
  const { cases, summary } = collectReports(mode);
  const output = resolve("target/ci-reports");
  mkdirSync(output, { recursive: true });
  writeFileSync(resolve(output, `${mode}.xml`), renderJUnit(cases, mode), { flag: "wx" });
  writeFileSync(resolve(output, `${mode}.json`), `${JSON.stringify(summary, null, 2)}\n`, { flag: "wx" });
  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = Object.entries(summary.groups).map(([group, value]) => `| ${group} | ${value.tests} | ${value.failures + value.errors} | ${value.skipped} |`);
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\n### ${mode} test reports\n\n| Group | Tests | Failed | Skipped |\n| --- | ---: | ---: | ---: |\n${rows.join("\n")}\n\nSource: \`${process.env.GITHUB_SHA ?? "local"}\`; build: \`${process.env.GITHUB_RUN_ID ?? "local"}.${process.env.GITHUB_RUN_ATTEMPT ?? "1"}\`.\n`);
  }
  const minimum = mode === "live" ? { live: 30 } : mode === "frontend" ? { unit: 1, mock: 31 } : mode === "automation" ? { automation: 1 } : { unit: 1, integration: 1 };
  for (const [group, count] of Object.entries(minimum)) {
    const result = summary.groups[group];
    if (result.tests < count || result.failures || result.errors || result.skipped) {
      throw new Error(`The ${group} reports are incomplete or contain unsuccessful tests`);
    }
  }
}
