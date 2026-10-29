/** Native Node cookie client for fixed local deployment probes. No passwords or cookies are logged. */
export function createSessionClient(
  origin,
  {
    cookieName = "SF_LAB_SESSION",
    marker = "isolated-lab",
    fetcher = fetch,
  } = {},
) {
  const base = new URL(origin);
  if (
    !["http:", "https:"].includes(base.protocol) ||
    !["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) ||
    base.username ||
    base.password ||
    base.pathname !== "/" ||
    base.search ||
    base.hash ||
    !/^[A-Za-z0-9_-]+$/.test(cookieName)
  )
    throw new Error("SESSION_TARGET_INVALID");
  let cookie = null;
  let username = null;

  function remember(response) {
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(";", 1)[0];
      if (!pair.startsWith(`${cookieName}=`)) continue;
      const token = pair.slice(cookieName.length + 1);
      if (!token || /(?:^|;)\s*Max-Age=0(?:;|$)/i.test(value)) cookie = null;
      else {
        if (!/^[A-Za-z0-9._-]+$/.test(token))
          throw new Error("SESSION_COOKIE_INVALID");
        cookie = pair;
      }
    }
  }

  async function send(endpoint, options = {}) {
    if (
      typeof endpoint !== "string" ||
      !endpoint.startsWith("/") ||
      endpoint.startsWith("//") ||
      endpoint.includes("\\")
    )
      throw new Error("SESSION_TARGET_INVALID");
    const url = new URL(endpoint, base);
    if (url.origin !== base.origin || url.hash)
      throw new Error("SESSION_TARGET_INVALID");
    const headers = new Headers(options.headers);
    if (headers.has("Authorization") || headers.has("Cookie"))
      throw new Error("SESSION_HEADER_INVALID");
    headers.set("Accept", headers.get("Accept") ?? "application/json");
    if (cookie) headers.set("Cookie", cookie);
    if (username) headers.set("X-Session-Account", username);
    const response = await fetcher(url, {
      ...options,
      headers,
      redirect: "error",
      cache: "no-store",
      signal: options.signal ?? AbortSignal.timeout(15_000),
    });
    if (
      marker &&
      response.headers.get("x-supportflow-environment") !== marker
    ) {
      await response.body?.cancel().catch(() => undefined);
      throw new Error("SESSION_ENVIRONMENT_INVALID");
    }
    remember(response);
    return response;
  }

  async function csrf() {
    const response = await send("/api/v1/security/csrf", { method: "GET" });
    if (
      response.status !== 200 ||
      !response.headers.get("content-type")?.includes("application/json")
    )
      throw new Error("SESSION_CSRF_INVALID");
    const chunks = [];
    let length = 0;
    for await (const chunk of response.body ?? []) {
      length += chunk.byteLength;
      if (length > 16_384) throw new Error("SESSION_CSRF_INVALID");
      chunks.push(chunk);
    }
    const value = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)),
    );
    if (
      value === null ||
      typeof value !== "object" ||
      typeof value.headerName !== "string" ||
      !/^X-[A-Za-z0-9-]+$/i.test(value.headerName) ||
      typeof value.token !== "string" ||
      !value.token ||
      value.token.length > 2048 ||
      /[\r\n]/.test(value.token) ||
      typeof value.parameterName !== "string" ||
      !cookie
    )
      throw new Error("SESSION_CSRF_INVALID");
    return value;
  }

  return {
    fetch: send,
    csrf,
    hasSessionCookie: () => cookie !== null,
    async login(account, password) {
      if (
        typeof account !== "string" ||
        !/^[A-Za-z0-9_.@-]{1,128}$/.test(account) ||
        typeof password !== "string" ||
        !password ||
        password.length > 1024
      )
        throw new Error("SESSION_CREDENTIAL_INVALID");
      username = null;
      try {
        const token = await csrf();
        const response = await send("/api/v1/security/login", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            [token.headerName]: token.token,
          },
          body: JSON.stringify({ username: account, password }),
        });
        if (response.status !== 204) throw new Error("SESSION_LOGIN_FAILED");
        username = account;
        await csrf();
      } catch (failure) {
        cookie = null;
        username = null;
        throw failure;
      }
    },
    async logout() {
      if (!cookie || !username) {
        cookie = null;
        username = null;
        return;
      }
      try {
        const token = await csrf();
        const response = await send("/api/v1/security/logout", {
          method: "POST",
          headers: { [token.headerName]: token.token },
        });
        if (response.status !== 204) throw new Error("SESSION_LOGOUT_FAILED");
      } finally {
        cookie = null;
        username = null;
      }
    },
  };
}
