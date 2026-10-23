import { effectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AfterSaleTicket, CurrentAccount, MerchantShop } from '../types'

const mocks = vi.hoisted(() => ({
  authenticate: vi.fn(),
  currentAccount: vi.fn(),
  merchantShops: vi.fn(),
  pendingReviews: vi.fn(),
  reviewHistory: vi.fn(),
  decideReview: vi.fn(),
  retryExecution: vi.fn(),
}))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
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
import { ApiError } from '../api/client'
import { useMerchantDesk } from './useMerchantDesk'

const shops: MerchantShop[] = [
  { id: 100, name: '一号店', merchantId: 1, merchantName: '示例商家' },
  { id: 101, name: '二号店', merchantId: 1, merchantName: '示例商家' },
]
const ticket: AfterSaleTicket = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '商品损坏',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
  execution: null,
  eligibility: null,
}
const reviewed: AfterSaleTicket = {
  ...ticket,
  status: 'APPROVED',
  reviewer: 'staff',
  reviewNote: '核实通过',
}
const failed: AfterSaleTicket = {
  ...reviewed,
  execution: {
    status: 'FAILED',
    amount: 10,
    currency: 'CNY',
    attemptCount: 1,
    maxAttempts: 5,
    nextAttemptAt: null,
    completedAt: null,
    reference: null,
    lastError: '暂时失败',
    retryable: true,
  },
}
const decision = { id: ticket.id, decision: 'APPROVE' as const, note: '核实通过' }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((ok, no) => {
    resolve = ok
    reject = no
  })
  return { promise, resolve, reject }
}
const scopes: ReturnType<typeof effectScope>[] = []
function desk() {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(useMerchantDesk)!
}
beforeEach(() => {
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.authenticate.mockResolvedValue(undefined)
  mocks.currentAccount.mockResolvedValue({ username: 'staff', accountType: 'MERCHANT_STAFF' })
  mocks.merchantShops.mockResolvedValue(shops)
  mocks.pendingReviews.mockImplementation((shopId: string) =>
    Promise.resolve(shopId === '100' ? [ticket] : []),
  )
  mocks.reviewHistory.mockResolvedValue([reviewed])
  mocks.decideReview.mockResolvedValue(reviewed)
  mocks.retryExecution.mockResolvedValue({
    ...failed,
    execution: { ...failed.execution!, status: 'PENDING', retryable: false },
  })
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
})

describe('useMerchantDesk', () => {
  it('verifies the server identity before listing granted shops and scoped pending tickets', async () => {
    const state = desk()
    expect(await state.connect('staff', 'test')).toBe(true)
    expect(mocks.authenticate).toHaveBeenCalledTimes(1)
    expect(mocks.currentAccount).toHaveBeenCalledTimes(1)
    expect(mocks.merchantShops).toHaveBeenCalledTimes(1)
    expect(state.shops.value).toEqual(shops)
    expect(state.shopId.value).toBe('100')
    expect(state.tickets.value).toEqual([ticket])
    expect(mocks.pendingReviews).toHaveBeenCalledWith('100', expect.any(AbortSignal))
  })

  it('rejects a buyer identity without loading any merchant records or retaining credentials', async () => {
    const state = desk()
    mocks.currentAccount.mockResolvedValueOnce({ username: 'buyer', accountType: 'BUYER' })
    expect(await state.connect('buyer', 'test')).toBe(false)
    expect(state.connected.value).toBe(false)
    expect(state.username.value).toBe('')
    expect(state.loginError.value).toContain('用户端')
    expect(mocks.merchantShops).not.toHaveBeenCalled()
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
  })

  it('allows an authenticated staff account with no grants but never invents a shop or queries a queue', async () => {
    const state = desk()
    mocks.merchantShops.mockResolvedValueOnce([])
    expect(await state.connect('staff', 'test')).toBe(true)
    expect(state.shops.value).toEqual([])
    expect(state.shopId.value).toBe('')
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
    await state.changeShop('100')
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
  })

  it.each(['identity', 'shops'] as const)(
    'discards a late %s response after logout',
    async (stage) => {
      const state = desk()
      const pending = deferred<CurrentAccount | MerchantShop[]>()
      if (stage === 'identity') mocks.currentAccount.mockReturnValueOnce(pending.promise)
      else mocks.merchantShops.mockReturnValueOnce(pending.promise)
      const request = state.connect('staff', 'test')
      for (let i = 0; i < 5; i++) await Promise.resolve()
      expect(state.disconnect()).toBe(true)
      pending.resolve(
        stage === 'identity' ? { username: 'staff', accountType: 'MERCHANT_STAFF' } : shops,
      )
      expect(await request).toBe(false)
      expect(state.connected.value).toBe(false)
      expect(state.shops.value).toEqual([])
    },
  )

  it('blocks concurrent login and ignores arbitrary shop IDs', async () => {
    const state = desk()
    const pending = deferred<void>()
    mocks.authenticate.mockReturnValueOnce(pending.promise)
    const request = state.connect('staff', 'test')
    expect(await state.connect('other', 'test')).toBe(false)
    pending.resolve()
    await request
    await state.changeShop('999')
    expect(state.shopId.value).toBe('100')
    expect(mocks.pendingReviews).toHaveBeenCalledTimes(1)
  })

  it.each(['logout', 'shop switch'] as const)(
    'discards delayed queue data after %s',
    async (transition) => {
      const state = desk()
      await state.connect('staff', 'test')
      const pending = deferred<AfterSaleTicket[]>()
      mocks.pendingReviews.mockReturnValueOnce(pending.promise)
      const request = state.loadReviews()
      const signal = mocks.pendingReviews.mock.lastCall?.[1] as AbortSignal
      if (transition === 'logout') state.disconnect()
      else await state.changeShop('101')
      expect(signal.aborted).toBe(true)
      pending.resolve([ticket])
      await request
      expect(state.tickets.value).toEqual([])
      expect(state.loading.value).toBe(false)
    },
  )

  it('holds the original shop and account while a decision is pending and prevents duplicate writes', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    const pending = deferred<AfterSaleTicket>()
    mocks.decideReview.mockReturnValueOnce(pending.promise)
    const request = state.submitReview(decision)
    expect(state.submitting.value).toBe(true)
    await state.changeShop('101')
    expect(state.disconnect()).toBe(false)
    expect(await state.connect('new', 'test')).toBe(false)
    await state.submitReview(decision)
    expect(state.shopId.value).toBe('100')
    expect(mocks.decideReview).toHaveBeenCalledTimes(1)
    expect(mocks.decideReview).toHaveBeenCalledWith(
      '100',
      ticket.id,
      'APPROVE',
      decision.note,
      expect.any(AbortSignal),
    )
    pending.resolve(reviewed)
    await request
    expect(state.tickets.value).toEqual([])
    expect(state.historyTickets.value).toEqual([reviewed])
    expect(state.submitting.value).toBe(false)
    expect(mocks.retryExecution).not.toHaveBeenCalled()
  })

  it('requires a refresh after an uncertain POST and never automatically replays the decision', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    mocks.decideReview.mockRejectedValueOnce(new ApiError('请求结果无法确认', 0))
    await state.submitReview(decision)
    expect(state.tickets.value).toEqual([ticket])
    expect(state.needsVerification.value).toBe(true)
    expect(state.error.value).toContain('刷新当前店铺')
    await state.submitReview(decision)
    expect(mocks.decideReview).toHaveBeenCalledTimes(1)
    await state.loadReviews('history')
    expect(state.needsVerification.value).toBe(false)
    expect(state.historyTickets.value).toEqual([reviewed])
  })

  it('does not submit a ticket absent from the active pending queue', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    await state.submitReview({ ...decision, id: '00000000-0000-4000-8000-000000000009' })
    expect(mocks.decideReview).not.toHaveBeenCalled()
  })

  it('clears formerly authorized data when a fresh request is denied after grant revocation', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    await state.loadReviews('history')
    mocks.pendingReviews.mockRejectedValueOnce(new ApiError('没有店铺权限', 403))
    await state.loadReviews('pending')
    expect(state.tickets.value).toEqual([])
    expect(state.historyTickets.value).toEqual([])
    expect(state.error.value).toBe('没有店铺权限')
    expect(state.connected.value).toBe(true)
  })

  it('clears credentials and shop data when authentication expires', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    mocks.pendingReviews.mockRejectedValueOnce(new ApiError('请重新登录', 401))
    await state.loadReviews()
    expect(state.connected.value).toBe(false)
    expect(state.username.value).toBe('')
    expect(state.shops.value).toEqual([])
    expect(state.loginError.value).toBe('请重新登录')
  })

  it('retries only an explicit failed task under its original shop and locks switching until completion', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    mocks.reviewHistory.mockResolvedValueOnce([failed])
    await state.loadReviews('history')
    expect(mocks.retryExecution).not.toHaveBeenCalled()
    const pending = deferred<AfterSaleTicket>()
    mocks.retryExecution.mockReturnValueOnce(pending.promise)
    const request = state.retryExecution(failed.id)
    await state.retryExecution(failed.id)
    await state.changeShop('101')
    expect(state.shopId.value).toBe('100')
    expect(mocks.retryExecution).toHaveBeenCalledWith('100', failed.id, expect.any(AbortSignal))
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
    const next: AfterSaleTicket = {
      ...failed,
      execution: { ...failed.execution!, status: 'PENDING', retryable: false },
    }
    pending.resolve(next)
    await request
    expect(state.historyTickets.value).toEqual([next])
    await state.retryExecution(failed.id)
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
  })

  it('preserves a failed task on a retry error until a read refresh confirms its status', async () => {
    const state = desk()
    await state.connect('staff', 'test')
    mocks.reviewHistory.mockResolvedValueOnce([failed])
    await state.loadReviews('history')
    mocks.retryExecution.mockRejectedValueOnce(new ApiError('状态已变化', 409))
    await state.retryExecution(failed.id)
    expect(state.historyTickets.value).toEqual([failed])
    expect(state.needsVerification.value).toBe(true)
    await state.retryExecution(failed.id)
    expect(mocks.retryExecution).toHaveBeenCalledTimes(1)
  })
})
