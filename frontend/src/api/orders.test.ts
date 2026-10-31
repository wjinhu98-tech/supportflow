import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from './client'

const order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
  receivedAt: null,
}
const json = (value: unknown) =>
  new Response(JSON.stringify(value), { headers: { 'content-type': 'application/json' } })

describe('buyer orders across shops', () => {
  it('uses the authenticated buyer endpoint and preserves same-number orders from different shops', async () => {
    const page = { items: [order, { ...order, shopId: 101 }], nextAfterId: null }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json(page))
    const api = new ApiClient('buyer', 'test', fetcher)

    expect(await api.allOrders()).toEqual(page)
    expect(fetcher).toHaveBeenCalledOnce()
    expect(fetcher.mock.calls[0]![0]).toBe('/api/v1/orders?afterId=0&size=20')
    expect(fetcher.mock.calls[0]![1]).toMatchObject({ method: 'GET', redirect: 'error' })
    expect(fetcher.mock.calls[0]![1]?.body).toBeUndefined()
  })

  it('keeps the server cursor when reading the next page', async () => {
    const page = { items: [{ ...order, shopId: 103 }], nextAfterId: 40 }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json(page))

    expect(await new ApiClient('buyer', 'test', fetcher).allOrders(20)).toEqual(page)
    expect(fetcher.mock.calls[0]![0]).toBe('/api/v1/orders?afterId=20&size=20')
  })

  it.each([0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1])(
    'rejects an invalid cursor %s before transport',
    async (cursor) => {
      const fetcher = vi.fn<typeof fetch>()
      await expect(
        new ApiClient('buyer', 'test', fetcher).allOrders(cursor),
      ).rejects.toBeInstanceOf(ApiError)
      expect(fetcher).not.toHaveBeenCalled()
    },
  )

  it.each([
    null,
    { items: null, nextAfterId: null },
    { items: [], nextAfterId: undefined },
    { items: Array.from({ length: 21 }, () => order), nextAfterId: null },
    { items: [{ ...order, shopId: 0 }], nextAfterId: null },
    { items: [{ ...order, totalAmount: -1 }], nextAfterId: null },
    { items: [order], nextAfterId: '21' },
    { items: [order], nextAfterId: Number.MAX_SAFE_INTEGER + 1 },
    { items: [order], nextAfterId: 20 },
    { items: [order], nextAfterId: 19 },
  ])('rejects a malformed or non-advancing page %#', async (page) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json(page))
    await expect(new ApiClient('buyer', 'test', fetcher).allOrders(20)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it('keeps shop-scoped reads separate for the current shop refund picker', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ items: [order], nextAfterId: null }))
      .mockResolvedValueOnce(json({ items: [{ ...order, shopId: 101 }], nextAfterId: null }))
    const api = new ApiClient('buyer', 'test', fetcher)

    expect(await api.shopOrders('100')).toEqual({ items: [order], nextAfterId: null })
    await expect(api.shopOrders('100')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(
      fetcher.mock.calls.every(([url]) => String(url).startsWith('/api/v1/shops/100/orders?')),
    ).toBe(true)
  })
})
