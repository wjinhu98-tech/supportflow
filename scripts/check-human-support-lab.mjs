import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { createSessionClient } from "./session-client.mjs";

// Fixed disposable teaching deployment. No daily database, paid model, order or refund mutations.
const origin = "http://127.0.0.1:18090";
const buyer = createSessionClient(origin);
const merchant = createSessionClient(origin);
let checks = 0;
let base = null;
let sessionId = null;
let closed = false;
const check = (condition, name) => {
  assert.ok(condition);
  checks++;
  console.log(`${name}=PASS`);
};
async function read(client, path, expected = 200) {
  const response = await client.fetch(path);
  assert.equal(response.status, expected);
  return response.json();
}
async function write(client, path, body, expected = 200) {
  const csrf = await client.csrf();
  const response = await client.fetch(path, {
    method: "POST",
    headers: {
      [csrf.headerName]: csrf.token,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  assert.equal(response.status, expected);
  return response.json();
}
async function main() {
  const marker = await fetch(`${origin}/healthz`, {
    redirect: "error",
    signal: AbortSignal.timeout(5000),
  });
  assert.equal(marker.headers.get("x-supportflow-environment"), "isolated-lab");
  const password = async (role) =>
    (
      await readFile(
        new URL(
          `../.local/deployment-lab/lab_${role}_password`,
          import.meta.url,
        ),
        "utf8",
      )
    ).trim();
  await buyer.login("lab-buyer", await password("buyer"));
  await merchant.login("lab-merchant", await password("merchant"));
  const identity = await read(merchant, "/api/v1/security/me");
  check(identity.capabilities.serveChats === true, "HSL_CHAT_CAPABILITY");
  const conversation = await write(
    buyer,
    "/api/v1/shops/100/chat/conversations",
    undefined,
    201,
  );
  base = `/api/v1/shops/100/chat/conversations/${conversation.id}/support`;
  const request = { requestId: randomUUID() };
  const waiting = await write(buyer, base, request);
  sessionId = waiting.session.id;
  check(
    waiting.session.status === "WAITING" && waiting.messages.length === 0,
    "HSL_REQUEST_WAITING",
  );
  check(
    (await write(buyer, base, request)).session.id === sessionId,
    "HSL_REQUEST_IDEMPOTENT",
  );
  const question = {
    clientMessageId: randomUUID(),
    text: "希望人工客服帮我核实一下情况。",
  };
  const sent = await write(buyer, `${base}/${sessionId}/messages`, question);
  assert.deepEqual(
    await write(buyer, `${base}/${sessionId}/messages`, question),
    sent,
  );
  await write(
    buyer,
    `${base}/${sessionId}/messages`,
    { ...question, text: "另一段内容" },
    409,
  );
  check(true, "HSL_MESSAGE_IDEMPOTENT_AND_CONFLICT");
  const desk = `/api/v1/merchant/shops/100/support-sessions/${sessionId}`;
  check(
    (await read(merchant, desk)).messages.length === 0,
    "HSL_TRANSCRIPT_HIDDEN_BEFORE_CLAIM",
  );
  const active = await write(merchant, `${desk}/claim`);
  check(
    active.session.status === "ACTIVE" &&
      active.session.assignedToMe &&
      active.messages.length === 1,
    "HSL_MERCHANT_CLAIM",
  );
  const reply = {
    clientMessageId: randomUUID(),
    text: "已收到你的问题，请稍候，我来核实。",
  };
  await write(merchant, `${desk}/messages`, reply);
  const transcript = await read(buyer, base);
  check(
    transcript.messages.length === 2 &&
      transcript.messages[1].role === "STAFF" &&
      transcript.messages[1].text === reply.text,
    "HSL_BUYER_SEES_HUMAN_REPLY",
  );
  await write(
    buyer,
    `/api/v1/shops/100/chat/conversations/${conversation.id}/messages`,
    { clientMessageId: randomUUID(), message: "查物流", orderNo: "LAB-000001" },
    409,
  );
  await write(
    buyer,
    "/api/v1/shops/100/chat/messages",
    { message: "查物流", orderNo: "LAB-000001" },
    409,
  );
  check(true, "HSL_AI_PAUSED_IN_BOTH_ROUTES");
  await read(merchant, "/api/v1/merchant/shops/102/support-sessions", 403);
  check(true, "HSL_NO_OTHER_SHOP_ACCESS");
  const ended = await write(merchant, `${desk}/close`);
  closed = true;
  check(
    ended.session.status === "CLOSED" && ended.messages.length === 2,
    "HSL_CLOSE_PRESERVES_HISTORY",
  );
  await write(
    buyer,
    `${base}/${sessionId}/messages`,
    { clientMessageId: randomUUID(), text: "关闭后不能新增。" },
    409,
  );
  const restored = await write(
    buyer,
    `/api/v1/shops/100/chat/conversations/${conversation.id}/messages`,
    { clientMessageId: randomUUID(), message: "查物流", orderNo: "LAB-000001" },
  );
  check(
    restored.messages?.some(
      (message) =>
        message.role === "ASSISTANT" &&
        message.response?.reply?.includes("LAB-000001"),
    ),
    "HSL_AI_RESTORED_AFTER_CLOSE",
  );
  check(
    (await read(buyer, base)).messages.length === 2,
    "HSL_HUMAN_HISTORY_REMAINS",
  );
  console.log(
    `HUMAN_SUPPORT_LAB=PASS checks=${checks} orderWrites=0 refundWrites=0 modelCalls=0`,
  );
}
try {
  await main();
} catch {
  console.error(`HUMAN_SUPPORT_LAB=FAIL passed=${checks}`);
  process.exitCode = 1;
} finally {
  if (base && sessionId && !closed) {
    // Close only the one case created by this run; never delete transcripts or old conversations.
    await write(buyer, `${base}/${sessionId}/close`).catch(() => undefined);
  }
  await buyer.logout().catch(() => undefined);
  await merchant.logout().catch(() => undefined);
}
