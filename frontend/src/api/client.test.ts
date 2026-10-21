import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError, validShopId } from './client'

const order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'public-test-csrf' }
const reply = { reply: '订单已发货', type: 'ORDER_DETAILS', orders: [order], nextAfterId: null }
const ticket = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '商品破损',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  execution: null,
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
const fetchMock = () => vi.fn<typeof fetch>()
const execution = {
  status: 'FAILED',
  amount: 199.9,
  currency: 'CNY',
  attemptCount: 1,
  maxAttempts: 5,
  nextAttemptAt: null,
  completedAt: null,
  reference: null,
  lastError: '模拟渠道暂时不可用',
  retryable: true,
}
const executionTicket = { ...ticket, status: 'APPROVED', execution }

describe('ApiClient', () => {
  it('invokes the transport without binding it to the client instance', async () => {
    let receiver: unknown = 'not-called'
    const request: typeof fetch = async function (this: unknown) {
      receiver = this
      return json(csrf)
    }
    await new ApiClient('buyer', 'test', request).authenticate()
    expect(receiver).toBeUndefined()
  })

  it('authenticates with UTF-8 Basic credentials and same-origin cookie policy', async () => {
    const request = fetchMock().mockResolvedValue(json(csrf))
    const api = new ApiClient('买家', '测试密码', request)
    await api.authenticate()
    const options = request.mock.calls[0]?.[1]
    const expected = btoa(
      Array.from(new TextEncoder().encode('买家:测试密码'), (b) => String.fromCharCode(b)).join(''),
    )
    expect(request).toHaveBeenCalledWith(
      '/api/v1/security/csrf',
      expect.objectContaining({
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        method: 'GET',
      }),
    )
    expect((options?.headers as Record<string, string>).Authorization).toBe(`Basic ${expected}`)
  })

  it('sends the selected order and CSRF header, not buyer identity or shop in the body', async () => {
    const request = fetchMock().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json(reply))
    const api = new ApiClient('buyer', 'public-test-only', request)
    await expect(api.chat('100', '咨询此订单', 'SF10001')).resolves.toEqual(reply)
    expect(request.mock.calls[1]?.[0]).toBe('/api/v1/shops/100/chat/messages')
    const options = request.mock.calls[1]?.[1]
    expect(JSON.parse(String(options?.body))).toEqual({ message: '咨询此订单', orderNo: 'SF10001' })
    expect(options?.headers).toMatchObject({ 'X-CSRF-TOKEN': csrf.token })
  })

  it('obtains a fresh CSRF token for each POST when Basic authentication rotates it', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(reply))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(reply))
    const api = new ApiClient('buyer', 'public-test-only', request)
    await api.chat('100', '查订单 SF10001')
    await api.chat('100', '查订单 SF10001')
    expect(request.mock.calls.filter((call) => call[0] === '/api/v1/security/csrf')).toHaveLength(2)
  })

  it('serializes overlapping token-and-POST pairs instead of racing session rotation', async () => {
    const paths: string[] = []
    const request = fetchMock().mockImplementation(async (path) => {
      paths.push(String(path))
      return String(path).endsWith('/csrf') ? json(csrf) : json(reply)
    })
    const api = new ApiClient('buyer', 'test', request)
    await Promise.all([api.chat('100', '我的订单'), api.chat('100', '查订单 SF10001')])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
    ])
  })

  it('does not replay forbidden POSTs and refreshes CSRF for the next explicit attempt', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'ACCESS_DENIED' }, 403))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(reply))
    const api = new ApiClient('buyer', 'public-test-only', request)
    await expect(api.chat('100', '查订单 SF10001')).rejects.toMatchObject({ status: 403 })
    expect(request).toHaveBeenCalledTimes(2)
    await api.chat('100', '查订单 SF10001')
    expect(request.mock.calls.filter((call) => call[0] === '/api/v1/security/csrf')).toHaveLength(2)
  })

  it.each(['', '0', '-1', '1/../../', '1.1', '9007199254740992'])(
    'rejects unsafe shop %s before network use',
    async (shop) => {
      const request = fetchMock()
      const api = new ApiClient('buyer', 'public-test-only', request)
      await expect(api.chat(shop, '我的订单')).rejects.toBeInstanceOf(ApiError)
      expect(request).not.toHaveBeenCalled()
    },
  )

  it.each(['', '  ', 'x'.repeat(1001)])('validates messages', async (message) => {
    const request = fetchMock()
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', message),
    ).rejects.toBeInstanceOf(ApiError)
    expect(request).not.toHaveBeenCalled()
  })

  it('rejects ambiguous Basic usernames', () => {
    expect(() => new ApiClient('buyer:other', 'test')).toThrow(ApiError)
    expect(() => new ApiClient('', 'test')).toThrow(ApiError)
    expect(() => new ApiClient('buyer', '')).toThrow(ApiError)
  })

  it('rejects cross-shop response data rather than rendering it', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, orders: [{ ...order, shopId: 101 }] }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '我的订单'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('rejects a cursor that cannot be represented exactly by JavaScript', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, nextAfterId: Number.MAX_SAFE_INTEGER + 1 }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '我的订单'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('cannot replace the Authorization header through a malformed CSRF response', async () => {
    const request = fetchMock().mockResolvedValue(json({ ...csrf, headerName: 'Authorization' }))
    await expect(new ApiClient('buyer', 'test', request).authenticate()).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it('maps a network error to a safe user-facing message', async () => {
    const request = fetchMock().mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(new ApiClient('buyer', 'test', request).authenticate()).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    })
  })

  it('does not expose arbitrary provider error text for server errors', async () => {
    const request = fetchMock().mockResolvedValue(
      json({ message: 'sensitive-provider-detail' }, 503),
    )
    await expect(new ApiClient('buyer', 'test', request).authenticate()).rejects.toMatchObject({
      status: 503,
      message: '服务暂时不可用，请稍后重试。',
    })
  })

  it('rejects an HTML response and malformed JSON', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(new Response('<html>proxy error</html>'))
      .mockResolvedValueOnce(
        new Response('{oops', { headers: { 'content-type': 'application/json' } }),
      )
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.authenticate()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(api.authenticate()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('distinguishes deliberate cancellation from network failure', async () => {
    const cancelled = new AbortController()
    cancelled.abort()
    const request = fetchMock().mockRejectedValue(new DOMException('Aborted', 'AbortError'))
    await expect(
      new ApiClient('buyer', 'test', request).authenticate(cancelled.signal),
    ).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('accepts only positive safe-integer shop contexts', () => {
    expect(validShopId('100')).toBe(true)
    expect(validShopId('01')).toBe(false)
    expect(validShopId('NaN')).toBe(false)
  })

  it('loads pending reviews with authentication and the same cookie policy', async () => {
    const request = fetchMock().mockResolvedValue(json([ticket]))
    await expect(new ApiClient('reviewer', 'test', request).pendingReviews()).resolves.toEqual([
      ticket,
    ])
    expect(request).toHaveBeenCalledWith(
      '/api/v1/reviews/after-sales',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: expect.objectContaining({ Authorization: expect.stringMatching(/^Basic /) }),
      }),
    )
  })

  it('uses a fresh CSRF token for an explicit review decision without sending buyer identity', async () => {
    const result = { ...ticket, status: 'APPROVED', reviewer: 'reviewer', reviewNote: '核实通过' }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
    const api = new ApiClient('reviewer', 'test', request)
    await expect(api.decideReview(ticket.id, 'APPROVE', '  核实通过  ')).resolves.toEqual(result)
    expect(request.mock.calls[1]?.[0]).toBe(`/api/v1/reviews/after-sales/${ticket.id}/decision`)
    const options = request.mock.calls[1]?.[1]
    expect(options?.method).toBe('POST')
    expect(options?.headers).toMatchObject({ 'X-CSRF-TOKEN': csrf.token })
    expect(JSON.parse(String(options?.body))).toEqual({ decision: 'APPROVE', note: '核实通过' })
  })

  it('serializes review reads and decisions with chat token-and-POST pairs', async () => {
    const paths: string[] = []
    const request = fetchMock().mockImplementation(async (path) => {
      const value = String(path)
      paths.push(value)
      if (value.endsWith('/csrf')) return json(csrf)
      if (value.endsWith('/chat/messages')) return json(reply)
      if (value.endsWith('/decision')) return json({ ...ticket, status: 'REJECTED' })
      return json([ticket])
    })
    const api = new ApiClient('reviewer', 'test', request)
    await Promise.all([
      api.chat('100', '我的订单'),
      api.pendingReviews(),
      api.decideReview(ticket.id, 'REJECT', '请补充凭证'),
    ])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
      '/api/v1/reviews/after-sales',
      '/api/v1/security/csrf',
      `/api/v1/reviews/after-sales/${ticket.id}/decision`,
    ])
  })

  it('never automatically replays a forbidden or conflicting human decision', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'ACCESS_DENIED' }, 403))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'REVIEW_CONFLICT' }, 409))
    const api = new ApiClient('reviewer', 'test', request)
    await expect(api.decideReview(ticket.id, 'APPROVE', '核实通过')).rejects.toMatchObject({
      status: 403,
    })
    expect(request).toHaveBeenCalledTimes(2)
    await expect(api.decideReview(ticket.id, 'APPROVE', '核实通过')).rejects.toMatchObject({
      status: 409,
    })
    expect(request).toHaveBeenCalledTimes(4)
  })

  it.each([
    { id: '-'.repeat(36), note: '核实通过' },
    { id: ticket.id, note: '   ' },
    { id: ticket.id, note: '长'.repeat(501) },
  ])('rejects invalid review inputs before network access: %s', async ({ id, note }) => {
    const request = fetchMock()
    await expect(
      new ApiClient('reviewer', 'test', request).decideReview(id, 'APPROVE', note),
    ).rejects.toBeInstanceOf(ApiError)
    expect(request).not.toHaveBeenCalled()
  })

  it.each([
    { ...ticket, id: 'not-a-uuid' },
    { ...ticket, shopId: 0 },
    { ...ticket, shopId: Number.MAX_SAFE_INTEGER + 1 },
    { ...ticket, status: 'APPROVED' },
  ])('rejects a malformed or non-pending review queue item: %s', async (item) => {
    const request = fetchMock().mockResolvedValue(json([item]))
    await expect(new ApiClient('reviewer', 'test', request).pendingReviews()).rejects.toMatchObject(
      {
        code: 'INVALID_RESPONSE',
      },
    )
  })

  it.each([
    { ...ticket, status: 'PENDING_REVIEW' },
    { ...ticket, id: '00000000-0000-4000-8000-000000000002', status: 'APPROVED' },
  ])('does not acknowledge an unrelated or undecided review response: %s', async (result) => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
    await expect(
      new ApiClient('reviewer', 'test', request).decideReview(ticket.id, 'APPROVE', '核实通过'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('reads current-shop after-sales and rejects another shop in the response', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json([executionTicket]))
      .mockResolvedValueOnce(json([{ ...executionTicket, shopId: 101 }]))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.ownedAfterSales('100')).resolves.toEqual([executionTicket])
    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/shops/100/after-sales')
    await expect(api.ownedAfterSales('100')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('loads global review history separately from the pending queue', async () => {
    const request = fetchMock().mockResolvedValue(json([ticket, executionTicket]))
    await expect(new ApiClient('reviewer', 'test', request).reviewHistory()).resolves.toEqual([
      ticket,
      executionTicket,
    ])
    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/reviews/after-sales/history')
  })

  it('normalizes a legacy ticket without execution to null', async () => {
    const request = fetchMock().mockResolvedValue(json([{ ...ticket, execution: undefined }]))
    await expect(new ApiClient('buyer', 'test', request).ownedAfterSales('100')).resolves.toEqual([
      ticket,
    ])
  })

  it.each([
    { ...execution, amount: -1 },
    { ...execution, currency: 'USD' },
    { ...execution, attemptCount: 1.5 },
    { ...execution, attemptCount: 6 },
    { ...execution, status: 'SUCCEEDED', retryable: true },
    { ...execution, reference: 123 },
  ])('rejects malformed execution data before displaying a financial result: %s', async (value) => {
    const request = fetchMock().mockResolvedValue(json([{ ...executionTicket, execution: value }]))
    await expect(
      new ApiClient('buyer', 'test', request).ownedAfterSales('100'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('uses a fresh CSRF token for explicit execution retry without submitting an amount', async () => {
    const result = {
      ...executionTicket,
      execution: { ...execution, status: 'PENDING', retryable: false },
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
    await expect(
      new ApiClient('reviewer', 'test', request).retryExecution(ticket.id),
    ).resolves.toEqual(result)
    expect(request.mock.calls[1]?.[0]).toBe(
      `/api/v1/reviews/after-sales/${ticket.id}/execution/retry`,
    )
    expect(request.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': csrf.token },
    })
    expect(request.mock.calls[1]?.[1]?.body).toBeUndefined()
  })

  it('never replays an execution retry when the result conflicts', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'REVIEW_CONFLICT' }, 409))
    await expect(
      new ApiClient('reviewer', 'test', request).retryExecution(ticket.id),
    ).rejects.toMatchObject({ status: 409 })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('serializes execution retry with history reads and chat requests', async () => {
    const paths: string[] = []
    const request = fetchMock().mockImplementation(async (path) => {
      const value = String(path)
      paths.push(value)
      if (value.endsWith('/csrf')) return json(csrf)
      if (value.endsWith('/retry')) return json(executionTicket)
      if (value.endsWith('/history')) return json([executionTicket])
      return json(reply)
    })
    const api = new ApiClient('reviewer', 'test', request)
    await Promise.all([
      api.retryExecution(ticket.id),
      api.reviewHistory(),
      api.chat('100', '我的订单'),
    ])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      `/api/v1/reviews/after-sales/${ticket.id}/execution/retry`,
      '/api/v1/reviews/after-sales/history',
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
    ])
  })

  it('does not send an execution retry cancelled while queued', async () => {
    let finish!: (response: Response) => void
    const request = fetchMock().mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const api = new ApiClient('reviewer', 'test', request)
    const read = api.reviewHistory()
    const controller = new AbortController()
    const retry = api.retryExecution(ticket.id, controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    finish(json([executionTicket]))
    await read
    await expect(retry).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('distinguishes a missing review from a missing order', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'REVIEW_NOT_FOUND' }, 404))
    await expect(
      new ApiClient('reviewer', 'test', request).decideReview(ticket.id, 'APPROVE', '核实通过'),
    ).rejects.toMatchObject({ status: 404, message: '未找到该售后申请，请刷新审核列表后重试。' })
  })

  it('does not send a human decision cancelled while it was waiting in the queue', async () => {
    let finishRead!: (value: Response) => void
    const request = fetchMock().mockReturnValueOnce(
      new Promise((done) => {
        finishRead = done
      }),
    )
    const api = new ApiClient('reviewer', 'test', request)
    const controller = new AbortController()
    const read = api.pendingReviews()
    const decision = api.decideReview(ticket.id, 'APPROVE', '核实通过', controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    finishRead(json([ticket]))
    await read
    await expect(decision).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledTimes(1)
  })
})
