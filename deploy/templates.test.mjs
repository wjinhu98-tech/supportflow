import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(fileURLToPath(new URL(path, root)), "utf8");
const compose = read("deploy/compose.yml");
const nginx = read("deploy/nginx.conf");
const sql = read("deploy/sql/init-lab.sh");

function service(name) {
  const services = compose.slice(compose.indexOf("\nservices:\n") + 11);
  const start = services.indexOf(`\n  ${name}:\n`);
  // First service has no leading newline after slicing.
  const offset =
    start < 0 && services.startsWith(`  ${name}:\n`) ? 0 : start + 1;
  assert.ok(
    offset >= 0 && services.slice(offset).startsWith(`  ${name}:\n`),
    `missing ${name}`,
  );
  const rest = services.slice(offset);
  const end = rest.slice(1).search(/\n(?:  [a-z][a-z0-9-]*:|[a-z])/);
  return end < 0 ? rest : rest.slice(0, end + 1);
}

test("lab has a distinct project/database and only three loopback host ports", () => {
  assert.match(compose, /^name: supportflow-lab$/m);
  const ports = [...compose.matchAll(/^\s+- (127\.0\.0\.1:\d+:\d+)$/gm)].map(
    (match) => match[1],
  );
  assert.deepEqual(ports.sort(), [
    "127.0.0.1:18090:8080",
    "127.0.0.1:18091:9091",
    "127.0.0.1:19090:9090",
  ]);
  assert.doesNotMatch(service("mysql"), /^\s+ports:/m);
  assert.doesNotMatch(
    compose,
    /supportflow_rebuild|host\.docker\.internal|network_mode:\s*host/,
  );
  assert.match(sql, /CREATE DATABASE IF NOT EXISTS `supportflow_lab`/);
});

test("runtime does not mount migration/root/ops plaintext secrets and cannot run Flyway", () => {
  const backend = service("backend");
  assert.match(backend, /SPRING_PROFILES_ACTIVE: lab/);
  assert.match(backend, /SPRING_FLYWAY_ENABLED: "false"/);
  assert.match(backend, /lab_app_password/);
  assert.match(backend, /lab_ops_hash/);
  assert.doesNotMatch(
    backend,
    /lab_root_password|lab_migrator_password|lab_ops_password|lab_buyer_hash|lab_merchant_hash/,
  );
  assert.match(backend, /SUPPORTFLOW_SECURITY_AUTHENTICATION_MODE: database/);
  assert.match(backend, /SUPPORTFLOW_SECURITY_AUTHENTICATION_BASIC_ENABLED: "false"/);
  assert.match(backend, /SUPPORTFLOW_SECURITY_AUTHENTICATION_SESSION_TIMEOUT: 30m/);
  assert.match(backend, /SUPPORTFLOW_TRAFFIC_ENABLED: "true"/);
  assert.match(backend, /SUPPORTFLOW_RAG_PERSISTENT_INDEX_ENABLED: "true"/);
  assert.match(service("seed-accounts"), /secrets: \[lab_migrator_password, lab_buyer_hash, lab_merchant_hash\]/);
  assert.doesNotMatch(service("seed-accounts"), /lab_root_password|lab_ops_hash|lab_ops_password/);
  assert.match(service("prometheus"), /secrets: \[lab_ops_password\]/);
  assert.doesNotMatch(service("frontend"), /secrets:/);
  const networks =
    compose.split("\nnetworks:\n")[1]?.split("\nvolumes:\n")[0] ?? "";
  assert.match(networks, /  database:\n    internal: true/);
  assert.equal((networks.match(/internal: true/g) ?? []).length, 1);
  assert.match(networks, /  web:\n    driver: bridge/);
  assert.match(networks, /  monitoring:\n    driver: bridge/);
  assert.match(service("mysql"), /^    networks: \[database\]$/m);
  assert.match(service("grant"), /^    networks: \[database\]$/m);
  assert.match(service("frontend"), /^    networks: \[web\]$/m);
  assert.match(service("prometheus"), /^    networks: \[monitoring\]$/m);
  assert.match(backend, /^    networks: \[database, web, monitoring\]$/m);
});

test("migration, post-schema grants, seeding and runtime are ordered by success", () => {
  assert.match(service("migrate"), /mysql:[\s\S]*condition: service_healthy/);
  assert.match(
    service("grant"),
    /migrate:[\s\S]*condition: service_completed_successfully/,
  );
  assert.match(
    service("seed"),
    /grant:[\s\S]*condition: service_completed_successfully/,
  );
  assert.match(
    service("seed-accounts"),
    /seed:[\s\S]*condition: service_completed_successfully/,
  );
  assert.match(service("backend"), /seed-accounts:[\s\S]*condition: service_completed_successfully/);
  assert.match(service("seed-accounts"), /command: \[seed-accounts\]/);
  assert.match(compose, /dev\.supportflow\.deployment\.LabDeploymentTool/);
  assert.match(
    compose,
    /org\.springframework\.boot\.loader\.launch\.PropertiesLauncher/,
  );
});

test("app writes remain table/column scoped with no authorization-table mutation", () => {
  const grants = sql.match(/^GRANT .+ TO 'lab_app'@'%';$/gm) ?? [];
  assert.ok(grants.length > 1);
  const reads = grants.filter((grant) => /^GRANT SELECT ON/.test(grant));
  assert.equal(reads.length, 1);
  assert.match(reads[0], /`supportflow\\_lab`\.\*/);
  for (const grant of grants.filter((value) => !reads.includes(value))) {
    assert.doesNotMatch(
      grant,
      /DELETE|CREATE|ALTER|DROP|GRANT OPTION|\.\*|support_merchant/,
    );
    assert.match(
      grant,
      /^GRANT (?:INSERT(?:, UPDATE)?|UPDATE \(status, received_at\)) ON `supportflow_lab`\.`support_[a-z_]+` TO/,
    );
  }
  assert.match(
    sql,
    /GRANT SELECT, INSERT, UPDATE, CREATE, INDEX, ALTER ON `supportflow\\_lab`\.\* TO 'lab_migrator'/,
  );
  const deletionGrants = grants.filter((grant) =>
    grant.includes("`support_order_buyer_deletion`"),
  );
  assert.deepEqual(deletionGrants, [
    "GRANT INSERT ON `supportflow_lab`.`support_order_buyer_deletion` TO 'lab_app'@'%';",
  ]);
  const importGrants = grants.filter((grant) =>
    grant.includes("`support_knowledge_import`"),
  );
  assert.deepEqual(importGrants, [
    "GRANT INSERT ON `supportflow_lab`.`support_knowledge_import` TO 'lab_app'@'%';",
  ]);
  assert.deepEqual(grants.filter((grant) => grant.includes("`support_knowledge_vector`")), [
    "GRANT INSERT ON `supportflow_lab`.`support_knowledge_vector` TO 'lab_app'@'%';",
  ]);
  assert.equal(grants.filter((grant) => grant.includes("`support_security_account`")).length, 0,
    "account reads use the existing SELECT grant and account writes must never be granted");
});

test("account bootstrap copies only the two existing business identities and preserves accounts", () => {
  const tool = read("src/main/java/dev/supportflow/deployment/LabDeploymentTool.java");
  const profile = read("src/main/resources/application-lab.yml");
  assert.match(tool, /case "seed-accounts" -> seedAccounts\(\)/);
  assert.match(tool, /importMissingConfiguredAccounts\(accounts\)/);
  assert.match(tool, /new LocalCustomerAccount\("lab-buyer", accountHash\("lab_buyer_hash"\)/);
  assert.match(tool, /new LocalCustomerAccount\("lab-merchant", accountHash\("lab_merchant_hash"\)/);
  assert.match(tool, /new CustomerIdentity\(200\)/);
  assert.match(tool, /new CustomerIdentity\(999\)/);
  assert.doesNotMatch(tool, /new LocalCustomerAccount\("lab-ops"/);
  assert.match(tool, /securityAccountsReadOnly=true knowledgeVectorsAppendOnly=true/);
  assert.match(profile, /authentication:\n      mode: database\n      basic-enabled: false\n      session-timeout: 30m/);
  assert.doesNotMatch(profile, /lab_buyer_hash|lab_merchant_hash/);
});

test("password handling uses only mounted files and one precise temporary cleanup", () => {
  assert.doesNotMatch(sql, /\r/);
  assert.match(sql, /\/run\/secrets\/\$1/);
  assert.match(sql, /umask 077/);
  assert.match(sql, /\[!A-Za-z0-9_-\]/);
  assert.match(sql, /--defaults-extra-file="\$client_file"/);
  assert.doesNotMatch(
    sql,
    /--password=|set -x|rm\s+-rf|rm\s+-r\b|find .*?-delete/,
  );
  const cleanup = sql.split("\n").filter((line) => /\brm\b/.test(line));
  assert.deepEqual(
    cleanup.map((line) => line.trim()),
    ["trap 'rm -f /tmp/supportflow-lab-root-client.cnf' EXIT"],
  );
});

test("proxy preserves security headers, marks responses and refuses management forwarding/retries", () => {
  assert.match(nginx, /listen 8080;/);
  assert.match(
    nginx,
    /add_header X-SupportFlow-Environment isolated-lab always;/,
  );
  assert.match(nginx, /location = \/actuator \{ return 404; \}/);
  assert.match(nginx, /location \^~ \/actuator\/ \{ return 404; \}/);
  assert.match(nginx, /resolver 127\.0\.0\.11 valid=10s ipv6=off;/);
  assert.match(nginx, /set \$lab_backend backend:8080;/);
  assert.match(nginx, /proxy_pass http:\/\/\$lab_backend;/);
  for (const header of ["Authorization", "Cookie", "X-CSRF-TOKEN"])
    assert.match(nginx, new RegExp(`proxy_set_header ${header} `));
  assert.match(nginx, /proxy_pass_header Set-Cookie;/);
  assert.match(nginx, /proxy_next_upstream off;/);
  assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html;/);
  assert.doesNotMatch(
    nginx.replace(/^\s*#.*$/gm, ""),
    /backend:9091|Access-Control-Allow-Origin|proxy_hide_header (?:Set-Cookie|Authorization)/,
  );
});

test("access log excludes order paths, queries, addresses, credentials and message content", () => {
  const format = nginx.match(/log_format lab_minimal '([^']+)';/);
  assert.ok(format);
  assert.equal(
    format[1],
    "$request_method $status $request_time $upstream_status",
  );
  assert.match(nginx, /access_log \/dev\/stdout lab_minimal;/);
  assert.doesNotMatch(
    format[1],
    /\$(?:request(?:_uri)?\b|remote_addr|http_|args|query_string|request_body)/,
  );
});

test("accepted text imports fit the in-memory request buffer", () => {
  assert.match(nginx, /client_max_body_size 128k;/);
  assert.match(nginx, /client_body_buffer_size 128k;/);
  assert.match(nginx, /client_body_in_file_only off;/);
});

test("image inputs are allowlisted, runtimes are non-root and no build recursively cleans output", () => {
  const backend = read("deploy/backend.Dockerfile");
  const frontend = read("deploy/frontend.Dockerfile");
  const ignore = read(".dockerignore");
  assert.match(backend, /FROM eclipse-temurin:21\./);
  assert.match(
    backend,
    /COPY --chown=10001:10001 target\/supportflow-backend-0\.1\.0-SNAPSHOT\.jar \/app\/app\.jar/,
  );
  assert.match(backend, /USER 10001:10001/);
  assert.doesNotMatch(backend, /RUN .*mvn|COPY \. /);
  assert.match(frontend, /FROM node:22\./);
  assert.match(frontend, /FROM nginxinc\/nginx-unprivileged:/);
  assert.match(frontend, /USER 101:101/);
  assert.match(frontend, /--emptyOutDir false/);
  assert.doesNotMatch(frontend, /COPY \. |rm\s+-rf|rm\s+-r\b/);
  assert.match(ignore, /^\*\*$/m);
  assert.match(ignore, /!target\/supportflow-backend-0\.1\.0-SNAPSHOT\.jar/);
  assert.match(ignore, /\*\*\/\.local\/\*\*/);
  assert.match(ignore, /\*\*\/node_modules\/\*\*/);
});

test("monitoring uses dedicated password_file and health checks contain no credentials", () => {
  const prometheus = read("deploy/prometheus.yml");
  assert.match(prometheus, /targets: \[backend:9091\]/);
  assert.match(prometheus, /metrics_path: \/actuator\/prometheus/);
  assert.match(prometheus, /username: lab-ops/);
  assert.match(prometheus, /password_file: \/run\/secrets\/lab_ops_password/);
  assert.doesNotMatch(prometheus, /^\s+password:/m);
  assert.match(service("backend"), /GET \/actuator\/health\/readiness/);
  assert.doesNotMatch(
    service("backend"),
    /--user|Authorization: Basic|password_file/,
  );
  for (const name of ["backend", "mysql", "frontend", "prometheus"]) {
    assert.match(service(name), /cpus: /);
    assert.match(service(name), /mem_limit: /);
  }
});
