import { readFile, mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createSessionClient } from "./session-client.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const web = "http://127.0.0.1:18090";
const management = "http://127.0.0.1:18091";
const prometheus = "http://127.0.0.1:19090";
const checks = [];
const secretMarkers = [];
const sessions = [];
let failedCheck = "TRANSPORT_OR_JSON";

function ensure(condition, name) {
  if (!condition) {
    failedCheck = name;
    throw new Error("LAB_CHECK_FAILED");
  }
  checks.push(name);
}

async function credentials(role) {
  const password = (
    await readFile(
      path.join(root, ".local/deployment-lab", `lab_${role}_password`),
      "utf8",
    )
  ).trim();
  if (!/^[A-Za-z0-9_-]{24,128}$/.test(password))
    throw new Error("INVALID_LAB_CREDENTIAL");
  const username =
    role === "buyer"
      ? "lab-buyer"
      : role === "merchant"
        ? "lab-merchant"
        : "lab-ops";
  const encoded = Buffer.from(`${username}:${password}`).toString("base64");
  secretMarkers.push(password, encoded);
  return { username, password, encoded };
}

async function call(
  origin,
  endpoint,
  { auth, session, expected = 200, ...options } = {},
) {
  if (session && origin !== web) throw new Error("SESSION_ORIGIN_MISMATCH");
  const init = {
    ...options,
    headers: { ...(auth ? { Authorization: auth } : {}), ...options.headers },
    redirect: "error",
    signal: AbortSignal.timeout(8000),
  };
  const response = session
    ? await session.fetch(endpoint, init)
    : await fetch(origin + endpoint, init);
  ensure(response.status === expected, `HTTP_${expected}_${endpoint}`);
  if (origin === web)
    ensure(
      response.headers.get("x-supportflow-environment") === "isolated-lab",
      "ISOLATED_MARKER",
    );
  return response;
}

async function run() {
  await call(web, "/healthz");
  for (const route of ["/", "/merchant"]) {
    ensure(
      (await (await call(web, route)).text()).includes("<html"),
      `SPA_${route}`,
    );
  }
  const buyer = createSessionClient(web),
    merchant = createSessionClient(web);
  sessions.push(buyer, merchant);
  const buyerCredential = await credentials("buyer"),
    merchantCredential = await credentials("merchant");
  await buyer.login(buyerCredential.username, buyerCredential.password);
  await merchant.login(
    merchantCredential.username,
    merchantCredential.password,
  );
  const ops = `Basic ${(await credentials("ops")).encoded}`;
  await call(web, "/api/v1/security/me", { expected: 401 });
  const me = await (
    await call(web, "/api/v1/security/me", { session: buyer })
  ).json();
  ensure(
    me.username === "lab-buyer" && me.accountType === "BUYER",
    "LAB_BUYER_IDENTITY",
  );
  const order = await (
    await call(web, "/api/v1/shops/100/orders/LAB-000001", { session: buyer })
  ).json();
  ensure(
    order.shopId === 100 &&
      order.orderNo === "LAB-000001" &&
      order.totalAmount === 199.9,
    "LAB_ORDER_FIXTURE",
  );
  await call(web, "/api/v1/shops/100/orders/LAB-OTHER-OWNER", {
    session: buyer,
    expected: 404,
  });
  await call(web, "/api/v1/merchant/shops", { session: buyer, expected: 403 });
  await call(web, "/api/v1/merchant/shops/100/knowledge", {
    session: merchant,
  });
  await call(web, "/api/v1/merchant/shops/101/knowledge", {
    session: merchant,
    expected: 403,
  });
  await call(web, "/api/v1/security/me", { auth: ops, expected: 401 });
  await call(web, "/actuator/prometheus", { expected: 404 });

  // This fixed stateless query verifies cookie-session + CSRF forwarding. It does not save a conversation.
  const csrf = await buyer.csrf();
  ensure(buyer.hasSessionCookie(), "LAB_SESSION_COOKIE_ISOLATED");
  ensure(
    typeof csrf.headerName === "string" && typeof csrf.token === "string",
    "CSRF_SESSION",
  );
  const reply = await (
    await call(web, "/api/v1/shops/100/chat/messages", {
      session: buyer,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({ message: "查询此订单", orderNo: "LAB-000001" }),
    })
  ).json();
  ensure(
    reply.type === "ORDER_DETAILS" && reply.orders[0]?.orderNo === "LAB-000001",
    "STATELESS_QUERY_CSRF",
  );

  for (const group of ["liveness", "readiness"]) {
    const health = await (
      await call(management, `/actuator/health/${group}`)
    ).json();
    ensure(
      health.status === "UP" && !health.components && !health.details,
      `MINIMAL_${group}`,
    );
  }
  await call(management, "/actuator/prometheus", { expected: 401 });
  const metrics = await (
    await call(management, "/actuator/prometheus", { auth: ops })
  ).text();
  ensure(
    metrics.includes("jvm_memory_used_bytes") &&
      metrics.includes("http_server_requests_seconds") &&
      metrics.includes("hikaricp_connections"),
    "JVM_HTTP_POOL_METRICS",
  );
  // AuthorizationFilter is a framework class name, not an exposed Authorization header.
  ensure(!metrics.includes("LAB-000001"), "NO_RAW_ORDER_METRIC_LABEL");
  ensure(
    !secretMarkers.some((value) => metrics.includes(value)) &&
      !metrics.includes("Basic ") &&
      !metrics.includes("Bearer ") &&
      !metrics.includes("{bcrypt}"),
    "NO_CREDENTIAL_METRICS",
  );
  for (const endpoint of [
    "env",
    "configprops",
    "heapdump",
    "logfile",
    "shutdown",
  ]) {
    const response = await fetch(`${management}/actuator/${endpoint}`, {
      headers: { Authorization: ops },
      redirect: "error",
      signal: AbortSignal.timeout(5000),
    });
    ensure(
      [401, 403, 404].includes(response.status),
      `NO_EXPOSURE_${endpoint}`,
    );
  }
  let targetUp = false;
  for (let attempt = 0; attempt < 20 && !targetUp; attempt++) {
    const response = await (
      await call(
        prometheus,
        "/api/v1/query?query=up%7Bjob%3D%22supportflow-backend%22%7D",
      )
    ).json();
    targetUp =
      response.status === "success" &&
      response.data?.result?.some((item) => item.value?.[1] === "1");
    if (!targetUp) await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  ensure(targetUp, "PROMETHEUS_TARGET_UP");
  await buyer.logout();
  await merchant.logout();
  await call(web, "/api/v1/security/me", { session: buyer, expected: 401 });
  const directory = path.join(root, ".local/deployment-checks");
  await mkdir(directory, { recursive: true });
  const report = path.join(
    directory,
    `${new Date().toISOString().replaceAll(":", "-")}.json`,
  );
  await writeFile(
    report,
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        checks,
        businessWrites: 0,
        statelessReadPosts: 1,
        modelCalls: 0,
        result: "PASS",
      },
      null,
      2,
    ),
    { flag: "wx" },
  );
  console.log(
    `DEPLOYMENT_CHECK=PASS checks=${checks.length} businessWrites=0 modelCalls=0`,
  );
  console.log(`REPORT=${path.relative(root, report)}`);
}

run()
  .catch(() => {
    console.error(
      `DEPLOYMENT_CHECK=FAILED code=${failedCheck} lastPassed=${checks.at(-1) ?? "NONE"}; no credentials or response bodies are printed.`,
    );
    process.exitCode = 1;
  })
  .finally(() =>
    Promise.allSettled(sessions.map((session) => session.logout())),
  );
