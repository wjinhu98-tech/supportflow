import { expect, test, type APIRequestContext } from '@playwright/test'
import { connect, send, showChat, showOrders, switchShop } from './helpers'

const mockBase = 'http://127.0.0.1:18080'
type RecordedRequest = {
  method: string
  path: string
  authenticated: boolean
  hasSessionCookie: boolean
  hasCsrf: boolean
  accepted: boolean
  body?: { message: string; orderNo?: string }
}

async function recorded(request: APIRequestContext) {
  const response = await request.get(`${mockBase}/__e2e/requests`)
  return (await response.json()) as RecordedRequest[]
}

test.beforeEach(async ({ request, page }) => {
  await request.post(`${mockBase}/__e2e/reset`)
  await page.goto('/?shopId=100')
})

test('chat is the default view and order navigation opens a separate workspace', async ({
  page,
}) => {
  const chatNavigation = page.getByRole('button', { name: '会话', exact: true })
  const orderNavigation = page.getByRole('button', { name: '查看订单面板', exact: true })
  await expect(chatNavigation).toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('message-input')).toBeVisible()
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.locator('.order-detail')).not.toBeVisible()
  await showOrders(page)
  await expect(chatNavigation).not.toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('message-input')).not.toBeVisible()
  await showChat(page)
  await expect(orderNavigation).not.toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
})

test('switching workspaces preserves the draft and conversation without submitting again', async ({
  page,
  request,
}) => {
  await connect(page)
  await send(page, '查订单 SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('SF10001')
  const messageCount = await page.getByTestId('chat-message').count()
  const postsBefore = (await recorded(request)).filter((entry) => entry.method === 'POST')
  await page.getByTestId('message-input').fill('这笔订单什么时候送达？')
  await showOrders(page)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(20)
  await showChat(page)
  await expect(page.getByTestId('message-input')).toHaveValue('这笔订单什么时候送达？')
  await expect(page.getByTestId('chat-message')).toHaveCount(messageCount)
  await expect(page.getByTestId('chat-message').last()).toContainText('SF10001')
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.locator('.order-detail')).not.toBeVisible()
  expect((await recorded(request)).filter((entry) => entry.method === 'POST')).toEqual(postsBefore)
})

test('a reply received on the orders page is visible at the bottom when returning to chat', async ({
  page,
  request,
}) => {
  await connect(page)
  for (let index = 1; index <= 7; index++) {
    const message = `滚动铺垫${index}：${'请帮我了解店铺咨询服务与商品相关说明。'.repeat(6)}`
    await send(page, message)
    await expect(page.getByTestId('chat-message').last()).toContainText(`收到您的问题：${message}`)
  }
  const messageList = page.getByRole('log', { name: '客服对话', exact: true })
  await expect
    .poll(() => messageList.evaluate((element) => element.scrollHeight > element.clientHeight))
    .toBe(true)
  await messageList.evaluate((element) => {
    element.scrollTop = 0
  })

  const message = '订单页后台回复滚动验收'
  let releaseReply!: () => void
  const replyGate = new Promise<void>((resolve) => {
    releaseReply = resolve
  })
  await page.route('**/api/v1/shops/100/chat/conversations/*/messages', async (route) => {
    if (route.request().postDataJSON()?.message !== message) {
      await route.continue()
      return
    }
    const response = await route.fetch()
    await replyGate
    await route.fulfill({ response })
  })
  const postsBefore = (await recorded(request)).filter((entry) => entry.method === 'POST')
  try {
    const replyReceived = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.request().postDataJSON()?.message === message,
    )
    await send(page, message)
    await expect
      .poll(async () => (await recorded(request)).some((entry) => entry.body?.message === message))
      .toBe(true)
    await showOrders(page)
    releaseReply()
    await (await replyReceived).finished()
    await expect(page.getByTestId('chat-message').last()).toContainText(`收到您的问题：${message}`)
    await showChat(page)
    await expect
      .poll(() =>
        messageList.evaluate(
          (element) => element.scrollTop + element.clientHeight >= element.scrollHeight - 4,
        ),
      )
      .toBe(true)
    await expect(page.getByTestId('chat-message').last()).toBeInViewport()
    const postsAfter = (await recorded(request)).filter((entry) => entry.method === 'POST')
    expect(postsAfter).toHaveLength(postsBefore.length + 1)
    expect(postsAfter.filter((entry) => entry.body?.message === message)).toHaveLength(1)
  } finally {
    releaseReply()
  }
})

test('disconnected users cannot send, and invalid credentials do not connect', async ({ page }) => {
  await expect(page.getByTestId('send-button')).toBeDisabled()
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await page.getByTestId('username-input').fill('buyer-demo')
  await page.getByTestId('password-input').fill('wrong-browser-test-password')
  await page.getByTestId('connect-button').click()
  await expect(page.getByText(/身份验证失败或已失效/).last()).toBeVisible()
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
})

test('proxy forwards Basic authentication, HttpOnly session cookie and CSRF header', async ({
  page,
  request,
}) => {
  await connect(page)
  await showOrders(page)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(20)
  const posts = (await recorded(request)).filter((entry) => entry.method === 'POST')
  expect(posts.length).toBeGreaterThan(0)
  expect(
    posts.every(
      (entry) => entry.authenticated && entry.hasSessionCookie && entry.hasCsrf && entry.accepted,
    ),
  ).toBe(true)
  expect(posts[0]?.path).toBe('/api/v1/shops/100/chat/messages')
  expect(posts[0]?.body).toEqual({ message: '我的订单' })
  const sessionCookie = (await page.context().cookies()).find(
    (cookie) => cookie.name === 'JSESSIONID',
  )
  expect(sessionCookie?.httpOnly).toBe(true)
  expect(sessionCookie?.sameSite).toBe('Lax')
})

test('credentials and CSRF token are not persisted in browser storage', async ({ page }) => {
  await connect(page)
  const storage = await page.evaluate(() =>
    JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }),
  )
  expect(storage).not.toContain('browser-test-only')
  expect(storage).not.toContain('buyer-demo')
  expect(storage).not.toContain(btoa('buyer-demo:browser-test-only'))
  await page.reload()
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
  await expect(page.getByTestId('send-button')).toBeDisabled()
})

test('pagination uses the opaque server cursor and appends without losing earlier orders', async ({
  page,
  request,
}) => {
  await connect(page)
  await showOrders(page)
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(20)
  await page.getByTestId('load-more-orders').click()
  await expect(cards).toHaveCount(21)
  await expect(cards.first()).toContainText('SF10001')
  await expect(cards.last()).toContainText('SF10021')
  await expect(page.getByTestId('load-more-orders')).toHaveCount(0)
  const body = (await recorded(request)).filter((entry) => entry.method === 'POST').at(-1)?.body
  expect(body).toEqual({ message: '我的订单 7347' })
})

test('order selection submits the scoped order and retains it for the next question', async ({
  page,
  request,
}, testInfo) => {
  await connect(page)
  await showOrders(page)
  const firstCard = page.getByTestId('orders-panel').getByTestId('order-card').first()
  await expect(firstCard).toContainText('SF10001')
  await firstCard.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('selected-order')).toContainText('SF10001')
  await expect(page.getByRole('button', { name: '会话', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.locator('.order-detail')).not.toBeVisible()
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await send(page, '现在是什么状态？')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  const bodies = (await recorded(request))
    .filter((entry) => entry.method === 'POST')
    .map((entry) => entry.body)
  expect(bodies).toContainEqual(
    expect.objectContaining({ message: '咨询此订单', orderNo: 'SF10001' }),
  )
  expect(bodies.at(-1)).toEqual(
    expect.objectContaining({ message: '现在是什么状态？', orderNo: 'SF10001' }),
  )
  await page.getByTestId('chat-message').last().scrollIntoViewIfNeeded()
  await page.waitForTimeout(350)
  await page.screenshot({ path: testInfo.outputPath('desktop.png'), fullPage: true })
})

test('natural-language lookup displays the structured order response', async ({ page }) => {
  await connect(page)
  await send(page, '帮我查一下SF10001发货了吗？')
  await expect(page.getByTestId('chat-message').last()).toContainText('SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
})

test('shop switch clears old context and queries the same order number only in the new shop', async ({
  page,
  request,
}) => {
  await connect(page)
  await showOrders(page)
  await page
    .getByTestId('orders-panel')
    .getByTestId('order-card')
    .first()
    .getByRole('button', { name: /^咨询此订单/ })
    .click()
  await expect(page.getByTestId('selected-order')).toContainText('SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await switchShop(page, '101')
  await expect(page.getByTestId('selected-order')).toHaveCount(0)
  await expect(page.getByTestId('chat-message').filter({ hasText: '已发货' })).toHaveCount(0)
  await showOrders(page)
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toContainText('待发货')
  await send(page, '查订单 SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('待发货')
  const lastPost = (await recorded(request)).filter((entry) => entry.method === 'POST').at(-1)
  expect(lastPost?.path).toMatch(
    /^\/api\/v1\/shops\/101\/chat\/conversations\/[a-f0-9-]+\/messages$/,
  )
  expect(lastPost?.body).toEqual(expect.objectContaining({ message: '查订单 SF10001' }))
})

test('empty shop has no invented order cards', async ({ page }) => {
  await connect(page)
  await switchShop(page, '102')
  await showOrders(page)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '暂未加载到订单', exact: true })).toBeVisible()
})

for (const [message, expectedMessage] of [
  ['触发404', /未找到当前店铺内属于你的这笔订单/],
  ['触发403', /无权访问，或 CSRF 校验已失效/],
  ['触发503', /消息结果暂时无法确认/],
] as const) {
  test(`API error ${message} is visible and keeps the connected account`, async ({ page }) => {
    await connect(page)
    await showOrders(page)
    await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(20)
    await send(page, message)
    await expect(page.getByText(expectedMessage).last()).toBeVisible()
    await expect(page.getByTestId('disconnect-button')).toBeVisible()
  })
}

test('401 expires the account and prevents further sends', async ({ page }) => {
  await connect(page)
  await send(page, '触发401')
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
  await expect(page.getByTestId('send-button')).toBeDisabled()
})

test('canceling pending login does not connect later or retain the password', async ({ page }) => {
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await page.getByTestId('username-input').fill('slow-buyer')
  await page.getByTestId('password-input').fill('browser-test-only')
  await page.getByTestId('connect-button').click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '连接你的客服账号' })).not.toBeVisible()
  await page.waitForTimeout(1700)
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await expect(page.getByTestId('password-input')).toHaveValue('')
})

test('a failed message is checked through read-only history instead of replaying its POST', async ({
  page,
  request,
}) => {
  await connect(page)
  await send(page, '触发503')
  await expect(page.getByRole('alert')).toContainText('消息结果暂时无法确认')
  await expect(page.getByTestId('message-input')).toHaveValue('')
  let posts = (await recorded(request)).filter((entry) => entry.body?.message === '触发503')
  expect(posts).toHaveLength(1)
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect
    .poll(async () =>
      (await recorded(request)).some(
        (entry) =>
          entry.method === 'GET' && /\/chat\/conversations\/[^/]+\/messages$/.test(entry.path),
      ),
    )
    .toBe(true)
  posts = (await recorded(request)).filter((entry) => entry.body?.message === '触发503')
  expect(posts).toHaveLength(1)
  expect(posts.every((entry) => entry.accepted && entry.hasCsrf && entry.hasSessionCookie)).toBe(
    true,
  )
})

test('logout ignores a pending response and clears order context', async ({ page, request }) => {
  await connect(page)
  await send(page, '延时回复')
  await expect
    .poll(async () => (await recorded(request)).some((entry) => entry.body?.message === '延时回复'))
    .toBe(true)
  await page.getByTestId('disconnect-button').click()
  // The mock deliberately completes after logout; it must not repopulate account state.
  await page.waitForTimeout(1700)
  await expect(
    page.getByTestId('chat-message').filter({ hasText: '已过期的延时回复' }),
  ).toHaveCount(0)
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(0)
})

test('shop switch cannot display an earlier shop pending reply', async ({ page, request }) => {
  await connect(page)
  await send(page, '延时回复')
  await expect
    .poll(async () => (await recorded(request)).some((entry) => entry.body?.message === '延时回复'))
    .toBe(true)
  await switchShop(page, '101')
  await showOrders(page)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(1)
  await page.waitForTimeout(1700)
  await expect(
    page.getByTestId('chat-message').filter({ hasText: '已过期的延时回复' }),
  ).toHaveCount(0)
})

test('assistant markup is rendered as text, never executable HTML', async ({ page }) => {
  await connect(page)
  await send(page, '显示测试文本')
  const reply = page.getByTestId('chat-message').last()
  await expect(reply).toContainText('<img src=x onerror=')
  await expect(reply.locator('img')).toHaveCount(0)
  expect(
    await page.evaluate(() => (window as Window & { __unsafeExecuted?: boolean }).__unsafeExecuted),
  ).toBeUndefined()
})

test('mobile shop consultation works without document-level horizontal overflow', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await connect(page)
  await showOrders(page)
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(20)
  await send(page, '查订单 SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await page.getByTestId('chat-message').last().scrollIntoViewIfNeeded()
  await page.waitForTimeout(350)
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth,
    content: document.documentElement.scrollWidth,
  }))
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport + 1)
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true })
})

test('mobile navigation separates orders from chat and keeps the composer reachable', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('conversation-panel')).toBeVisible()
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.locator('.order-detail')).not.toBeVisible()
  await expect(page.locator('.quick-prompts')).toHaveCount(0)
  await expect(page.getByTestId('message-input')).toBeInViewport()
  await expect(page.getByTestId('send-button')).toBeInViewport()
  await connect(page)
  await page.getByTestId('message-input').fill('查订单 SF10001')
  await showOrders(page)
  await expect(page.getByTestId('conversation-panel')).not.toBeVisible()
  await expect(page.getByTestId('message-input')).not.toBeVisible()
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(20)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
  await page.screenshot({ path: testInfo.outputPath('mobile-orders.png'), fullPage: true })
  await showChat(page)
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.getByTestId('message-input')).toHaveValue('查订单 SF10001')
  await expect(page.getByTestId('message-input')).toBeInViewport()
  await expect(page.getByTestId('send-button')).toBeInViewport()
  await expect(page.getByTestId('send-button')).toBeEnabled()
  await page.getByTestId('send-button').click()
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await expect(page.getByTestId('message-input')).toBeInViewport()
  await expect(page.getByTestId('send-button')).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
  await page.screenshot({ path: testInfo.outputPath('mobile-chat-only.png'), fullPage: true })
})
