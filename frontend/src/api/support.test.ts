import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const conversationId = 'a1111111-1111-4111-8111-111111111111'
const sessionId = 'b1111111-1111-4111-8111-111111111111'
const messageId = 'c1111111-1111-4111-8111-111111111111'
const timestamp = '2026-10-30T00:00:00Z'
const session = {
  id: sessionId,
  status: 'WAITING',
  assigned: false,
  assignedToMe: false,
  createdAt: timestamp,
  updatedAt: timestamp,
  closedAt: null,
}
const snapshot = { session, messages: [], nextBeforeId: null }
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } })
const csrf = () =>
  json({ token: 'fresh-token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' })

describe('human support API boundaries', () => {
  it('loads the order browser with a scoped read and rejects cross-shop items', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ items: [], nextAfterId: null }))
    const api = new ApiClient('buyer', 'test', fetcher)
    expect(await api.shopOrders('100')).toEqual({ items: [], nextAfterId: null })
    expect(fetcher.mock.calls[0]![0]).toBe('/api/v1/shops/100/orders?afterId=0&size=20')
    expect(fetcher.mock.calls[0]![1]?.method).toBe('GET')
    expect(fetcher).toHaveBeenCalledOnce()
    fetcher.mockResolvedValueOnce(json({ items: [{ shopId: 101 }], nextAfterId: null }))
    await expect(api.shopOrders('100')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('requests a human with an explicit nonce and fresh CSRF on the current conversation', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json(snapshot))
    const api = new ApiClient('buyer', 'test', fetcher)
    expect(await api.requestSupport('100', conversationId, messageId)).toEqual(snapshot)
    expect(fetcher.mock.calls[1]![0]).toBe(
      `/api/v1/shops/100/chat/conversations/${conversationId}/support`,
    )
    const init = fetcher.mock.calls[1]![1]!
    expect(JSON.parse(init.body as string)).toEqual({ requestId: messageId })
    expect(init.headers).toMatchObject({ 'X-CSRF-TOKEN': 'fresh-token' })
  })
  it('does not replay an uncertain human write and retains its caller-supplied nonce', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrf())
      .mockRejectedValueOnce(new TypeError('lost response'))
    const api = new ApiClient('buyer', 'test', fetcher)
    await expect(
      api.sendSupportMessage('100', conversationId, sessionId, messageId, '请核实'),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(JSON.parse(fetcher.mock.calls[1]![1]!.body as string)).toEqual({
      clientMessageId: messageId,
      text: '请核实',
    })
  })
  it('scopes staff queue and older transcript reads without invoking a mutation', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ items: [session], nextBeforeId: null }))
      .mockResolvedValueOnce(json(snapshot))
    const api = new ApiClient('staff', 'test', fetcher)
    await api.supportSessions('100', 'WAITING', null)
    await api.merchantSupportSnapshot('100', sessionId, undefined, messageId)
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/merchant/shops/100/support-sessions?status=WAITING',
      `/api/v1/merchant/shops/100/support-sessions/${sessionId}?beforeId=${messageId}`,
    ])
    expect(fetcher.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true)
  })
  it('rejects malformed transcript data and invalid targets before rendering', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ ...snapshot, messages: [{ id: 'bad', text: '<script>' }] }))
    const api = new ApiClient('buyer', 'test', fetcher)
    await expect(api.supportSnapshot('100', conversationId)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
    fetcher.mockClear()
    await expect(api.requestSupport('../101', conversationId, messageId)).rejects.toThrow()
    expect(fetcher).not.toHaveBeenCalled()
  })
})
