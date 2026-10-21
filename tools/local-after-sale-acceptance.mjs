import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { createRequire } from "node:module";

// Explicitly opt in: this creates and approves ONE local demonstration case.
// It never calls a payment provider, changes orders, seeds tables, or handles other cases.
const root = process.cwd();
const origin = "http://127.0.0.1:8080";
const orderNo = "SFDEMO2026102107";
const reason =
  "LOCAL_ACCEPTANCE_20261021: demo damaged item; simulated refund only";
const shop = "/api/v1/shops/100";

function privateLogin(relative) {
  const text = readFileSync(path.join(root, relative), "utf8");
  const value = (name) =>
    text.match(new RegExp(`^\\s*${name}\\s*[=:]\\s*(.*?)\\s*$`, "im"))?.[1];
  return { username: value("username"), password: value("password") };
}

function buyerLogin() {
  const envPath = path.join(root, "http-client.private.env.json");
  if (existsSync(envPath)) {
    let environments;
    try {
      environments = JSON.parse(
        readFileSync(envPath, "utf8").replace(/^\uFEFF/, ""),
      );
    } catch {
      throw new Error(
        "Local HTTP credential file is invalid JSON; contents are not logged",
      );
    }
    for (const value of Object.values(environments)) {
      if (
        value?.username === "customer-200" &&
        typeof value.password === "string" &&
        value.password.length
      ) {
        return { username: value.username, password: value.password };
      }
    }
  }
  return privateLogin(".local/day4-login.txt");
}

function session(login) {
  if (
    typeof login.username !== "string" ||
    typeof login.password !== "string" ||
    !login.password.length
  ) {
    throw new Error(
      "Required local login is missing; existing login files were not changed",
    );
  }
  const authorization = `Basic ${Buffer.from(`${login.username}:${login.password}`, "utf8").toString("base64")}`;
  const cookies = new Map();
  async function request(method, endpoint, body, expected = 200) {
    const headers = {
      Authorization: authorization,
      Accept: "application/json",
    };
    if (method === "POST") {
      // Fresh CSRF from this account's own cookie jar before every mutating request.
      const csrf = await request("GET", "/api/v1/security/csrf");
      headers[csrf.headerName] = csrf.token;
    }
    if (cookies.size)
      headers.Cookie = [...cookies]
        .map(([key, value]) => `${key}=${value}`)
        .join("; ");
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(`${origin}${endpoint}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: "error",
      signal: AbortSignal.timeout(25000),
    });
    for (const header of response.headers.getSetCookie()) {
      const pair = header.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0)
        cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    if (response.status !== expected) {
      await response.body?.cancel();
      throw new Error(
        `${method} ${endpoint}: expected HTTP ${expected}, received ${response.status}`,
      );
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }
  return request;
}

async function verifyReadonlyUi(
  buyerCredentials,
  reviewerCredentials,
  id,
  reference,
) {
  const require = createRequire(path.join(root, "frontend/package.json"));
  const { chromium, expect } = require("@playwright/test");
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  async function check(credentials, reviewer) {
    const context = await browser.newContext();
    try {
      // This UI check must not apply, approve, or retry anything. Login's fixed list query is read-only.
      await context.route("**/api/**", async (route) => {
        const request = route.request();
        if (["GET", "HEAD", "OPTIONS"].includes(request.method()))
          return route.continue();
        if (
          request.method() === "POST" &&
          new URL(request.url()).pathname.endsWith("/chat/messages")
        ) {
          const message = request.postDataJSON()?.message;
          if (
            typeof message === "string" &&
            /^\u6211\u7684\u8ba2\u5355(?:\s+\d+)?$/.test(message)
          ) {
            return route.continue();
          }
        }
        return route.abort();
      });
      const page = await context.newPage();
      await page.goto("http://127.0.0.1:5173/?shopId=100");
      await page.locator(".connect-top-button").click();
      await page.getByTestId("username-input").fill(credentials.username);
      await page.getByTestId("password-input").fill(credentials.password);
      await page.getByTestId("connect-button").click();
      await expect(page.getByTestId("disconnect-button")).toBeVisible();
      await expect(page.getByTestId("connect-form")).not.toBeVisible();
      await page
        .getByTestId(reviewer ? "open-reviews" : "open-after-sales")
        .click();
      if (reviewer) await page.getByTestId("review-tab-history").click();
      const card = page
        .getByTestId(reviewer ? "review-card" : "after-sale-card")
        .filter({ hasText: id });
      await expect(card).toHaveCount(1);
      if ((await card.getAttribute("open")) === null)
        await card.locator("summary").click();
      await expect(card).toContainText(orderNo);
      await expect(card).toContainText("26.80");
      await expect(card.getByTestId("execution-progress")).toHaveAttribute(
        "data-status",
        "SUCCEEDED",
      );
      await expect(card.getByTestId("execution-reference")).toHaveText(
        reference,
      );
      await expect(card.getByTestId("retry-execution")).toHaveCount(0);
    } finally {
      await context.close();
    }
  }
  try {
    await check(buyerCredentials, false);
    await check(reviewerCredentials, true);
    console.log(
      "REAL_FRONTEND_BUYER_PROGRESS=PASS REAL_FRONTEND_REVIEWER_PROGRESS=PASS",
    );
  } finally {
    await browser.close();
  }
}

async function main() {
  assert(
    process.argv.length === 3 && process.argv[2] === "--run-demo",
    "Usage: node tools/local-after-sale-acceptance.mjs --run-demo (one local simulated refund)",
  );
  assert(
    existsSync(
      path.join(
        root,
        "src/main/resources/db/migration/V5__after_sale_execution.sql",
      ),
    ),
    "Run from the supportflow-rebuild repository root",
  );
  let ready = false;
  for (let attempt = 0; attempt < 15; attempt++) {
    try {
      const response = await fetch(`${origin}/api/v1/chat/welcome`, {
        signal: AbortSignal.timeout(1000),
      });
      await response.body?.cancel();
      if (response.ok) {
        ready = true;
        break;
      }
    } catch {
      // A freshly launched JVM may not have bound its port yet.
    }
    await delay(1000);
  }
  assert(ready, "The local backend did not become ready on port 8080");
  const buyerCredentials = buyerLogin();
  const reviewerCredentials = privateLogin(".local/reviewer-login.txt");
  assert.equal(
    buyerCredentials.username,
    "customer-200",
    "Use the dedicated demo buyer only",
  );
  assert.equal(
    reviewerCredentials.username,
    "support-reviewer",
    "Use the independent local reviewer only",
  );
  const buyer = session(buyerCredentials);
  const reviewer = session(reviewerCredentials);
  const original = await buyer("GET", `${shop}/orders/${orderNo}`);
  assert.equal(original.orderNo, orderNo, "Unexpected demonstration order");
  assert.equal(
    original.status,
    "PAID",
    "Do not mutate or restore an order changed by its owner",
  );
  assert.equal(
    Number(original.totalAmount).toFixed(2),
    "26.80",
    "Unexpected demonstration amount",
  );
  assert.equal(original.currency, "CNY");
  await buyer("GET", "/api/v1/reviews/after-sales", undefined, 403);
  await reviewer("GET", "/api/v1/reviews/after-sales");
  console.log(
    "AUTHENTICATION=PASS BUYER_REVIEW_FORBIDDEN=403 INDEPENDENT_REVIEWER=PASS",
  );

  const find = (tickets) =>
    tickets.find(
      (ticket) => ticket.orderNo === orderNo && ticket.shopId === 100,
    );
  let ticket = find(await buyer("GET", `${shop}/after-sales`));
  if (ticket) {
    assert.equal(
      ticket.reason,
      reason,
      "Existing case belongs to another workflow; preserved without approval",
    );
    assert(
      ["PENDING_REVIEW", "APPROVED"].includes(ticket.status),
      "Existing demo has a terminal review decision",
    );
  }
  const message = {
    message: `\u7533\u8bf7\u552e\u540e ${orderNo} ${reason}`,
    orderNo,
  };
  if (!ticket) {
    await buyer("POST", `${shop}/chat/messages`, message);
    ticket = find(await buyer("GET", `${shop}/after-sales`));
    assert(ticket, "The chat did not create a demonstration application");
    assert.equal(ticket.status, "PENDING_REVIEW");
    assert.equal(ticket.execution, null);
  }
  const id = ticket.id;
  assert.match(id, /^[0-9a-f-]{36}$/i);
  assert.equal(
    ticket.reason,
    reason,
    "Only approve the explicit local acceptance application",
  );
  await buyer("POST", `${shop}/chat/messages`, message);
  assert.equal(
    find(await buyer("GET", `${shop}/after-sales`))?.id,
    id,
    "Duplicate submission must preserve its ID",
  );
  const decision = {
    decision: "APPROVE",
    note: "LOCAL_ACCEPTANCE_20261021: approved local simulation only",
  };
  await buyer(
    "POST",
    `/api/v1/reviews/after-sales/${id}/decision`,
    decision,
    403,
  );
  if (ticket.status === "PENDING_REVIEW") {
    const approved = await reviewer(
      "POST",
      `/api/v1/reviews/after-sales/${id}/decision`,
      decision,
    );
    assert.equal(approved.status, "APPROVED");
  }
  for (let attempt = 0; attempt < 16; attempt++) {
    ticket = find(await buyer("GET", `${shop}/after-sales`));
    if (ticket?.execution?.status === "SUCCEEDED") break;
    await delay(2000);
  }
  assert.equal(ticket?.status, "APPROVED");
  assert.equal(
    ticket?.execution?.status,
    "SUCCEEDED",
    "The durable worker did not finish within 32 seconds",
  );
  assert.equal(Number(ticket.execution.amount).toFixed(2), "26.80");
  assert.equal(ticket.execution.currency, "CNY");
  assert.match(ticket.execution.reference, /^SIM-/);
  const reference = ticket.execution.reference;
  console.log(
    `WORKFLOW=SUCCEEDED ORDER=${orderNo} CASE=${id} AMOUNT=26.80 CNY REFERENCE=${reference}`,
  );

  await reviewer(
    "POST",
    `/api/v1/reviews/after-sales/${id}/decision`,
    decision,
    409,
  );
  await reviewer(
    "POST",
    `/api/v1/reviews/after-sales/${id}/execution/retry`,
    undefined,
    409,
  );
  await buyer("POST", `${shop}/chat/messages`, message);
  const again = find(await buyer("GET", `${shop}/after-sales`));
  assert.equal(again.id, id);
  assert.equal(again.execution.reference, reference);
  const reviewed = (
    await reviewer("GET", "/api/v1/reviews/after-sales/history")
  ).find((value) => value.id === id);
  assert.equal(
    reviewed?.execution?.reference,
    reference,
    "Buyer and reviewer must see the same receipt",
  );
  assert(
    !(await buyer("GET", "/api/v1/shops/101/after-sales")).some(
      (value) => value.id === id,
    ),
    "Another shop must not expose this case",
  );
  const after = await buyer("GET", `${shop}/orders/${orderNo}`);
  assert.equal(
    after.status,
    original.status,
    "Simulation must not alter the order status",
  );
  assert.equal(
    after.totalAmount,
    original.totalAmount,
    "Simulation must not alter the order amount",
  );
  const progress = await buyer("POST", `${shop}/chat/messages`, {
    message: "\u552e\u540e\u8fdb\u5ea6",
  });
  assert(
    progress.reply.includes(reference),
    "The chat must return the real stored simulated receipt",
  );
  console.log(
    "DUPLICATE_APPROVAL=409 SUCCESSFUL_TASK_RETRY=409 RECEIPT_UNCHANGED=PASS",
  );
  console.log(
    "BUYER_ADMIN_AGREE=PASS SHOP_ISOLATION=PASS ORDER_UNCHANGED=PASS CHAT_PROGRESS=PASS",
  );
  await verifyReadonlyUi(buyerCredentials, reviewerCredentials, id, reference);
}

main().catch((failure) => {
  // Only our bounded assertion messages/statuses; never dump responses, logins or fetch request objects.
  const safeMessage =
    failure instanceof SyntaxError
      ? "Invalid JSON; response contents are not logged"
      : String(failure.message).split("\n", 1)[0];
  console.error(`LOCAL_ACCEPTANCE_FAILED: ${safeMessage}`);
  process.exitCode = 1;
});
