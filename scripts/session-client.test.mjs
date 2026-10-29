import test from "node:test";
import assert from "node:assert/strict";
import { createSessionClient } from "./session-client.mjs";

const origin = "http://127.0.0.1:18090";
const csrf = (token, cookie) =>
  new Response(
    JSON.stringify({
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
      token,
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "x-supportflow-environment": "isolated-lab",
        ...(cookie
          ? {
              "Set-Cookie": `SF_LAB_SESSION=${cookie}; Path=/; HttpOnly; SameSite=Lax`,
            }
          : {}),
      },
    },
  );
const empty = (cookie) =>
  new Response(null, {
    status: 204,
    headers: {
      "x-supportflow-environment": "isolated-lab",
      ...(cookie
        ? {
            "Set-Cookie": `SF_LAB_SESSION=${cookie}; Path=/; HttpOnly; SameSite=Lax`,
          }
        : {}),
    },
  });

test("session probe exchanges a password once, follows cookie rotation, and logs out with fresh CSRF", async () => {
  const responses = [
    csrf("anonymous", "anon"),
    empty("loggedin"),
    csrf("rotated"),
    csrf("write"),
    empty(),
    csrf("logout"),
    empty(),
  ];
  const requests = [];
  const session = createSessionClient(origin, {
    fetcher: async (url, options) => {
      requests.push({ url, options });
      return responses.shift();
    },
  });
  await session.login("lab-buyer", "public-fake-secret");
  const token = await session.csrf();
  await session.fetch("/api/v1/shops/100/chat/messages", {
    method: "POST",
    headers: { [token.headerName]: token.token },
    body: JSON.stringify({ message: "我的订单" }),
  });
  await session.logout();
  assert.equal(session.hasSessionCookie(), false);
  assert.equal(requests.length, 7);
  assert.deepEqual(
    requests.map(({ url }) => url.pathname),
    [
      "/api/v1/security/csrf",
      "/api/v1/security/login",
      "/api/v1/security/csrf",
      "/api/v1/security/csrf",
      "/api/v1/shops/100/chat/messages",
      "/api/v1/security/csrf",
      "/api/v1/security/logout",
    ],
  );
  assert.deepEqual(JSON.parse(requests[1].options.body), {
    username: "lab-buyer",
    password: "public-fake-secret",
  });
  assert.equal(
    requests[2].options.headers.get("Cookie"),
    "SF_LAB_SESSION=loggedin",
  );
  assert.equal(
    requests[4].options.headers.get("X-Session-Account"),
    "lab-buyer",
  );
  assert.equal(requests[6].options.headers.get("X-CSRF-TOKEN"), "logout");
  for (const { options } of requests)
    assert.equal(options.headers.has("Authorization"), false);
});

test("failed login has no Basic fallback and drops the prior cookie", async () => {
  let calls = 0;
  const session = createSessionClient(origin, {
    fetcher: async () =>
      ++calls === 1
        ? csrf("anonymous", "anon")
        : new Response(null, {
            status: 401,
            headers: { "x-supportflow-environment": "isolated-lab" },
          }),
  });
  await assert.rejects(
    session.login("lab-buyer", "public-fake-secret"),
    /SESSION_LOGIN_FAILED/,
  );
  assert.equal(calls, 2);
  assert.equal(session.hasSessionCookie(), false);
});

test("rejects external targets, spoofed environment markers, and caller authentication headers", async () => {
  assert.throws(
    () => createSessionClient("https://example.com"),
    /SESSION_TARGET_INVALID/,
  );
  let calls = 0;
  const session = createSessionClient(origin, {
    fetcher: async () => {
      calls++;
      return new Response("{}");
    },
  });
  await assert.rejects(
    session.fetch("//example.com/data"),
    /SESSION_TARGET_INVALID/,
  );
  await assert.rejects(
    session.fetch("/api/v1/security/me", {
      headers: { Authorization: "Basic fake" },
    }),
    /SESSION_HEADER_INVALID/,
  );
  await assert.rejects(
    session.fetch("/api/v1/security/me"),
    /SESSION_ENVIRONMENT_INVALID/,
  );
  assert.equal(calls, 1);
});
