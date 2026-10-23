import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' }
const shop = { id: 100, name: '一号店', merchantId: 1, merchantName: '测试商家' }
const ticket = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '破损',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
  execution: null,
  eligibility: null,
}
const reviewed = { ...ticket, status: 'APPROVED', reviewer: 'staff', reviewNote: '核实通过' }
const execution = {
  status: 'PENDING',
  amount: 10,
  currency: 'CNY',
  attemptCount: 1,
  maxAttempts: 5,
  nextAttemptAt: null,
  completedAt: null,
  reference: null,
  lastError: null,
  retryable: false,
}

describe('merchant API boundaries', () => {
  it('reads current server identity without trusting account names or returning extra identity fields', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        json({ username: 'unpredictable-name', accountType: 'BUYER', buyerId: 200 }),
      )
    const api = new ApiClient('unpredictable-name', 'test', request)
    expect(await api.currentAccount()).toEqual({
      username: 'unpredictable-name',
      accountType: 'BUYER',
    })
    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/security/me')
    expect(request.mock.calls[0]?.[1]?.method).toBe('GET')
  })

  it.each([
    { username: 'staff', accountType: 'ADMIN' },
    { accountType: 'MERCHANT_STAFF' },
    { username: ' ', accountType: 'BUYER' },
  ])('rejects a malformed server identity: %j', async (identity) => {
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(json(identity))
    await expect(new ApiClient('staff', 'test', request).currentAccount()).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it('lists only server-provided authorized shops and maps the public contract', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json([{ ...shop, unrelated: true }]))
    expect(await new ApiClient('staff', 'test', request).merchantShops()).toEqual([shop])
    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/merchant/shops')
  })

  it.each([
    { rows: [{ ...shop, id: -1 }] },
    { rows: [shop, shop] },
    { rows: [{ ...shop, merchantId: 0 }] },
    { rows: [{ ...shop, name: '' }] },
  ])('rejects malformed or duplicate shop responses: %j', async ({ rows }) => {
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(json(rows))
    await expect(new ApiClient('staff', 'test', request).merchantShops()).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it.each(['pending', 'history', 'decision', 'retry'] as const)(
    'rejects cross-shop %s responses even when the ticket ID matches',
    async (kind) => {
      const other = {
        ...(kind === 'pending' ? ticket : reviewed),
        shopId: 101,
        ...(kind === 'retry' ? { execution } : {}),
      }
      const request = vi.fn<typeof fetch>()
      if (kind === 'decision' || kind === 'retry') request.mockResolvedValueOnce(json(csrf))
      request.mockResolvedValueOnce(
        json(kind === 'pending' || kind === 'history' ? [other] : other),
      )
      const api = new ApiClient('staff', 'test', request)
      const operation =
        kind === 'pending'
          ? api.pendingReviews('100')
          : kind === 'history'
            ? api.reviewHistory('100')
            : kind === 'decision'
              ? api.decideReview('100', ticket.id, 'APPROVE', '核实通过')
              : api.retryExecution('100', ticket.id)
      await expect(operation).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
      expect(request.mock.calls.every(([path]) => !String(path).includes('/reviews/'))).toBe(true)
    },
  )

  it('posts a retry only under its explicit shop using fresh CSRF, and never auto-replays a lost response', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('connection lost'))
    await expect(
      new ApiClient('staff', 'test', request).retryExecution('100', ticket.id),
    ).rejects.toThrow()
    expect(request).toHaveBeenCalledTimes(2)
    expect(request.mock.calls[1]?.[0]).toBe(
      `/api/v1/merchant/shops/100/after-sales/${ticket.id}/execution/retry`,
    )
    expect(request.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': csrf.token },
    })
  })

  it.each(['0', '-1', '100/../101', '100?shopId=101'])(
    'rejects an invalid explicit merchant shop before any network write: %s',
    async (id) => {
      const request = vi.fn<typeof fetch>()
      const api = new ApiClient('staff', 'test', request)
      await expect(api.decideReview(id, ticket.id, 'APPROVE', '核实通过')).rejects.toThrow()
      expect(request).not.toHaveBeenCalled()
    },
  )
})
