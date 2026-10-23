import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MerchantApp from './MerchantApp.vue'

const mocks = vi.hoisted(() => ({
  authenticate: vi.fn(),
  currentAccount: vi.fn(),
  merchantShops: vi.fn(),
  pendingReviews: vi.fn(),
  reviewHistory: vi.fn(),
  decideReview: vi.fn(),
  retryExecution: vi.fn(),
}))
vi.mock('./api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/client')>()
  return {
    ...actual,
    ApiClient: class {
      authenticate = mocks.authenticate
      currentAccount = mocks.currentAccount
      merchantShops = mocks.merchantShops
      pendingReviews = mocks.pendingReviews
      reviewHistory = mocks.reviewHistory
      decideReview = mocks.decideReview
      retryExecution = mocks.retryExecution
    },
  }
})
beforeEach(() => {
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.authenticate.mockResolvedValue(undefined)
  mocks.currentAccount.mockResolvedValue({ username: 'staff', accountType: 'MERCHANT_STAFF' })
  mocks.merchantShops.mockResolvedValue([
    { id: 100, name: '一号店', merchantId: 1, merchantName: '测试商家' },
  ])
  mocks.pendingReviews.mockResolvedValue([])
  mocks.reviewHistory.mockResolvedValue([])
})
async function login(view: ReturnType<typeof mount>) {
  await view.get('[data-testid="merchant-username"]').setValue('staff')
  await view.get('[data-testid="merchant-password"]').setValue('test')
  await view.get('[data-testid="merchant-login"]').trigger('submit')
  await flushPromises()
}
describe('MerchantApp', () => {
  it('uses a dedicated staff login and only renders the authorized shop selector', async () => {
    const view = mount(MerchantApp)
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    await login(view)
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(true)
    expect(view.find('[data-testid="merchant-login"]').exists()).toBe(false)
    expect(view.findAll('[data-testid="merchant-shop-select"] option')).toHaveLength(1)
    expect(view.get('[data-testid="merchant-shop-select"]').text()).toContain('一号店')
    expect(view.find('[data-testid="connect-form"]').exists()).toBe(false)
    expect(view.find('[data-testid="message-input"]').exists()).toBe(false)
    expect(localStorage.length).toBe(0)
    await view.get('[data-testid="merchant-disconnect"]').trigger('click')
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    expect((view.get('[data-testid="merchant-password"]').element as HTMLInputElement).value).toBe(
      '',
    )
    view.unmount()
  })

  it('rejects buyer accounts and clears the password field', async () => {
    const view = mount(MerchantApp)
    mocks.currentAccount.mockResolvedValueOnce({ username: 'buyer', accountType: 'BUYER' })
    await login(view)
    expect(view.get('[role="alert"]').text()).toContain('用户端')
    expect((view.get('[data-testid="merchant-password"]').element as HTMLInputElement).value).toBe(
      '',
    )
    expect(view.find('[data-testid="merchant-workspace"]').exists()).toBe(false)
    expect(mocks.merchantShops).not.toHaveBeenCalled()
    view.unmount()
  })

  it('renders an explicit no-permission state without a queue or arbitrary shop input', async () => {
    const view = mount(MerchantApp)
    mocks.merchantShops.mockResolvedValueOnce([])
    await login(view)
    expect(view.get('[data-testid="merchant-no-shops"]').text()).toContain('尚未获得店铺授权')
    expect(view.find('[data-testid="merchant-shop-select"]').exists()).toBe(false)
    expect(view.find('[data-testid="review-panel"]').exists()).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
    view.unmount()
  })
})
