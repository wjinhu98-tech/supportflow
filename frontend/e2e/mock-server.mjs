import http from 'node:http'
import { randomUUID } from 'node:crypto'

// Public, fake browser-test credentials. This server never reads local application secrets.
export const MOCK_USERNAME = 'buyer-demo'
export const MOCK_PASSWORD = 'browser-test-only'
const expectedAuthorization = `Basic ${Buffer.from(`${MOCK_USERNAME}:${MOCK_PASSWORD}`).toString('base64')}`
const slowAuthorization = `Basic ${Buffer.from(`slow-buyer:${MOCK_PASSWORD}`).toString('base64')}`
const sessions = new Map()
const requests = []
const nextCursor = 7347
const timestamp = '2026-10-20T01:00:00Z'
const makeOrder = (shopId, orderNo, overrides = {}) => ({
  shopId,
  orderNo,
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 128.5,
  currency: 'CNY',
  createdAt: timestamp,
  updatedAt: timestamp,
  ...overrides,
})
const firstPage = Array.from({ length: 20 }, (_, index) => makeOrder(100, `SF${10001 + index}`))
const finalPage = [makeOrder(100, 'SF10021')]
const otherShopOrders = [
  makeOrder(101, 'SF10001', { status: 'PAID', statusDescription: '待发货', totalAmount: 899 }),
]
const allOrders = [...firstPage, ...finalPage, ...otherShopOrders]

function respond(response, status, value) {
  if (response.destroyed || response.writableEnded) return
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  response.end(JSON.stringify(value))
}

function failure(response, status, code, message) {
  respond(response, status, { code, message })
}

function cookies(request) {
  return Object.fromEntries(
    (request.headers.cookie ?? '').split(';').map((entry) => {
      const separator = entry.indexOf('=')
      return separator < 0
        ? ['', '']
        : [entry.slice(0, separator).trim(), entry.slice(separator + 1)]
    }),
  )
}

async function bodyOf(request) {
  const chunks = []
  let length = 0
  for await (const chunk of request) {
    length += chunk.length
    if (length > 65536) throw new Error('Request too large')
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  if (url.pathname === '/__e2e/health') return respond(response, 200, { ready: true })
  if (url.pathname === '/__e2e/reset' && request.method === 'POST') {
    sessions.clear()
    requests.length = 0
    return respond(response, 200, { reset: true })
  }
  if (url.pathname === '/__e2e/requests') return respond(response, 200, requests)

  const authenticated = [expectedAuthorization, slowAuthorization].includes(
    request.headers.authorization,
  )
  const session = sessions.get(cookies(request).JSESSIONID)
  const hasCsrf = !!session && request.headers['x-csrf-token'] === session.token
  const record = {
    method: request.method,
    path: url.pathname + url.search,
    authenticated,
    hasSessionCookie: !!session,
    hasCsrf,
    accepted: false,
  }
  requests.push(record)
  if (!authenticated)
    return failure(response, 401, 'UNAUTHENTICATED', '账号或密码不正确，请重新连接。')

  if (url.pathname === '/api/v1/security/csrf' && request.method === 'GET') {
    if (request.headers.authorization === slowAuthorization) {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      if (response.destroyed) return
    }
    const id = randomUUID()
    const token = randomUUID()
    sessions.set(id, { token })
    response.setHeader('Set-Cookie', `JSESSIONID=${id}; Path=/; HttpOnly; SameSite=Lax`)
    record.accepted = true
    return respond(response, 200, { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token })
  }

  const match = /^\/api\/v1\/shops\/([1-9][0-9]*)\/chat\/messages$/.exec(url.pathname)
  if (request.method !== 'POST' || !match)
    return failure(response, 404, 'NOT_FOUND', '接口不存在。')
  if (!hasCsrf)
    return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过，请重新连接账号。')
  // This application's Spring Security authentication clears the prior CSRF token.
  // Require a new GET before the next POST instead of silently replaying requests.
  session.token = randomUUID()
  let body
  try {
    body = await bodyOf(request)
  } catch {
    return failure(response, 400, 'INVALID_ARGUMENT', '请求格式不正确。')
  }
  record.body = body
  if (typeof body.message !== 'string' || !body.message.trim() || body.message.length > 1000) {
    return failure(response, 400, 'INVALID_ARGUMENT', '消息不能为空且不能超过1000字。')
  }
  record.accepted = true
  const shopId = Number(match[1])
  const message = body.message.trim()
  if (message === '触发401')
    return failure(response, 401, 'UNAUTHENTICATED', '登录状态已失效，请重新连接账号。')
  if (message === '触发403')
    return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过，请重新连接账号。')
  if (message === '触发404')
    return failure(response, 404, 'ORDER_NOT_FOUND', '未找到属于您的订单。')
  if (message === '触发503')
    return failure(response, 503, 'AI_UNAVAILABLE', '智能服务暂时不可用，请稍后重试。')
  if (message === '延时回复') {
    await new Promise((resolve) => setTimeout(resolve, 1500))
    return respond(response, 200, {
      reply: '这是一条已过期的延时回复',
      type: 'TEXT',
      orders: [],
      nextAfterId: null,
    })
  }
  if (message === '显示测试文本') {
    return respond(response, 200, {
      reply: '<img src=x onerror="window.__unsafeExecuted=true">请原样显示这段文本',
      type: 'TEXT',
      orders: [],
      nextAfterId: null,
    })
  }
  const listMatch = /^我的订单(?:\s+(\d+))?$/.exec(message)
  if (listMatch || message === '查订单') {
    const cursor = Number(listMatch?.[1] ?? 0)
    if (cursor !== 0 && cursor !== nextCursor) {
      return failure(response, 400, 'INVALID_ARGUMENT', '必须使用服务端返回的分页游标。')
    }
    const orders =
      shopId === 100
        ? cursor === 0
          ? firstPage
          : finalPage
        : shopId === 101
          ? otherShopOrders
          : []
    return respond(response, 200, {
      reply: orders.length
        ? `请选择当前店铺的一笔订单（${orders.length}笔）。`
        : '您在当前店铺暂无订单。',
      type: 'ORDER_SELECTION',
      orders,
      nextAfterId: shopId === 100 && cursor === 0 ? nextCursor : null,
    })
  }
  const orderNo = /SF\d+/i.exec(message)?.[0].toUpperCase() ?? body.orderNo
  if (orderNo) {
    const order = allOrders.find(
      (candidate) => candidate.shopId === shopId && candidate.orderNo === orderNo,
    )
    if (!order) return failure(response, 404, 'ORDER_NOT_FOUND', '未找到属于您的订单。')
    return respond(response, 200, {
      reply: `店铺 ${shopId} 的订单 ${order.orderNo}：${order.statusDescription}，订单金额 ${order.totalAmount.toFixed(2)} CNY。`,
      type: 'ORDER_DETAILS',
      orders: [order],
      nextAfterId: null,
    })
  }
  respond(response, 200, {
    reply: `收到您的问题：${body.message}`,
    type: 'TEXT',
    orders: [],
    nextAfterId: null,
  })
})

const port = Number(process.env.E2E_MOCK_PORT ?? 18080)
server.listen(port, '127.0.0.1', () =>
  console.log(`Browser-test mock listening at http://127.0.0.1:${port}`),
)
for (const signal of ['SIGTERM', 'SIGINT'])
  process.on(signal, () => server.close(() => process.exit(0)))
