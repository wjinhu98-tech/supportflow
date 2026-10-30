import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { renderJUnit, sanitizeJUnit, summarizeCases } from "../.github/scripts/ci-reports.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8").replace(/\r\n/g, "\n");
const workflow = read(".github/workflows/agent-evaluation.yml");
const pom = read("pom.xml");

function job(name) {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.ok(start >= 0, `missing ${name} job`);
  const rest = workflow.slice(start + 1);
  const next = rest.slice(1).search(/\n  [a-z][a-z-]*:\n/);
  return next < 0 ? rest : rest.slice(0, next + 1);
}

test("every PR and main push runs the complete checks without path filters", () => {
  const triggers = workflow.slice(workflow.indexOf("\non:\n"), workflow.indexOf("\npermissions:\n"));
  assert.match(triggers, /\n  pull_request:\n/);
  assert.match(triggers, /\n  push:\n    branches: \[main\]/);
  assert.match(triggers, /\n  workflow_dispatch:\n/);
  assert.match(triggers, /include_images:[\s\S]*type: boolean\n\s+default: false/);
  assert.doesNotMatch(triggers, /paths(?:-ignore)?:|pull_request_target|workflow_run/);
  for (const name of ["backend-live", "frontend-mock", "automation"]) {
    assert.doesNotMatch(job(name), /^    if:/m, `${name} must not skip checks by event or source path`);
  }
  assert.match(job("checks"), /if: always\(\)/);
  assert.match(job("checks"), /needs: \[backend-live, frontend-mock, automation\]/);
  for (const result of ["BACKEND_RESULT", "FRONTEND_RESULT", "AUTOMATION_RESULT"]) {
    assert.ok(job("checks").includes(`test "$${result}" = success`));
  }
});

test("verify binds all integration tests and live browser tests reuse that build", () => {
  const failsafe = pom.match(/<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-failsafe-plugin<\/artifactId>([\s\S]*?)<\/plugin>/)?.[1];
  assert.ok(failsafe, "Failsafe plugin must exist");
  assert.match(failsafe, /<executions>[\s\S]*<goal>integration-test<\/goal>[\s\S]*<goal>verify<\/goal>/);
  assert.match(failsafe, /<failIfNoTests>true<\/failIfNoTests>/);
  const backend = job("backend-live");
  assert.match(backend, /scripts\/setup-rag-model\.ps1/);
  assert.match(backend, /mvn -B -ntp -Dspring\.test\.context\.cache\.maxSize=4 verify/);
  assert.doesNotMatch(backend, /-D(?:skipTests|maven\.test\.skip|test=|it\.test=)/);
  assert.match(backend, /java -cp [^\n]+ dev\.supportflow\.frontend\.FrontendTestServer/);
  assert.match(backend, /E2E_BACKEND_URL: http:\/\/127\.0\.0\.1:18081/);
  assert.match(backend, /FRONTEND_TEST_BACKEND_READY/);
  assert.match(backend, /trap 'kill/);
  assert.match(backend, /playwright install --with-deps chromium/);
  assert.match(backend, /run test:e2e/);
  assert.match(workflow, /PLAYWRIGHT_CHANNEL: chromium/);
});

test("frontend checks cover locked dependencies, types, every unit test, production build and mocks", () => {
  const frontend = job("frontend-mock");
  for (const command of ["npm ci --ignore-scripts", "vue-tsc --noEmit", "npm run test --", "npm run build -- --emptyOutDir false", "playwright install --with-deps chromium", "npm run test:e2e --"]) {
    assert.ok(frontend.includes(command), `missing ${command}`);
  }
  assert.match(frontend, /frontend-unit\.xml/);
  assert.match(frontend, /frontend-mock\.xml/);
  assert.doesNotMatch(frontend, /E2E_BACKEND_URL:/);
  assert.match(job("automation"), /scripts\/\*\.test\.mjs deploy\/\*\.test\.mjs/);
});

test("official actions are immutable pins and CI has no publication credentials or write permissions", () => {
  const pins = {
    "actions/checkout": "3d3c42e5aac5ba805825da76410c181273ba90b1",
    "actions/setup-java": "b843ebf9b913146b38da2eaa7aeddf76074ae1ad",
    "actions/setup-node": "949feb2413d6458794dcd2491c4babbbce0c15c1",
    "actions/upload-artifact": "cf430e030ddbb5b0abf93d22962f4752f3646cd9",
    "actions/download-artifact": "9000827ccba6bdab643e8b6fd33ac0654aef8333",
  };
  const actions = [...workflow.matchAll(/uses:\s*([^\s@]+)@([^\s#]+)/g)];
  assert.ok(actions.length > 10);
  for (const [, name, sha] of actions) assert.equal(sha, pins[name], `unverified action ${name}`);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.doesNotMatch(workflow, /(?:contents|packages|actions|id-token):\s*write|secrets\.|pull_request_target|docker\s+(?:login|push)|gh\s+(?:release|api)|DEEPSEEK_API_KEY|real-model-evaluation/i);
  assert.equal((workflow.match(/persist-credentials: false/g) ?? []).length, 4);
  assert.equal((workflow.match(/clean: false/g) ?? []).length, 4);
  assert.doesNotMatch(workflow, /mvn[^\n]*\bclean\b|rm\s+-rf|Remove-Item\s+-Recurse/);
});

test("delivery follows all green checks and builds from this run's verified artifacts", () => {
  const delivery = job("delivery");
  assert.match(delivery, /needs: \[checks\]/);
  assert.match(delivery, /if: github\.event_name != 'pull_request'/);
  assert.match(delivery, /name: backend-build-\$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}/);
  assert.match(delivery, /name: frontend-build-\$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}/);
  assert.match(delivery, /docker build --file deploy\/backend\.Dockerfile/);
  assert.match(delivery, /docker build --file deploy\/bundle\/frontend\.Dockerfile/);
  assert.match(delivery, /docker save [^\n]+mysql:8\.0\.45 prom\/prometheus:v3\.2\.1/);
  assert.match(delivery, /node scripts\/build-delivery\.mjs --sha "\$GITHUB_SHA" --built-at "\$DELIVERY_BUILT_AT" --with-images/);
  assert.match(delivery, /if: env\.DELIVERY_INCLUDE_IMAGES == 'true'/);
  assert.match(delivery, /if \[ "\$DELIVERY_INCLUDE_IMAGES" = true \]; then[\s\S]*--with-images\n\s+else\n\s+node scripts\/build-delivery\.mjs --sha "\$GITHUB_SHA" --built-at "\$DELIVERY_BUILT_AT"\n/);
  assert.match(delivery, /target\/delivery\/\*\.zip\n\s+target\/delivery\/\*\.zip\.sha256/);
  assert.match(delivery, /sourceSha: sha, checks/);
  assert.match(delivery, /sourceSha: process\.env\.GITHUB_SHA, images/);
  assert.match(delivery, /GITHUB_STEP_SUMMARY/);
  const ignore = read(".dockerignore");
  assert.match(ignore, /^\*\*$/m);
  assert.match(ignore, /^!frontend\/dist\/\*\*$/m);
  assert.match(ignore, /^!deploy\/bundle\/frontend\.Dockerfile$/m);
  assert.ok(ignore.indexOf("**/http-client.private.env.json") > ignore.indexOf("!frontend/dist/**"));
});

test("report artifacts use bounded sanitized output paths and expire after 14 days", () => {
  const uploads = workflow.split(/- (?:name:[^\n]+\n\s+)?(?:if: always\(\)\n\s+)?uses: actions\/upload-artifact@/).slice(1);
  assert.equal(uploads.length, 6);
  for (const upload of uploads) {
    const step = upload.split(/\n      - /)[0];
    assert.match(step, /retention-days: 14/);
    assert.doesNotMatch(step, /path: (?:target\/|\.local\/|frontend\/(?:test-results|playwright-report)\/)(?:\*\*|[ \t]*$)/m);
  }
  for (const mode of ["backend", "live", "frontend", "automation"]) {
    assert.ok(workflow.includes(`node .github/scripts/ci-reports.mjs ${mode}`));
    assert.ok(workflow.includes(`target/ci-reports/${mode}.xml`));
    assert.ok(workflow.includes(`target/ci-reports/${mode}.json`));
  }
});

test("sanitized JUnit preserves only anonymous outcomes and durations", () => {
  const raw = `<testsuite name="secret-suite"><properties><property name="api-key" value="private-provider-key"/></properties>
    <testcase name="private-buyer-name" classname="private-class" time="0.25"><system-out>private-session-cookie</system-out></testcase>
    <testcase name="private-message" time="1.5"><failure message="private-business-order"><![CDATA[private-stacktrace <testcase time="99"/>]]></failure></testcase>
    <testcase name="private-path" time="0"><skipped message="private-reason"/></testcase>
    <testcase time="0.75"><error>private-database-url</error></testcase></testsuite>`;
  const cases = sanitizeJUnit(raw, "unit");
  assert.deepEqual(summarizeCases(cases), { tests: 4, failures: 1, errors: 1, skipped: 1, time: 2.5 });
  const sanitized = renderJUnit(cases, "backend");
  assert.doesNotMatch(sanitized, /private-|api-key|properties|system-out|CDATA/);
  assert.match(sanitized, /name="case-00001" time="0.25"/);
  assert.match(sanitized, /<failure message="See the failed CI step/);
  assert.equal((sanitized.match(/<testcase /g) ?? []).length, 4);
});

test("report sanitizer rejects unsafe XML, malformed cases and unbounded durations", () => {
  for (const input of [
    '<!DOCTYPE testsuite [<!ENTITY secret SYSTEM "file:///private">]><testsuite/>',
    '<testsuite><testcase time="1"></testsuite>',
    '<testsuite><testcase time="Infinity"/></testsuite>',
    '<testsuite><testcase time="-1"/></testsuite>',
    '<testsuite><testcase time="86401"/></testsuite>',
    '<not-junit/>',
  ]) assert.throws(() => sanitizeJUnit(input, "unit"));
  assert.throws(() => sanitizeJUnit("<testsuite/>", "unsafe<group"));
});
