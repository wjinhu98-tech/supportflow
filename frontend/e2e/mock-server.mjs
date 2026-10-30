import http from 'node:http'
import { randomUUID } from 'node:crypto'

// Public, fake browser-test credentials. This server never reads local application secrets.
export const MOCK_USERNAME = 'buyer-demo'
export const MOCK_PASSWORD = 'browser-test-only'
const expectedAuthorization = `Basic ${Buffer.from(`${MOCK_USERNAME}:${MOCK_PASSWORD}`).toString('base64')}`
const slowAuthorization = `Basic ${Buffer.from(`slow-buyer:${MOCK_PASSWORD}`).toString('base64')}`
const sessions = new Map()
const requests = []
const conversations = new Map()
const support = new Map()
const pendingReplies = new WeakMap()
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
const allOrders = [
  ...firstPage,
  ...finalPage,
  ...otherShopOrders,
  makeOrder(102, 'SF102001'),
  makeOrder(102, 'SF102002', { status: 'PAID', statusDescription: '待发货' }),
  makeOrder(103, 'SF103001'),
  makeOrder(103, 'SF103002', { status: 'PAID', statusDescription: '待发货' }),
]

function respond(response, status, value) {
  if (response.destroyed || response.writableEnded) return
  const pending = pendingReplies.get(response)
  if (pending && status === 200 && value?.type) {
    const now = new Date().toISOString()
    const messages = [
      {
        id: randomUUID(),
        role: 'USER',
        text: pending.body.message,
        createdAt: now,
        response: null,
      },
      { id: randomUUID(), role: 'ASSISTANT', text: value.reply, createdAt: now, response: value },
    ]
    pending.stored.messages.push(...messages)
    pending.stored.conversation.updatedAt = now
    value = { conversation: pending.stored.conversation, messages }
    pending.stored.results.set(pending.body.clientMessageId, { body: pending.body, value })
    pendingReplies.delete(response)
  }
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
    conversations.clear()
    support.clear()
    requests.length = 0
    return respond(response, 200, { reset: true })
  }
  if (url.pathname === '/__e2e/requests') return respond(response, 200, requests)

  const sessionId = cookies(request).JSESSIONID
  let session = sessions.get(sessionId)
  const basicUser =
    request.headers.authorization === expectedAuthorization
      ? MOCK_USERNAME
      : request.headers.authorization === slowAuthorization
        ? 'slow-buyer'
        : null
  const owner = basicUser ?? session?.username
  const authenticated = !!owner
  const hasCsrf = !!session && request.headers['x-csrf-token'] === session.token
  const record = {
    method: request.method,
    path: url.pathname + url.search,
    authenticated,
    hasAuthorization: !!request.headers.authorization,
    hasSessionCookie: !!session,
    hasCsrf,
    accepted: false,
  }
  requests.push(record)
  if (url.pathname === '/api/v1/security/csrf' && request.method === 'GET') {
    if (
      session?.username &&
      request.headers['x-session-account'] &&
      request.headers['x-session-account'] !== session.username
    )
      return failure(response, 401, 'UNAUTHENTICATED', '登录状态已变化，请重新登录。')
    if (!session) {
      const id = randomUUID()
      session = { token: randomUUID(), username: null }
      sessions.set(id, session)
      response.setHeader('Set-Cookie', `JSESSIONID=${id}; Path=/; HttpOnly; SameSite=Lax`)
    }
    record.accepted = true
    return respond(response, 200, {
      headerName: 'X-CSRF-TOKEN',
      parameterName: '_csrf',
      token: session.token,
    })
  }

  if (url.pathname === '/api/v1/security/login' && request.method === 'POST') {
    if (!hasCsrf) return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过。')
    sessions.delete(sessionId)
    const body = await bodyOf(request)
    if (
      ![MOCK_USERNAME, 'slow-buyer', 'chat-staff', 'review-only', 'knowledge-only'].includes(
        body.username,
      ) ||
      body.password !== MOCK_PASSWORD
    )
      return failure(response, 401, 'UNAUTHENTICATED', '账号或密码不正确，请重新连接。')
    if (body.username === 'slow-buyer') await new Promise((resolve) => setTimeout(resolve, 1500))
    const id = randomUUID()
    sessions.set(id, { token: randomUUID(), username: body.username })
    response.setHeader('Set-Cookie', `JSESSIONID=${id}; Path=/; HttpOnly; SameSite=Lax`)
    record.accepted = true
    return respond(response, 204)
  }
  if (
    !authenticated ||
    (session?.username &&
      request.headers['x-session-account'] &&
      request.headers['x-session-account'] !== owner)
  )
    return failure(response, 401, 'UNAUTHENTICATED', '登录状态已失效，请重新登录。')
  if (url.pathname === '/api/v1/security/logout' && request.method === 'POST') {
    if (!hasCsrf) return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过。')
    sessions.delete(sessionId)
    response.setHeader('Set-Cookie', 'JSESSIONID=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0')
    record.accepted = true
    return respond(response, 204)
  }
  if (url.pathname === '/api/v1/security/me' && request.method === 'GET') {
    record.accepted = true
    return respond(response, 200, {
      username: owner,
      accountType: ['chat-staff', 'review-only', 'knowledge-only'].includes(owner)
        ? 'MERCHANT_STAFF'
        : 'BUYER',
      capabilities: {
        reviewAfterSales: owner === 'review-only',
        manageKnowledge: owner === 'knowledge-only',
        serveChats: owner === 'chat-staff',
      },
    })
  }
  if (url.pathname === '/api/v1/shops' && request.method === 'GET') {
    record.accepted = true
    return respond(response, 200, [
      { shopId: 100, name: '演示店铺100' },
      { shopId: 101, name: '演示店铺101' },
      { shopId: 102, name: '数码优选店' },
      { shopId: 103, name: '生活家居店' },
    ])
  }
  if (url.pathname === '/api/v1/merchant/shops' && request.method === 'GET') {
    if (!['chat-staff', 'review-only', 'knowledge-only'].includes(owner))
      return failure(response, 403, 'ACCESS_DENIED', '无权查看。')
    return respond(response, 200, [
      { id: 100, name: '演示店铺100', merchantId: 1, merchantName: '演示商家' },
    ])
  }
  const orderList = /^\/api\/v1\/shops\/([1-9][0-9]*)\/orders$/.exec(url.pathname)
  if (orderList && request.method === 'GET') {
    const shopId = Number(orderList[1])
    const afterId = Number(url.searchParams.get('afterId') ?? 0)
    const items =
      shopId === 100
        ? afterId === 0
          ? firstPage
          : finalPage
        : allOrders.filter((order) => order.shopId === shopId)
    record.accepted = true
    return respond(response, 200, {
      items,
      nextAfterId: shopId === 100 && afterId === 0 ? nextCursor : null,
    })
  }
  const orderDetail = /^\/api\/v1\/shops\/([1-9][0-9]*)\/orders\/([^/]+)$/.exec(url.pathname)
  if (orderDetail && request.method === 'GET') {
    const order = allOrders.find(
      (item) =>
        item.shopId === Number(orderDetail[1]) &&
        item.orderNo === decodeURIComponent(orderDetail[2]),
    )
    if (!order) return failure(response, 404, 'ORDER_NOT_FOUND', '未找到订单。')
    record.accepted = true
    return respond(response, 200, order)
  }
  const buyerSupport =
    /^\/api\/v1\/shops\/([1-9][0-9]*)\/chat\/conversations\/([a-f0-9-]+)\/support(?:\/(messages|[a-f0-9-]+)(?:\/(messages|close))?)?$/.exec(
      url.pathname,
    )
  const merchantSupport =
    /^\/api\/v1\/merchant\/shops\/([1-9][0-9]*)\/support-sessions(?:\/([a-f0-9-]+)(?:\/(claim|messages|close))?)?$/.exec(
      url.pathname,
    )
  if (buyerSupport || merchantSupport) {
    const merchant = !!merchantSupport
    const shopId = Number((buyerSupport ?? merchantSupport)[1])
    if (merchant && (owner !== 'chat-staff' || shopId !== 100))
      return failure(response, 403, 'ACCESS_DENIED', '没有人工接待权限。')
    const target = buyerSupport ? conversations.get(buyerSupport[2]) : null
    if (
      buyerSupport &&
      (!target || target.owner !== owner || target.deleted || target.conversation.shopId !== shopId)
    )
      return failure(response, 404, 'NOT_FOUND', '没有此咨询。')
    let entry = buyerSupport
      ? support.get(buyerSupport[2])
      : [...support.values()].find(
          (item) => item.session.id === merchantSupport[2] && item.shopId === shopId,
        )
    const sessionView = (item) => ({
      ...item.session,
      assigned: !!item.assignee,
      assignedToMe: merchant && item.assignee === owner,
    })
    const snapshot = (item, cursor = url.searchParams.get('beforeId')) => {
      const all = item && (!merchant || item.assignee === owner) ? item.messages : []
      const end = cursor ? all.findIndex((message) => message.id === cursor) : all.length
      const messages = all.slice(Math.max(0, end - 30), end)
      return {
        session: item ? sessionView(item) : null,
        messages,
        nextBeforeId: end > 30 ? messages[0].id : null,
      }
    }
    if (merchant && !merchantSupport[2] && request.method === 'GET') {
      const status = url.searchParams.get('status') ?? 'WAITING'
      const items = [...support.values()]
        .filter(
          (item) =>
            item.shopId === shopId &&
            item.session.status === status &&
            (status === 'WAITING' || item.assignee === owner),
        )
        .map(sessionView)
      return respond(response, 200, { items: items.slice(0, 30), nextBeforeId: null })
    }
    if (merchant && (!entry || (entry.assignee && entry.assignee !== owner)))
      return failure(response, 404, 'NOT_FOUND', '此咨询已由其他客服处理。')
    if (
      buyerSupport?.[3] &&
      buyerSupport[3] !== 'messages' &&
      entry?.session.id !== buyerSupport[3]
    )
      return failure(response, 404, 'NOT_FOUND', '没有此人工服务。')
    if (request.method === 'GET') {
      record.accepted = true
      return respond(response, 200, snapshot(entry))
    }
    if (!hasCsrf) return failure(response, 403, 'ACCESS_DENIED', '安全校验未通过。')
    session.token = randomUUID()
    const body = await bodyOf(request)
    record.body = body
    const action = merchant ? merchantSupport[3] : buyerSupport[4]
    if (!merchant && !buyerSupport[3]) {
      const existing = entry?.requests.get(body.requestId)
      if (existing) return respond(response, 200, snapshot(entry))
      if (entry && entry.session.status !== 'CLOSED')
        return failure(response, 409, 'SUPPORT_CONFLICT', '已有人工服务。')
      const now = new Date().toISOString()
      const oldMessages = entry?.messages ?? []
      entry = {
        shopId,
        conversationId: buyerSupport[2],
        assignee: null,
        session: {
          id: randomUUID(),
          status: 'WAITING',
          createdAt: now,
          updatedAt: now,
          closedAt: null,
        },
        messages: oldMessages,
        requests: new Map([[body.requestId, true]]),
      }
      support.set(buyerSupport[2], entry)
      record.accepted = true
      return respond(response, 200, snapshot(entry))
    }
    if (!entry || entry.session.status === 'CLOSED')
      return failure(response, 409, 'SUPPORT_CONFLICT', '人工服务已结束。')
    if (action === 'claim' && merchant) {
      if (entry.session.status !== 'WAITING')
        return failure(response, 409, 'SUPPORT_CONFLICT', '咨询已被接待。')
      entry.assignee = owner
      entry.session.status = 'ACTIVE'
      record.accepted = true
      return respond(response, 200, snapshot(entry))
    }
    if (merchant && entry.assignee !== owner)
      return failure(response, 403, 'ACCESS_DENIED', '请先接待。')
    if (action === 'messages') {
      const previous = entry.messages.find(
        (message) => message.clientMessageId === body.clientMessageId,
      )
      if (previous) return respond(response, 200, previous)
      if (typeof body.text !== 'string' || !body.text.trim() || body.text.length > 2000)
        return failure(response, 400, 'INVALID_ARGUMENT', '请输入消息。')
      const message = {
        id: randomUUID(),
        clientMessageId: body.clientMessageId,
        role: merchant ? 'STAFF' : 'BUYER',
        text: body.text,
        createdAt: new Date().toISOString(),
      }
      entry.messages.push(message)
      record.accepted = true
      return respond(response, 200, message)
    }
    if (action === 'close') {
      entry.session.status = 'CLOSED'
      entry.session.closedAt = new Date().toISOString()
      record.accepted = true
      return respond(response, 200, snapshot(entry))
    }
    return failure(response, 404, 'NOT_FOUND', '没有此接口。')
  }
  const windowMatch = /^\/api\/v1\/shops\/([1-9][0-9]*)\/chat\/window(?:\/([a-f0-9-]+))?$/.exec(
    url.pathname,
  )
  if (windowMatch) {
    const shopId = Number(windowMatch[1])
    const visible = [...conversations.values()].filter(
      (entry) => !entry.deleted && entry.owner === owner && entry.conversation.shopId === shopId,
    )
    const current = visible.find((entry) => entry.window)
    if (request.method === 'GET' && !windowMatch[2]) {
      const messages = visible
        .flatMap((entry) => entry.messages)
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      const cursor = url.searchParams.get('beforeId')
      const end = cursor ? messages.findIndex((message) => message.id === cursor) : messages.length
      if (end < 0) return failure(response, 404, 'CONVERSATION_NOT_FOUND', '未找到该历史记录。')
      const page = messages.slice(Math.max(0, end - 40), end)
      record.accepted = true
      return respond(response, 200, {
        shopId,
        conversation: current?.conversation ?? null,
        messages: page,
        nextBeforeId: end > 40 ? page[0].id : null,
        context:
          current?.messages.filter((message) => message.role === 'ASSISTANT').at(-1)?.response
            ?.context ?? null,
      })
    }
    if (!hasCsrf) return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过。')
    session.token = randomUUID()
    if (request.method === 'POST' && !windowMatch[2]) {
      record.accepted = true
      if (current) return respond(response, 200, current.conversation)
      const conversation = {
        id: randomUUID(),
        shopId,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      conversations.set(conversation.id, {
        owner,
        window: true,
        deleted: false,
        conversation,
        messages: [],
        results: new Map(),
      })
      return respond(response, 200, conversation)
    }
    if (request.method === 'DELETE' && windowMatch[2]) {
      const target = conversations.get(windowMatch[2])
      if (
        !target ||
        target.owner !== owner ||
        target.conversation.shopId !== shopId ||
        !target.window
      )
        return failure(response, 404, 'CONVERSATION_NOT_FOUND', '未找到该店铺对话。')
      if (!target.deleted) for (const entry of visible) entry.deleted = true
      record.accepted = true
      response.statusCode = 204
      return response.end()
    }
    return failure(response, 404, 'NOT_FOUND', '接口不存在。')
  }
  const conversationMatch =
    /^\/api\/v1\/shops\/([1-9][0-9]*)\/chat\/conversations(?:\/([a-f0-9-]+)\/messages)?$/.exec(
      url.pathname,
    )
  let stored
  if (conversationMatch) {
    const shopId = Number(conversationMatch[1])
    const id = conversationMatch[2]
    stored = id ? conversations.get(id) : null
    if (
      id &&
      (!stored || stored.deleted || stored.owner !== owner || stored.conversation.shopId !== shopId)
    )
      return failure(response, 404, 'CONVERSATION_NOT_FOUND', '未找到该会话。')
    if (request.method === 'GET') {
      record.accepted = true
      const beforeId = url.searchParams.get('beforeId')
      if (id) {
        const end = beforeId
          ? stored.messages.findIndex((message) => message.id === beforeId)
          : stored.messages.length
        if (end < 0) return failure(response, 404, 'CONVERSATION_NOT_FOUND', '未找到该历史记录。')
        const messages = stored.messages.slice(Math.max(0, end - 40), end)
        return respond(response, 200, {
          conversation: stored.conversation,
          messages,
          nextBeforeId: end > 40 ? messages[0].id : null,
        })
      }
      const owned = [...conversations.values()]
        .filter(
          (entry) =>
            !entry.deleted && entry.owner === owner && entry.conversation.shopId === shopId,
        )
        .reverse()
      const start = beforeId
        ? owned.findIndex((entry) => entry.conversation.id === beforeId) + 1
        : 0
      if (beforeId && start === 0)
        return failure(response, 404, 'CONVERSATION_NOT_FOUND', '未找到该会话。')
      const page = owned.slice(start, start + 20).map((entry) => entry.conversation)
      return respond(response, 200, {
        conversations: page,
        nextBeforeId: owned.length > start + 20 ? page.at(-1).id : null,
      })
    }
    if (request.method === 'POST' && !id) {
      if (!hasCsrf)
        return failure(response, 403, 'ACCESS_DENIED', '请求安全校验未通过，请重新连接账号。')
      session.token = randomUUID()
      record.body = await bodyOf(request)
      const conversation = {
        id: randomUUID(),
        shopId,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      conversations.set(conversation.id, { owner, conversation, messages: [], results: new Map() })
      record.accepted = true
      return respond(response, 201, conversation)
    }
  }
  const match =
    /^\/api\/v1\/shops\/([1-9][0-9]*)\/chat\/messages$/.exec(url.pathname) ?? conversationMatch
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
  if (stored) {
    if (typeof body.clientMessageId !== 'string')
      return failure(response, 400, 'INVALID_ARGUMENT', '消息编号不正确。')
    const previous = stored.results.get(body.clientMessageId)
    if (previous) {
      if (JSON.stringify(previous.body) !== JSON.stringify(body))
        return failure(response, 409, 'CONVERSATION_CONFLICT', '消息内容已发生变化。')
      return respond(response, 200, previous.value)
    }
    pendingReplies.set(response, { stored, body })
  }
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
  if (message === '触发模型503')
    return failure(response, 503, 'MODEL_UNAVAILABLE', 'private-provider-detail-marker')
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
          : allOrders.filter((order) => order.shopId === shopId)
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
