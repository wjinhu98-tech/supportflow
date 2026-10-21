import { expect, test, type APIRequestContext } from '@playwright/test'
import { connect, send, switchShop } from './helpers'

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
  const firstCard = page.getByTestId('orders-panel').getByTestId('order-card').first()
  await expect(firstCard).toContainText('SF10001')
  await firstCard.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('selected-order')).toContainText('SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await send(page, '现在是什么状态？')
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  const bodies = (await recorded(request))
    .filter((entry) => entry.method === 'POST')
    .map((entry) => entry.body)
  expect(bodies).toContainEqual({ message: '咨询此订单', orderNo: 'SF10001' })
  expect(bodies.at(-1)).toEqual({ message: '现在是什么状态？', orderNo: 'SF10001' })
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
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toContainText('待发货')
  await send(page, '查订单 SF10001')
  await expect(page.getByTestId('chat-message').last()).toContainText('待发货')
  const lastPost = (await recorded(request)).filter((entry) => entry.method === 'POST').at(-1)
  expect(lastPost?.path).toBe('/api/v1/shops/101/chat/messages')
  expect(lastPost?.body).toEqual({ message: '查订单 SF10001' })
})

test('empty shop has no invented order cards', async ({ page }) => {
  await connect(page)
  await switchShop(page, '102')
  await expect(page.getByTestId('orders-panel').getByTestId('order-card')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '暂未加载到订单', exact: true })).toBeVisible()
})

for (const [message, expectedMessage] of [
  ['触发404', /未找到当前店铺内属于你的这笔订单/],
  ['触发403', /无权访问，或 CSRF 校验已失效/],
  ['触发503', /服务暂时不可用/],
] as const) {
  test(`API error ${message} is visible and keeps the connected account`, async ({ page }) => {
    await connect(page)
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

test('a failed message is retried only after an explicit click', async ({ page, request }) => {
  await connect(page)
  await send(page, '触发503')
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  await expect(page.getByTestId('message-input')).toHaveValue('触发503')
  let posts = (await recorded(request)).filter((entry) => entry.body?.message === '触发503')
  expect(posts).toHaveLength(1)
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect
    .poll(
      async () =>
        (await recorded(request)).filter((entry) => entry.body?.message === '触发503').length,
    )
    .toBe(2)
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  posts = (await recorded(request)).filter((entry) => entry.body?.message === '触发503')
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
