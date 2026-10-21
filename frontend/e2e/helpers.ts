import { expect, type Page } from '@playwright/test'

export async function connect(page: Page, username = 'buyer-demo', password = 'browser-test-only') {
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await page.getByTestId('username-input').fill(username)
  await page.getByTestId('password-input').fill(password)
  await page.getByTestId('connect-button').click()
  await expect(page.getByTestId('disconnect-button')).toBeVisible()
  // The account header appears before the initial orders have loaded. Wait until
  // the login modal actually closes before interacting with the conversation.
  await expect(page.getByTestId('connect-form')).not.toBeVisible()
}

export async function send(page: Page, message: string) {
  await page.getByTestId('message-input').fill(message)
  await page.getByTestId('send-button').click()
}

export async function switchShop(page: Page, shopId: string) {
  await page.getByRole('button', { name: '切换店铺', exact: true }).click()
  await page.getByTestId('shop-input').fill(shopId)
  await page.getByTestId('shop-confirm').click()
  await expect(page).toHaveURL(new RegExp(`[?&]shopId=${shopId}(?:&|$)`))
  await expect(page.getByTestId('shop-confirm')).not.toBeVisible()
}
