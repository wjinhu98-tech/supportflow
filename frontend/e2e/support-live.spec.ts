import { expect, test } from '@playwright/test'
import { connect, send, showChat, showOrders, switchShop } from './helpers'

const password = 'Frontend-Test-Only!'

test('live buyer handoff, chat-only staff claim/reply, close and AI restoration', async ({
  page,
  browser,
}) => {
  let aiPosts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/chat\/(?:messages|conversations\/[^/]+\/messages)$/.test(new URL(request.url()).pathname)
    )
      aiPosts++
  })
  await page.goto('/?shopId=100')
  await connect(page, 'frontend-buyer', password)
  const activeClose = page.getByTestId('close-human-support')
  if (await activeClose.isVisible()) {
    await activeClose.click()
    await expect(page.getByTestId('request-human-support')).toBeVisible()
  }
  await page.getByTestId('request-human-support').click()
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  const text = `人工核实 ${Date.now()}`
  await send(page, text)
  await expect(page.getByTestId('chat-message').last()).toContainText(text)
  await expect(page.getByTestId('delete-conversation')).toBeDisabled()
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  await showOrders(page)
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(20)
  await page
    .getByTestId('orders-panel')
    .locator('[data-testid="order-card"][data-shop-id="100"][data-order-no="SF10001"]')
    .getByRole('button', { name: /^咨询此订单/ })
    .click()
  await expect(page.getByTestId('conversation-panel')).toBeVisible()
  await expect(page.getByTestId('selected-order')).toContainText('SF10001')
  await expect(page.locator('.order-detail')).not.toBeVisible()
  await showChat(page)
  await page.getByRole('button', { name: '申请退款', exact: true }).click()
  const refund = page.getByRole('dialog', { name: '申请退款', exact: true })
  await expect(refund).toBeVisible()
  await expect(refund.locator('#refund-order option')).toHaveCount(21)
  await refund.getByRole('button', { name: '关闭', exact: true }).click()
  expect(aiPosts).toBe(0)
  const context = await browser.newContext()
  const staff = await context.newPage()
  await staff.goto('/merchant')
  await staff.getByTestId('merchant-username').fill('frontend-chat-staff')
  await staff.getByTestId('merchant-password').fill(password)
  await staff.getByTestId('merchant-connect').click()
  await expect(staff.getByTestId('human-support-panel')).toBeVisible()
  await expect(staff.getByTestId('merchant-tab-after-sales')).toHaveCount(0)
  await expect(staff.getByTestId('merchant-tab-knowledge')).toHaveCount(0)
  await staff.locator('.human-session').first().click()
  await staff.getByTestId('support-claim').click()
  await expect(staff.getByTestId('support-message').last()).toContainText(text)
  const reply = `已收到，正在核实 ${Date.now()}`
  await staff.getByTestId('support-reply').fill(reply)
  await staff.getByTestId('support-send').click()
  await expect(page.getByTestId('chat-message').last()).toContainText(reply)
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺客服')
  await staff.getByTestId('support-close').click()
  await expect(page.getByTestId('buyer-support-status')).toContainText('人工服务已结束')
  await send(page, '我的订单')
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺智能客服')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  expect(aiPosts).toBe(1)
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  await switchShop(page, '101')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(0)
  await switchShop(page, '100')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  await context.close()
})
