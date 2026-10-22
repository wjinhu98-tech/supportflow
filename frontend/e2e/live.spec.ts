import { expect, test, type Page } from '@playwright/test'
import { connect, send, switchShop } from './helpers'
import type { AfterSaleEligibility, AfterSaleTicket } from '../src/types'

const password = 'Frontend-Test-Only!'
const reviewPath = '/api/v1/reviews/after-sales'
const buyerHeaders = {
  Authorization: `Basic ${Buffer.from(`frontend-buyer:${password}`).toString('base64')}`,
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
      new URL(response.url()).pathname.endsWith('/chat/messages') &&
      request.postDataJSON()?.message === message
    )
  })
  await send(page, message)
  const response = await responsePromise
  expect(response.status(), `Chat failed for: ${message}`).toBe(200)
  const body = (await response.json()) as { reply: string; type: string }
  await expect(page.getByTestId('chat-message').last()).toContainText(body.reply)
  return body
}

async function selectOrder(page: Page, orderNo: string) {
  const card = page
    .getByTestId('orders-panel')
    .getByTestId('order-card')
    .filter({ hasText: new RegExp(`\\b${orderNo}\\b`) })
  await expect(card).toHaveCount(1)
  await card.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('chat-message').last()).toContainText(orderNo)
  await expect(page.getByTestId('send-button')).toBeDisabled()
  // Wait for the selected-order POST before submitting another message.
  await expect(page.getByTestId('message-input')).toBeEnabled()
}

async function selectFreshAfterSaleOrder(page: Page) {
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

async function openReviewQueue(page: Page, expectedStatus = 200) {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === reviewPath,
  )
  await page.getByRole('button', { name: '人工审核', exact: true }).click()
  const response = await responsePromise
  expect(response.status()).toBe(expectedStatus)
  await expect(page.getByTestId('review-panel')).toBeVisible()
  return response
}

// Only runs when E2E_BACKEND_URL points at the isolated Testcontainers launcher.
// These deliberately public test-only accounts must never be copied to local secrets.
test.beforeEach(async ({ page }) => {
  await page.goto('/?shopId=100')
})

test('real Spring Security CSRF and real MySQL owned-order selection', async ({ page }) => {
  await connect(page, 'frontend-buyer', 'Frontend-Test-Only!')
  const panel = page.getByTestId('orders-panel')
  await expect(panel.getByTestId('order-card')).toHaveCount(20)
  const order = panel.getByTestId('order-card').filter({ hasText: 'SF10001' })
  await expect(order).toHaveCount(1)
  await order.getByRole('button', { name: /^咨询此订单/ }).click()
  await expect(page.getByTestId('chat-message').last()).toContainText('已发货')
  await expect(page.getByTestId('chat-message').last()).toContainText('199.90')
  const cookie = (await page.context().cookies()).find((item) => item.name === 'JSESSIONID')
  expect(cookie?.httpOnly).toBe(true)
  await page.getByTestId('load-more-orders').click()
  await expect(panel.getByTestId('order-card')).toHaveCount(23)
})

test('real API keeps same-number orders scoped to the active shop and rejects a missing order', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', 'Frontend-Test-Only!')
  await switchShop(page, '101')
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
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toContainText('SF20001')
  await send(page, '查订单 SF10001')
  await expect(page.getByRole('alert').last()).toContainText('未找到当前店铺内属于你的这笔订单')
  await expect(page.getByTestId('chat-message').filter({ hasText: '已发货' })).toHaveCount(0)
})

test('real knowledge citations and recorded logistics stay separate from same-number shop orders', async ({
  page,
}) => {
  await connect(page, 'frontend-buyer', password)
  const knowledge = await chat(page, '售后审批规则')
  expect(knowledge.type).toBe('TEXT')
  expect(knowledge.reply).toMatch(/\[来源：[^\]]+@[^\]]+\]/)
  expect(knowledge.reply).toContain('演示环境')

  await selectOrder(page, 'SF10001')
  const logistics = await chat(page, '查物流')
  expect(logistics.reply).toContain('学习版仓库：已出库')
  expect(logistics.reply).toContain('学习版配送中心：运输中')
  expect(logistics.reply).toContain('并非实时快递联网查询')

  await switchShop(page, '101')
  await selectOrder(page, 'SF10001')
  const otherShopLogistics = await chat(page, '查物流')
  expect(otherShopLogistics.reply).toContain('暂无录入的物流事件')
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
    await openReviewQueue(page, 403)
    await expect(page.getByTestId('review-panel').getByRole('alert')).toContainText('无权访问')
    await expect(page.getByTestId('review-card')).toHaveCount(0)
    await expect(page.getByTestId('approve-review')).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('review-panel')).toHaveCount(0)

    await page.getByTestId('disconnect-button').click()
    await connect(page, 'frontend-reviewer', password)
    await openReviewQueue(page)
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
    await page.keyboard.press('Escape')
    await page.getByTestId('disconnect-button').click()

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
  await connect(page, 'frontend-reviewer', password)
  await openReviewQueue(page)
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
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('review-panel')).toHaveCount(0)

  await page.getByTestId('disconnect-button').click()
  await connect(page, 'frontend-buyer', password)
  const naturalProgress = await chat(page, '我的售后处理到哪了')
  expect(naturalProgress.reply).toContain('演示处理完成')
  expect(naturalProgress.reply).not.toContain('SUCCEEDED')
  expect(naturalProgress.reply).toContain(reference!)
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
  await page.getByTestId('open-after-sales').click()
  await expect(page.getByTestId('after-sales-panel')).toBeVisible()
  await expect(page.getByTestId('after-sale-card').filter({ hasText: ticketId! })).toHaveCount(0)
})
