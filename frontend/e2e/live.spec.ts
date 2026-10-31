import { expect, test, type Page } from '@playwright/test'
import { connect, send, showChat, showOrders, switchShop, clearShopConversation } from './helpers'
import { sessionAccount } from './session'
import type {
  AfterSaleEligibility,
  AfterSaleTicket,
  ChatResponse,
  ConversationMessagePage,
  ConversationPage,
  MerchantShop,
  ReceiptConfirmation,
} from '../src/types'

const password = 'Frontend-Test-Only!'
const reviewPath = '/api/v1/merchant/shops/100/after-sales'
const buyerHeaders = {
  'X-Session-Account': 'frontend-buyer',
}

async function ownedTickets(page: Page): Promise<AfterSaleTicket[]> {
  const result = await page.request.get('/api/v1/shops/100/after-sales', { headers: buyerHeaders })
  expect(result.status()).toBe(200)
  return result.json()
}

async function eligibilityFor(page: Page, orderNo: string): Promise<AfterSaleEligibility> {
  const result = await page.request.get(
    `/api/v1/shops/100/orders/${encodeURIComponent(orderNo)}/after-sale-eligibility`,
    { headers: buyerHeaders },
  )
  expect(result.status()).toBe(200)
  return result.json()
}

async function chat(page: Page, message: string) {
  const responsePromise = page.waitForResponse((response) => {
    const request = response.request()
    return (
      request.method() === 'POST' &&
      /\/chat\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname) &&
      request.postDataJSON()?.message === message
    )
  })
  await send(page, message)
  const response = await responsePromise
  expect(response.status(), `Chat failed for: ${message}`).toBe(200)
  const turn = (await response.json()) as {
    messages: { role: string; response: ChatResponse | null }[]
  }
  const body = turn.messages.find((message) => message.role === 'ASSISTANT')!.response!
  await expect(page.getByTestId('chat-message').last()).toContainText(body.reply)
  return body
}

async function selectOrder(page: Page, orderNo: string) {
  await showOrders(page)
  const card = page
    .getByTestId('orders-panel')
    .getByTestId('order-card')
    .filter({ has: page.getByRole('button', { name: `咨询此订单 ${orderNo}`, exact: true }) })
  await expect(card).toHaveCount(1)
  await card.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByRole('button', { name: '会话', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await expect(page.getByTestId('orders-panel')).not.toBeVisible()
  await expect(page.getByTestId('chat-message').last()).toContainText(orderNo)
  await expect(page.getByTestId('send-button')).toBeDisabled()
  // Wait for the selected-order POST before submitting another message.
  await expect(page.getByTestId('message-input')).toBeEnabled()
}

async function selectFreshAfterSaleOrder(page: Page) {
  await showOrders(page)
  const usedOrderNos = new Set((await ownedTickets(page)).map((ticket) => ticket.orderNo))
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  const available = await cards.allTextContents()
  let orderNo: string | undefined
  for (const candidate of available.flatMap(
    (text) => text.match(/\b(?:SF\d+|PAGE-\d+)\b/g) ?? [],
  )) {
    if (!usedOrderNos.has(candidate) && (await eligibilityFor(page, candidate)).canSubmit) {
      orderNo = candidate
      break
    }
  }
  expect(
    orderNo,
    'The isolated launcher must have an unused order; tests never delete cases',
  ).toBeTruthy()
  await selectOrder(page, orderNo!)
  return orderNo!
}

async function connectMerchant(page: Page, account = 'frontend-reviewer') {
  await page.goto('/merchant')
  await page.getByTestId('merchant-username').fill(account)
  await page.getByTestId('merchant-password').fill(password)
  await page.getByTestId('merchant-connect').click()
  await expect(page.getByTestId('merchant-workspace')).toBeVisible()
  await expect(page.getByTestId('review-panel')).toBeVisible()
}

// Only runs when E2E_BACKEND_URL points at the isolated Testcontainers launcher.
// These deliberately public test-only accounts must never be copied to local secrets.
test.beforeEach(async ({ page }) => {
  await page.goto('/?shopId=100')
})

test('customer and merchant entry points enforce role and database shop grants', async ({
  page,
}, testInfo) => {
  await expect(page.getByRole('button', { name: '人工审核', exact: true })).toHaveCount(0)
  await page.goto('/merchant')
  await page.getByTestId('merchant-username').fill('frontend-buyer')
  await page.getByTestId('merchant-password').fill(password)
  await page.getByTestId('merchant-connect').click()
  await expect(page.getByRole('alert')).toContainText(/买家|商家|客服/)
  await expect(page.getByTestId('merchant-workspace')).toHaveCount(0)
  await connectMerchant(page)
  const shops = page.getByTestId('merchant-shop-select')
  await expect(shops).toHaveValue('100')
  await expect(shops.locator('option[value="101"]')).toHaveCount(0)
  const headers = {
    'X-Session-Account': 'frontend-reviewer',
  }
  for (const path of [
    '/api/v1/reviews/after-sales',
    '/api/v1/orders',
    '/api/v1/shops/100/chat/conversations',
    '/api/v1/merchant/shops/101/after-sales',
  ]) {
    const response = await page.request.get(path, { headers })
    expect(response.status(), path).toBe(403)
  }
  await page.screenshot({
    path: testInfo.outputPath('merchant-workspace-desktop.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
  await page.screenshot({
    path: testInfo.outputPath('merchant-workspace-mobile.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByTestId('merchant-disconnect').click()
  await connectMerchant(page, 'frontend-reviewer-other')
  await expect(page.getByTestId('merchant-shop-select')).toHaveValue('101')
  await expect(page.getByTestId('merchant-shop-select').locator('option[value="100"]')).toHaveCount(
    0,
  )
})

test('unassigned staff receive no default shop and staff cannot sign into the customer workspace', async ({
  page,
}) => {
  await page.goto('/merchant')
  await page.getByTestId('merchant-username').fill('frontend-unassigned')
  await page.getByTestId('merchant-password').fill(password)
  await page.getByTestId('merchant-connect').click()
  await expect(page.getByTestId('merchant-workspace')).toBeVisible()
  await expect(page.getByTestId('review-panel')).toHaveCount(0)
  await expect(page.getByText(/未.*授权|没有.*店铺|暂无.*店铺|尚未.*授权/).first()).toBeVisible()
  await page.goto('/?shopId=100')
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await page.getByTestId('username-input').fill('frontend-reviewer')
  await page.getByTestId('password-input').fill(password)
  await page.getByTestId('connect-button').click()
  await expect(page.getByRole('alert').last()).toContainText(/买家|商家|客服/)
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
})

test('conversation history survives reconnect without reopening refund forms or replaying writes', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', password)
  await clearShopConversation(page)
  const marker = `历史恢复验收-${Date.now()}`
  const markerRequest = page.waitForRequest(
    (request) => request.method() === 'POST' && request.postDataJSON()?.message === marker,
  )
  await chat(page, marker)
  const conversationId = new URL((await markerRequest).url()).pathname.split('/').at(-2)!
  await selectOrder(page, 'SF10001')
  await chat(page, '申请退款')
  await expect(page.getByRole('dialog', { name: '申请退款', exact: true })).toBeVisible()
  const before = (await ownedTickets(page)).map((ticket) => ticket.id)
  const writes: string[] = []
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/chat\/conversations\/[^/]+\/messages$|\/orders\/[^/]+\/(?:after-sales|receipt-confirmation)$/.test(
        new URL(request.url()).pathname,
      )
    )
      writes.push(request.url())
  })
  await page.reload()
  await expect(page.getByTestId('disconnect-button')).toHaveCount(0)
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('conversation-select')).toHaveCount(0)
  await expect(page.getByTestId('chat-message').filter({ hasText: marker }).first()).toBeVisible()
  await expect(page.getByRole('dialog', { name: '申请退款', exact: true })).toHaveCount(0)
  await expect(page.getByText('历史快照', { exact: false }).first()).toBeVisible()
  expect(writes).toHaveLength(0)
  expect((await ownedTickets(page)).map((ticket) => ticket.id)).toEqual(before)

  await switchShop(page, '101')
  await expect(page.getByTestId('chat-message').filter({ hasText: marker })).toHaveCount(0)
  const foreignShop = await page.request.get(
    `/api/v1/shops/101/chat/conversations/${conversationId}/messages`,
    { headers: buyerHeaders },
  )
  expect(foreignShop.status()).toBe(404)
  await switchShop(page, '100')
  await expect(page.getByTestId('chat-message').filter({ hasText: marker }).first()).toBeVisible()
  expect(writes).toHaveLength(0)

  await expect(page.getByTestId('new-conversation')).toHaveCount(0)
  const secondMarker = `同一店铺继续咨询-${Date.now()}`
  await chat(page, secondMarker)
  // A shop has one window; continuing must not make the earlier messages disappear.
  const originalHistory = await page.request.get(
    `/api/v1/shops/100/chat/conversations/${conversationId}/messages`,
    { headers: buyerHeaders },
  )
  expect(originalHistory.status()).toBe(200)
  const originalMessages = (await originalHistory.json()) as ConversationMessagePage
  expect(originalMessages.messages.some((message) => message.text === marker)).toBe(true)
  expect(originalMessages.messages.some((message) => message.text === secondMarker)).toBe(true)
  await expect(page.getByTestId('chat-message').filter({ hasText: marker }).first()).toBeVisible()
  await expect(
    page.getByTestId('chat-message').filter({ hasText: secondMarker }).first(),
  ).toBeVisible()
  await expect(page.getByRole('dialog', { name: '申请退款', exact: true })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/conversation-history.png', fullPage: true })

  await page.getByTestId('disconnect-button').click()
  await connect(page, 'frontend-other', password)
  await expect(page.getByTestId('chat-message').filter({ hasText: marker })).toHaveCount(0)
  const foreignBuyer = await page.request.get(
    `/api/v1/shops/100/chat/conversations/${conversationId}/messages`,
    {
      headers: {
        'X-Session-Account': 'frontend-other',
      },
    },
  )
  expect(foreignBuyer.status()).toBe(404)
})

test('each shop keeps its own window and explicit deletion leaves the other shops and orders intact', async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000)
  await connect(page, 'frontend-buyer', password)
  await clearShopConversation(page)
  const marker100 = `原店铺记录-${Date.now()}`
  await chat(page, marker100)
  await page.getByRole('button', { name: '切换店铺', exact: true }).click()
  await expect(page.getByTestId('shop-option-102')).toContainText('数码优选店')
  await expect(page.getByTestId('shop-option-103')).toContainText('生活家居店')
  await page.getByTestId('shop-option-102').click()
  await expect(page.getByTestId('shop-confirm')).not.toBeVisible()
  await clearShopConversation(page)
  const marker102 = `数码店铺记录-${Date.now()}`
  const sent102 = page.waitForRequest(
    (request) => request.method() === 'POST' && request.postDataJSON()?.message === marker102,
  )
  await chat(page, marker102)
  const oldId = new URL((await sent102).url()).pathname.split('/').at(-2)!
  const orderResponse = await page.request.get('/api/v1/shops/102/orders/SF102001', {
    headers: buyerHeaders,
  })
  expect(orderResponse.status()).toBe(200)
  const orderBefore = await orderResponse.json()
  await selectOrder(page, 'SF102001')
  await switchShop(page, '103')
  await clearShopConversation(page)
  const marker103 = `家居店铺记录-${Date.now()}`
  await chat(page, marker103)
  await switchShop(page, '102')
  await expect(
    page.getByTestId('chat-message').filter({ hasText: marker102 }).first(),
  ).toBeVisible()
  await expect(page.getByTestId('chat-message').filter({ hasText: marker103 })).toHaveCount(0)
  await expect(page.getByTestId('chat-message').filter({ hasText: marker100 })).toHaveCount(0)
  const deletions: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'DELETE') deletions.push(request.url())
  })
  await page.getByTestId('delete-conversation').click()
  const dialog = page.getByRole('dialog', { name: '删除该店铺对话？' })
  await expect(dialog).toContainText('店铺 102')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(deletions).toHaveLength(0)
  await expect(
    page.getByTestId('chat-message').filter({ hasText: marker102 }).first(),
  ).toBeVisible()
  await clearShopConversation(page)
  expect(deletions).toHaveLength(1)
  expect(
    (
      await page.request.get(`/api/v1/shops/102/chat/conversations/${oldId}/messages`, {
        headers: buyerHeaders,
      })
    ).status(),
  ).toBe(404)
  const orderAfter = await page.request.get('/api/v1/shops/102/orders/SF102001', {
    headers: buyerHeaders,
  })
  expect(await orderAfter.json()).toEqual(orderBefore)
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('chat-message')).toHaveCount(0)
  const fresh = `删除后继续咨询-${Date.now()}`
  await chat(page, fresh)
  const csrfResponse = await page.request.get('/api/v1/security/csrf', { headers: buyerHeaders })
  const csrf = await csrfResponse.json()
  expect(
    (
      await page.request.delete(`/api/v1/shops/102/chat/window/${oldId}`, {
        headers: { ...buyerHeaders, [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(204)
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('chat-message').filter({ hasText: fresh }).first()).toBeVisible()
  await expect(page.getByTestId('chat-message').filter({ hasText: marker102 })).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('shop-window-after-delete.png'),
    fullPage: true,
  })
  await switchShop(page, '103')
  await expect(
    page.getByTestId('chat-message').filter({ hasText: marker103 }).first(),
  ).toBeVisible()
  await switchShop(page, '100')
  await expect(
    page.getByTestId('chat-message').filter({ hasText: marker100 }).first(),
  ).toBeVisible()
  const merchant = await sessionAccount('frontend-reviewer', password)
  try {
    const merchantShops = await merchant.get('/api/v1/merchant/shops')
    expect(merchantShops.status()).toBe(200)
    expect((await merchantShops.json()).map((shop: MerchantShop) => shop.id)).toEqual([100])
  } finally {
    await merchant.dispose()
  }
})

test('real history uses bounded pages for both conversations and older messages', async ({
  page,
}) => {
  test.setTimeout(90_000)
  await connect(page, 'frontend-buyer', password)
  await clearShopConversation(page)
  async function post(path: string, data: unknown = {}) {
    const csrfResponse = await page.request.get('/api/v1/security/csrf', { headers: buyerHeaders })
    expect(csrfResponse.status()).toBe(200)
    const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
    const response = await page.request.post(path, {
      headers: { ...buyerHeaders, [csrf.headerName]: csrf.token },
      data,
    })
    expect(response.ok()).toBe(true)
    return response.json()
  }
  const base = '/api/v1/shops/100/chat/conversations'
  const { id } = (await post(base)) as { id: string }
  for (let index = 1; index <= 22; index++) {
    await post(`${base}/${id}/messages`, {
      clientMessageId: crypto.randomUUID(),
      message: `分页消息-${index}`,
    })
  }
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('conversation-select')).toHaveCount(0)
  await expect(page.getByTestId('chat-message')).toHaveCount(40)
  await page.getByTestId('load-earlier-messages').click()
  await expect(page.getByTestId('chat-message')).toHaveCount(44)
  await expect(page.getByTestId('load-earlier-messages')).toHaveCount(0)

  for (let index = 0; index < 21; index++) await post(base)
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  // Conversation pagination stays available through the API without a history toolbar.
  const firstPage = await page.request.get(base, { headers: buyerHeaders })
  expect(firstPage.status()).toBe(200)
  const first = (await firstPage.json()) as ConversationPage
  expect(first.conversations).toHaveLength(20)
  expect(first.nextBeforeId).toBeTruthy()
  const secondPage = await page.request.get(`${base}?beforeId=${first.nextBeforeId}`, {
    headers: buyerHeaders,
  })
  expect(secondPage.status()).toBe(200)
  const second = (await secondPage.json()) as ConversationPage
  expect(second.conversations.some((conversation) => conversation.id === id)).toBe(true)
  expect(second.conversations.length).toBeLessThanOrEqual(20)
  expect(
    second.conversations.some((conversation) =>
      first.conversations.some((previous) => previous.id === conversation.id),
    ),
  ).toBe(false)
  await expect(page.getByTestId('load-more-conversations')).toHaveCount(0)
})

test('real Spring Security CSRF and real MySQL owned-order selection', async ({ page }) => {
  await connect(page, 'frontend-buyer', 'Frontend-Test-Only!')
  await showOrders(page)
  const panel = page.getByTestId('orders-panel')
  await expect(panel.getByTestId('order-card')).toHaveCount(20)
  const order = panel.getByTestId('order-card').filter({ hasText: 'SF10001' })
  await expect(order).toHaveCount(1)
  await order.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await expect(page.getByTestId('chat-message').last()).toContainText('199.90')
  const cookie = (await page.context().cookies()).find((item) => item.name === 'JSESSIONID')
  expect(cookie?.httpOnly).toBe(true)
  await showOrders(page)
  await page.getByTestId('load-more-orders').click()
  await expect(panel.getByTestId('order-card')).toHaveCount(24)
})

test('real buyer deletion hides an ended order across reads and reconnect while retaining its chat snapshot', async ({
  page,
}, testInfo) => {
  test.setTimeout(60_000)
  const orderNo = 'SFDELETELIVE01'
  const headers = {
    'X-Session-Account': 'frontend-delete-buyer',
  }
  const orderPath = `/api/v1/shops/100/orders/${orderNo}`
  const productQuestion = '我买的删除验收杯发货了吗？'
  async function productLookup(): Promise<ChatResponse> {
    const csrfResponse = await page.request.get('/api/v1/security/csrf', { headers })
    expect(csrfResponse.status()).toBe(200)
    const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
    const response = await page.request.post('/api/v1/shops/100/chat/messages', {
      headers: { ...headers, [csrf.headerName]: csrf.token },
      data: { message: productQuestion },
    })
    expect(response.status()).toBe(200)
    return response.json()
  }
  // This account and product belong only to this case; buyer200's shared 24-order baseline is untouched.
  await connect(page, 'frontend-delete-buyer', password)
  const before = await page.request.get(orderPath, { headers })
  expect(before.status()).toBe(200)
  expect(await before.json()).toMatchObject({ orderNo, status: 'COMPLETED' })
  expect((await productLookup()).orders.map((order) => order.orderNo)).toEqual([orderNo])
  const selected = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/chat\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname) &&
      response.request().postDataJSON()?.orderNo === orderNo,
  )
  await selectOrder(page, orderNo)
  const turn = (await (await selected).json()) as { conversation: { id: string } }
  const historyPath = `/api/v1/shops/100/chat/conversations/${turn.conversation.id}/messages`
  const historyBefore = await page.request.get(historyPath, { headers })
  expect(historyBefore.status()).toBe(200)
  const original = (await historyBefore.json()) as ConversationMessagePage
  expect(
    original.messages.some((message) =>
      message.response?.orders.some((order) => order.orderNo === orderNo),
    ),
  ).toBe(true)
  await showOrders(page)
  const panel = page.getByTestId('orders-panel')
  const card = panel.getByTestId('order-card').filter({ hasText: orderNo })
  await card.getByRole('button', { name: `删除订单 ${orderNo}`, exact: true }).click()
  const deleted = page.waitForResponse(
    (response) =>
      response.request().method() === 'DELETE' && new URL(response.url()).pathname === orderPath,
  )
  await page.getByTestId('confirm-order-deletion').click()
  expect((await deleted).status()).toBe(204)
  await expect(page.getByTestId('order-deletion-dialog')).not.toBeVisible()
  await expect(card).toHaveCount(0)
  expect((await page.request.get(orderPath, { headers })).status()).toBe(404)
  const list = await page.request.get('/api/v1/orders?size=100', { headers })
  expect(list.status()).toBe(200)
  expect((await list.json()).items).toEqual([])
  const productAfter = await productLookup()
  expect(productAfter.orders).toEqual([])
  expect(productAfter.type).not.toBe('ORDER_DETAILS')
  const refresh = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/shops/100/orders',
  )
  await page.getByRole('button', { name: '刷新订单', exact: true }).click()
  const refreshed = await refresh
  expect(refreshed.status()).toBe(200)
  expect((await refreshed.json()).items).toEqual([])
  await expect(panel.getByTestId('order-card')).toHaveCount(0)
  const historyAfter = await page.request.get(historyPath, { headers })
  expect(historyAfter.status()).toBe(200)
  expect(((await historyAfter.json()) as ConversationMessagePage).messages).toEqual(
    original.messages,
  )
  await page.reload()
  await connect(page, 'frontend-delete-buyer', password)
  await showOrders(page)
  await expect(panel.getByTestId('order-card')).toHaveCount(0)
  await showChat(page)
  const snapshot = page
    .getByTestId('chat-message')
    .getByTestId('order-card')
    .filter({ hasText: orderNo })
  await expect(snapshot).toHaveCount(1)
  await expect(
    snapshot.getByRole('button', { name: `删除订单 ${orderNo}`, exact: true }),
  ).toHaveCount(0)
  await expect(page.getByText('历史快照 · 订单信息以重新查询为准')).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('deleted-order-retained-history.png'),
    fullPage: true,
  })
})

test('receipt confirmation needs explicit consent and persists the server receipt and refund deadline', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1600, height: 1000 })
  await connect(page, 'frontend-buyer', password)
  await showOrders(page)
  await page.getByTestId('load-more-orders').click()
  const panel = page.getByTestId('orders-panel')
  await expect(panel.getByTestId('order-card')).toHaveCount(24)
  const historic = panel.getByTestId('order-card').filter({ hasText: 'PAGE-021' })
  await expect(historic.locator('[data-status="COMPLETED"]')).toBeVisible()
  await expect(
    historic.getByRole('button', { name: '确认收货 PAGE-021', exact: true }),
  ).toHaveCount(0)
  await selectOrder(page, 'SFRECEIPT01')
  await showOrders(page)
  const card = panel.getByTestId('order-card').filter({ hasText: 'SFRECEIPT01' })
  const requests: string[] = []
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname.endsWith('/receipt-confirmation')
    )
      requests.push(request.url())
  })
  await card.getByRole('button', { name: '确认收货 SFRECEIPT01', exact: true }).click()
  const dialog = page.getByTestId('receipt-confirmation-dialog')
  await expect(dialog).toContainText('已经收到')
  await expect(dialog).toContainText('售后申请期限')
  expect(requests).toHaveLength(0)
  await dialog.getByRole('button', { name: '暂不确认', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(requests).toHaveLength(0)
  await expect(card.locator('[data-status="SHIPPED"]')).toBeVisible()
  await card.getByRole('button', { name: '确认收货 SFRECEIPT01', exact: true }).click()
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        '/api/v1/shops/100/orders/SFRECEIPT01/receipt-confirmation',
  )
  await dialog.getByTestId('confirm-receipt-submit').click()
  const response = await responsePromise
  expect(response.status()).toBe(200)
  expect(response.request().postData()).toBeNull()
  const result = (await response.json()) as ReceiptConfirmation
  expect(result.order).toMatchObject({ shopId: 100, orderNo: 'SFRECEIPT01', status: 'COMPLETED' })
  expect(result.order.receivedAt).toBeTruthy()
  expect(result.alreadyConfirmed).toBe(false)
  expect(result.eligibility).toMatchObject({
    status: 'ELIGIBLE',
    canSubmit: true,
    receivedAt: result.order.receivedAt,
    windowDays: 15,
  })
  expect(Date.parse(result.eligibility.deadlineAt!) - Date.parse(result.order.receivedAt)).toBe(
    15 * 24 * 60 * 60 * 1000,
  )
  await expect(dialog.getByTestId('receipt-confirmation-result')).toContainText('售后申请截止')
  await expect(dialog.getByTestId('confirm-receipt-submit')).toHaveCount(0)
  await expect(card.locator('[data-status="COMPLETED"]')).toBeVisible()
  await expect(page.getByTestId('order-received-at')).toBeVisible()
  await expect(page.getByTestId('order-after-sale-deadline')).toBeVisible()
  expect(requests).toHaveLength(1)
  await page.screenshot({ path: 'test-results/receipt-confirmation.png', fullPage: true })
  await dialog.getByRole('button', { name: '完成', exact: true }).click()
  await expect(card.getByRole('button', { name: '确认收货 SFRECEIPT01', exact: true })).toHaveCount(
    0,
  )
  await showChat(page)
  await expect(
    page
      .getByTestId('chat-message')
      .last()
      .getByTestId('order-card')
      .locator('[data-status="COMPLETED"]'),
  ).toBeVisible()

  // Deliberately repeat via the isolated public test account, never a business database.
  const csrfResponse = await page.request.get('/api/v1/security/csrf', { headers: buyerHeaders })
  expect(csrfResponse.status()).toBe(200)
  const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
  const repeatedResponse = await page.request.post(
    '/api/v1/shops/100/orders/SFRECEIPT01/receipt-confirmation',
    {
      headers: { ...buyerHeaders, [csrf.headerName]: csrf.token },
    },
  )
  expect(repeatedResponse.status()).toBe(200)
  const repeated = (await repeatedResponse.json()) as ReceiptConfirmation
  expect(repeated.alreadyConfirmed).toBe(true)
  expect(repeated.order.receivedAt).toBe(result.order.receivedAt)
  expect(repeated.eligibility.deadlineAt).toBe(result.eligibility.deadlineAt)
  const eligibility = await eligibilityFor(page, 'SFRECEIPT01')
  expect(eligibility.receivedAt).toBe(result.order.receivedAt)
  expect(eligibility.deadlineAt).toBe(result.eligibility.deadlineAt)
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await showOrders(page)
  await page.getByTestId('load-more-orders').click()
  await expect(panel.getByTestId('order-card')).toHaveCount(24)
  await selectOrder(page, 'SFRECEIPT01')
  await showOrders(page)
  await expect(page.getByTestId('order-after-sale-deadline')).toBeVisible()
  await expect(card.getByRole('button', { name: '确认收货 SFRECEIPT01', exact: true })).toHaveCount(
    0,
  )
})

test('real API keeps same-number orders scoped to the active shop and rejects a missing order', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', 'Frontend-Test-Only!')
  await switchShop(page, '101')
  await showOrders(page)
  const panel = page.getByTestId('orders-panel')
  await expect(panel.getByTestId('order-card')).toHaveCount(1)
  await panel.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('chat-message').last()).toContainText('待发货')
  await expect(page.getByTestId('chat-message').last()).toContainText('899.00')
  await send(page, '查订单 SF10002')
  await expect(page.getByRole('alert').last()).toContainText('未找到当前店铺内属于你的这笔订单')
  await expect(page.getByTestId('chat-message').last()).not.toContainText('已完成')
})

test('real API does not reveal another buyer owned-order details', async ({ page }) => {
  await connect(page, 'frontend-other', 'Frontend-Test-Only!')
  await showOrders(page)
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toContainText('SF20001')
  await send(page, '查订单 SF10001')
  await expect(page.getByRole('alert').last()).toContainText('未找到当前店铺内属于你的这笔订单')
  await expect(page.getByTestId('chat-message').filter({ hasText: '已发货' })).toHaveCount(0)
})

test('real knowledge audit evidence and recorded logistics stay separate from same-number shop orders', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', password)
  const knowledge = await chat(page, '售后审批规则')
  expect(knowledge.type).toBe('TEXT')
  expect(knowledge.knowledge?.status).toBe('ANSWERED')
  expect(knowledge.knowledge?.sources.length).toBeGreaterThan(0)
  expect(knowledge.knowledge?.sources.map((source) => source.sourceId)).toContain(
    'after-sale-review',
  )
  await expect(
    page.getByTestId('chat-message').last().getByTestId('knowledge-sources'),
  ).toHaveCount(0)

  await selectOrder(page, 'SF10001')
  const logistics = await chat(page, '查物流')
  expect(logistics.reply).toContain('学习版仓库：已出库')
  expect(logistics.reply).toContain('学习版配送中心：运输中')
  expect(logistics.reply).toContain('并非实时快递联网查询')

  await switchShop(page, '101')
  await selectOrder(page, 'SF10001')
  const otherShopLogistics = await chat(page, '查物流')
  // The observed PAID order finishes without querying logistics for another shop.
  expect(otherShopLogistics.reply).toContain('店铺 101 的订单 SF10001')
  expect(otherShopLogistics.reply).toContain('待发货')
  expect(otherShopLogistics.reply).toContain('899.00 CNY')
  expect(otherShopLogistics.reply).not.toContain('学习版仓库：已出库')
  expect(otherShopLogistics.reply).not.toContain('学习版配送中心：运输中')
})

test('refund form previews a trusted deadline, requires a reason and creates only on confirmation', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', password)
  await selectOrder(page, 'SF10002')
  const before = await ownedTickets(page)
  const submittedRequests: string[] = []
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/orders\/[^/]+\/after-sales$/.test(new URL(request.url()).pathname)
    )
      submittedRequests.push(request.url())
  })
  const response = await chat(page, '将该订单退款')
  expect(response.type).toBe('AFTER_SALE_FORM')
  expect(response.reply).not.toMatch(/Java|SQL|Tool|PENDING_REVIEW/)
  const form = page.getByRole('dialog', { name: '申请退款', exact: true })
  await expect(form).toBeVisible()
  await expect(form).toContainText('售后期限内')
  await expect(form).toContainText('申请截止')
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeDisabled()
  await form.getByRole('radio', { name: '商铺原因', exact: true }).check()
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeDisabled()
  await form.getByLabel('具体原因', { exact: true }).selectOption({ label: '商品质量问题' })
  await expect(form.locator('textarea')).toHaveCount(0)
  expect(submittedRequests).toHaveLength(0)
  expect((await ownedTickets(page)).map((ticket) => ticket.id)).toEqual(
    before.map((ticket) => ticket.id),
  )
  await page.screenshot({ path: 'test-results/refund-form.png', fullPage: true })
  const resultPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/shops/100/orders/SF10002/after-sales',
  )
  await form.getByRole('button', { name: '确认提交退款申请' }).click()
  const result = await resultPromise
  expect(result.status()).toBe(200)
  const ticket = (await result.json()) as AfterSaleTicket
  expect(ticket).toMatchObject({
    orderNo: 'SF10002',
    reason: '商铺原因：商品质量问题',
    status: 'PENDING_REVIEW',
    eligibility: { status: 'ELIGIBLE', windowDays: 15 },
  })
  await expect(form).toHaveCount(0)
  expect(submittedRequests).toHaveLength(1)
  expect((await ownedTickets(page)).filter((item) => item.orderNo === 'SF10002')).toHaveLength(1)
})

test('expired refunds cannot submit and missing receipt requests explain manual verification', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', password)
  const before = await ownedTickets(page)
  await selectOrder(page, 'PAGE-001')
  await chat(page, '申请退款')
  const form = page.getByRole('dialog', { name: '申请退款', exact: true })
  await expect(form).toContainText('已超过确认收货后15天')
  await form.getByRole('radio', { name: '个人原因', exact: true }).check()
  await form.getByLabel('具体原因', { exact: true }).selectOption({ label: '不想要了' })
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(form).toHaveCount(0)
  await selectOrder(page, 'SF10001')
  await chat(page, '申请退款')
  await expect(form).toContainText('缺少确认收货时间')
  await expect(form).toContainText('客服核实售后期限')
  await expect(form).not.toContainText('申请截止')
  await form.getByRole('radio', { name: '个人原因', exact: true }).check()
  await form.getByLabel('具体原因', { exact: true }).selectOption({ label: '不想要了' })
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeEnabled()
  await form.getByRole('radio', { name: '其他', exact: true }).check()
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeDisabled()
  await form.getByLabel('其他原因', { exact: true }).fill('补充原因，取消前不提交')
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeEnabled()
  await form.getByRole('button', { name: '取消', exact: true }).click()
  await expect(form).toHaveCount(0)
  expect((await ownedTickets(page)).map((ticket) => ticket.id)).toEqual(
    before.map((ticket) => ticket.id),
  )
})

test('Other reason is persisted only after explicit confirmation', async ({ page }) => {
  await connect(page, 'frontend-buyer', password)
  const orderNo = await selectFreshAfterSaleOrder(page)
  const before = await ownedTickets(page)
  await chat(page, '申请退款')
  const form = page.getByRole('dialog', { name: '申请退款', exact: true })
  await form.getByRole('radio', { name: '其他', exact: true }).check()
  const reason = form.getByLabel('其他原因', { exact: true })
  await expect(reason).toHaveAttribute('maxlength', '497')
  await reason.fill('   ')
  await expect(form.getByRole('button', { name: '确认提交退款申请' })).toBeDisabled()
  await reason.fill('收到的商品与客服确认内容不一致，请协助核实')
  expect((await ownedTickets(page)).map((ticket) => ticket.id)).toEqual(
    before.map((ticket) => ticket.id),
  )
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/shops/100/orders/${orderNo}/after-sales`,
  )
  await form.getByRole('button', { name: '确认提交退款申请' }).click()
  const response = await responsePromise
  expect(response.status()).toBe(200)
  expect(await response.json()).toMatchObject({
    orderNo,
    reason: '其他：收到的商品与客服确认内容不一致，请协助核实',
    status: 'PENDING_REVIEW',
  })
  await expect(form).toHaveCount(0)
  expect((await ownedTickets(page)).filter((ticket) => ticket.orderNo === orderNo)).toHaveLength(1)
})

for (const decision of ['APPROVE', 'REJECT'] as const) {
  test(`real after-sale ${decision} requires a different authorized reviewer and persists the result`, async ({
    page,
  }, testInfo) => {
    await connect(page, 'frontend-buyer', password)
    const orderNo = await selectFreshAfterSaleOrder(page)
    const reason = `商品破损 浏览器验收 ${decision}`
    const submitted = await chat(page, `申请售后 ${reason}`)
    expect(submitted.reply).toContain('等待客服审核')
    expect(submitted.reply).toContain('不会实际返款')
    const ticketId = (await ownedTickets(page)).find((ticket) => ticket.orderNo === orderNo)?.id
    expect(ticketId).toBeTruthy()

    const beforeReview = await chat(page, '我的售后')
    expect(beforeReview.reply).toContain(orderNo)
    expect(beforeReview.reply).toContain(ticketId!)
    expect(beforeReview.reply).not.toContain('PENDING_REVIEW')
    expect((await ownedTickets(page)).find((ticket) => ticket.id === ticketId)?.status).toBe(
      'PENDING_REVIEW',
    )
    await expect(page.getByRole('button', { name: '人工审核', exact: true })).toHaveCount(0)
    const denied = await page.request.get(reviewPath, { headers: buyerHeaders })
    expect(denied.status()).toBe(403)
    await expect(page.getByTestId('review-panel')).toHaveCount(0)

    await page.getByTestId('disconnect-button').click()
    await connectMerchant(page)
    const card = page.getByTestId('review-card').filter({ hasText: ticketId! })
    await expect(card).toHaveCount(1)
    await expect(card).toContainText(orderNo)
    await expect(card).toContainText(reason)
    if ((await card.getAttribute('open')) === null) await card.locator('summary').click()
    const note = `人工验收 ${decision}：已核对申请与订单，未执行支付退款。`
    await card.getByTestId('review-note').fill(note)
    if (decision === 'APPROVE') {
      await page.screenshot({
        path: testInfo.outputPath('human-review-desktop.png'),
        fullPage: true,
      })
    }
    const responsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === `${reviewPath}/${ticketId}/decision`,
    )
    await card.getByTestId(decision === 'APPROVE' ? 'approve-review' : 'reject-review').click()
    const response = await responsePromise
    expect(response.status()).toBe(200)
    const reviewed = (await response.json()) as Record<string, unknown>
    const status = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'
    expect(reviewed).toMatchObject({
      id: ticketId,
      orderNo,
      shopId: 100,
      reason,
      status,
      reviewer: 'frontend-reviewer',
      reviewNote: note,
    })
    await expect(card).toHaveCount(0)
    await page.getByTestId('merchant-disconnect').click()
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    const afterReview = await chat(page, '我的售后')
    expect(afterReview.reply).toContain(orderNo)
    expect((await ownedTickets(page)).find((ticket) => ticket.id === ticketId)?.status).toBe(status)
    expect(afterReview.reply).toContain(`审核意见：${note}`)
    expect(afterReview.reply).toContain('不会实际返款')
    if (decision === 'APPROVE') {
      await page.screenshot({
        path: testInfo.outputPath('buyer-approved-desktop.png'),
        fullPage: true,
      })
    }
    await switchShop(page, '101')
    const otherShopHistory = await chat(page, '我的售后')
    expect(otherShopHistory.reply).not.toContain(ticketId!)
  })
}

test('real approved request executes a durable simulated refund and both progress panels show the same result', async ({
  page,
}, testInfo) => {
  test.setTimeout(60_000)
  await connect(page, 'frontend-buyer', password)
  const orderNo = await selectFreshAfterSaleOrder(page)
  await chat(page, '申请售后 商品破损 模拟退款执行浏览器验收')
  const ticketId = (await ownedTickets(page)).find((ticket) => ticket.orderNo === orderNo)?.id
  expect(ticketId).toBeTruthy()
  await page.getByTestId('disconnect-button').click()
  await connectMerchant(page)
  const pendingCard = page.getByTestId('review-card').filter({ hasText: ticketId! })
  await expect(pendingCard).toHaveCount(1)
  if ((await pendingCard.getAttribute('open')) === null)
    await pendingCard.locator('summary').click()
  await pendingCard.getByTestId('review-note').fill('核实订单及售后原因，批准执行学习版模拟退款。')
  const approvedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `${reviewPath}/${ticketId}/decision`,
  )
  await pendingCard.getByTestId('approve-review').click()
  expect((await approvedResponse).status()).toBe(200)
  await expect(pendingCard).toHaveCount(0)

  await page.getByTestId('review-tab-history').click()
  const historyCard = page.getByTestId('review-card').filter({ hasText: ticketId! })
  await expect(historyCard).toHaveCount(1)
  if ((await historyCard.getAttribute('open')) === null)
    await historyCard.locator('summary').click()
  await expect
    .poll(
      async () => {
        const progress = historyCard.getByTestId('execution-progress')
        const status = await progress.getAttribute('data-status')
        if (status !== 'SUCCEEDED') {
          const refresh = page.getByTestId('refresh-reviews')
          await expect(refresh).toBeEnabled()
          await refresh.click()
          await expect(refresh).toBeEnabled()
        }
        return status
      },
      { timeout: 20_000, intervals: [300, 600, 1000] },
    )
    .toBe('SUCCEEDED')
  const reference = await historyCard.getByTestId('execution-reference').textContent()
  expect(reference).toMatch(/^SIM-/)
  await expect(historyCard).toContainText('模拟退款成功')
  await expect(historyCard.getByTestId('retry-execution')).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('reviewer-execution-progress.png'),
    fullPage: true,
  })
  await page.getByTestId('merchant-disconnect').click()
  await page.goto('/?shopId=100')
  await connect(page, 'frontend-buyer', password)
  const naturalProgress = await chat(page, '我的售后处理到哪了')
  expect(naturalProgress.reply).toContain('演示处理完成')
  expect(naturalProgress.reply).not.toContain('SUCCEEDED')
  expect(naturalProgress.reply).toContain(reference!)
  await showOrders(page)
  const ownedResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/shops/100/after-sales',
  )
  await page.getByTestId('open-after-sales').click()
  const owned = await ownedResponse
  expect(owned.status()).toBe(200)
  const tickets = (await owned.json()) as {
    id: string
    orderNo: string
    execution: { amount: number; reference: string; status: string } | null
  }[]
  const ticket = tickets.find((item) => item.id === ticketId)
  expect(ticket?.execution).toMatchObject({ status: 'SUCCEEDED', reference })
  expect(ticket?.execution?.amount).toBeGreaterThanOrEqual(0)
  const buyerCard = page.getByTestId('after-sale-card').filter({ hasText: ticketId! })
  await expect(buyerCard).toHaveCount(1)
  if ((await buyerCard.getAttribute('open')) === null) await buyerCard.locator('summary').click()
  await expect(buyerCard).toContainText(orderNo)
  await expect(buyerCard.getByTestId('execution-progress')).toHaveAttribute(
    'data-status',
    'SUCCEEDED',
  )
  await expect(buyerCard.getByTestId('execution-reference')).toHaveText(reference!)
  await expect(buyerCard).toContainText(
    new Intl.NumberFormat('zh-CN', {
      style: 'currency',
      currency: 'CNY',
      minimumFractionDigits: 2,
    }).format(ticket!.execution!.amount),
  )
  await expect(buyerCard.getByTestId('retry-execution')).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('buyer-execution-progress.png'),
    fullPage: true,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(buyerCard.getByTestId('execution-reference')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  await page.screenshot({
    path: testInfo.outputPath('buyer-execution-progress-mobile.png'),
    fullPage: true,
  })
  await page.keyboard.press('Escape')
  await switchShop(page, '101')
  await showOrders(page)
  await page.getByTestId('open-after-sales').click()
  await expect(page.getByTestId('after-sales-panel')).toBeVisible()
  await expect(page.getByTestId('after-sale-card').filter({ hasText: ticketId! })).toHaveCount(0)
})
