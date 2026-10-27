import { readFile } from "node:fs/promises";

// Fixed isolated deployment; GET never creates a window or deletes existing chat records.
const origin = "http://127.0.0.1:18090";
let checks = 0;
function ensure(value, label) {
  if (!value) throw new Error(label);
  checks++;
  console.log(`${label}=PASS`);
}
async function credential(role) {
  const password = (
    await readFile(
      new URL(`../.local/deployment-lab/lab_${role}_password`, import.meta.url),
      "utf8",
    )
  ).trim();
  if (!/^[A-Za-z0-9_-]{24,128}$/.test(password))
    throw new Error("SWL_CREDENTIAL");
  return `Basic ${Buffer.from(`lab-${role}:${password}`).toString("base64")}`;
}
async function get(path, authorization, expected = 200) {
  if (!path.startsWith("/api/v1/") || path.includes(".."))
    throw new Error("SWL_TARGET");
  const response = await fetch(new URL(path, origin), {
    method: "GET",
    redirect: "error",
    signal: AbortSignal.timeout(10000),
    headers: { Authorization: authorization, Accept: "application/json" },
  });
  if (response.status !== expected) throw new Error("SWL_HTTP_STATUS");
  return response.json();
}
try {
  const buyer = await credential("buyer");
  const merchant = await credential("merchant");
  const shops = await get("/api/v1/shops", buyer);
  ensure(
    Array.isArray(shops) &&
      shops.length <= 50 &&
      shops.some((shop) => shop.shopId === 102 && shop.name === "数码优选店") &&
      shops.some((shop) => shop.shopId === 103 && shop.name === "生活家居店"),
    "SWL_NEW_SHOP_DIRECTORY",
  );
  ensure(
    shops.every((shop) =>
      Object.keys(shop).every((key) => ["shopId", "name"].includes(key)),
    ),
    "SWL_DIRECTORY_PUBLIC_FIELDS",
  );
  for (const shop of [102, 103]) {
    for (const index of [1, 2]) {
      const number = `SF${shop}00${index}`;
      const order = await get(`/api/v1/shops/${shop}/orders/${number}`, buyer);
      ensure(
        order.shopId === shop &&
          order.orderNo === number &&
          order.currency === "CNY" &&
          ["SHIPPED", "PAID"].includes(order.status),
        `SWL_ORDER_${number}`,
      );
    }
    const window = await get(`/api/v1/shops/${shop}/chat/window`, buyer);
    ensure(
      window.shopId === shop &&
        Array.isArray(window.messages) &&
        window.messages.length <= 40,
      `SWL_WINDOW_READ_${shop}`,
    );
    await get(`/api/v1/merchant/shops/${shop}/knowledge`, merchant, 403);
    ensure(true, `SWL_NO_STAFF_GRANT_${shop}`);
  }
  const granted = await get("/api/v1/merchant/shops", merchant);
  ensure(
    Array.isArray(granted) && granted.length === 1 && granted[0].id === 100,
    "SWL_MERCHANT_SCOPE_PRESERVED",
  );
  await get("/api/v1/shops/102/orders/SF103001", buyer, 404);
  ensure(true, "SWL_CROSS_SHOP_ORDER_GUARD");
  console.log(
    `SHOP_WINDOW_LAB=PASS checks=${checks} businessWrites=0 windowWrites=0 modelCalls=0`,
  );
} catch (failure) {
  const label =
    failure instanceof Error && /^SWL_[A-Z_]+$/.test(failure.message)
      ? failure.message
      : "SWL_CHECK_FAILED";
  console.error(`SHOP_WINDOW_LAB=FAIL reason=${label}`);
  process.exitCode = 1;
}
