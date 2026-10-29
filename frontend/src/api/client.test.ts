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
  receivedAt: null,
}
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'public-test-csrf' }
const reply = {
  reply: '订单已发货',
  type: 'ORDER_DETAILS',
  orders: [order],
  nextAfterId: null,
  knowledge: null,
}
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
  eligibility: null,
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
const eligibility = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'ELIGIBLE',
  canSubmit: true,
  message: '订单在售后期限内，请填写退款原因。',
  receivedAt: '2026-10-20T00:00:00Z',
  deadlineAt: '2026-11-04T00:00:00Z',
  checkedAt: '2026-10-21T00:00:00Z',
  windowDays: 15,
}
const conversation = {
  id: '00000000-0000-4000-8000-000000000031',
  shopId: 100,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
}
const userMessage = {
  id: '00000000-0000-4000-8000-000000000032',
  role: 'USER',
  text: '咨询此订单',
  createdAt: conversation.createdAt,
  response: null,
}
const assistantMessage = {
  id: '00000000-0000-4000-8000-000000000033',
  role: 'ASSISTANT',
  text: reply.reply,
  createdAt: conversation.createdAt,
  response: reply,
}
const storedPair = { conversation, messages: [userMessage, assistantMessage] }

describe('buyer order deletion', () => {
  it('uses a fresh CSRF token for an explicit DELETE with no identity or business payload', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(
      new ApiClient('buyer', 'test', request).deleteOrder('100', order.orderNo),
    ).resolves.toBeUndefined()
    expect(request).toHaveBeenCalledTimes(2)
    expect(request.mock.calls[1]?.[0]).toBe('/api/v1/shops/100/orders/SF10001')
    expect(request.mock.calls[1]?.[1]).toMatchObject({
      method: 'DELETE',
      headers: { [csrf.headerName]: csrf.token },
      redirect: 'error',
    })
    expect(request.mock.calls[1]?.[1]?.body).toBeUndefined()
  })

  it('rejects an unexpected deletion response rather than declaring it successful', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ order }))
    await expect(
      new ApiClient('buyer', 'test', request).deleteOrder('100', order.orderNo),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['0', 'SF10001'],
    ['100', ''],
    ['100', 'bad order'],
  ])('does not send an invalid deletion target %s/%s', async (shop, number) => {
    const request = fetchMock()
    await expect(
      new ApiClient('buyer', 'test', request).deleteOrder(shop!, number!),
    ).rejects.toBeInstanceOf(ApiError)
    expect(request).not.toHaveBeenCalled()
  })

  it('shows a bounded deletion conflict and never replays failed or uncertain deletion', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(
        json(
          {
            code: 'ORDER_DELETION_CONFLICT',
            message: '该订单仍有待处理售后，请处理完成后再删除。',
          },
          409,
        ),
      )
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('network'))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.deleteOrder('100', order.orderNo)).rejects.toMatchObject({
      status: 409,
      message: '该订单仍有待处理售后，请处理完成后再删除。',
    })
    expect(request).toHaveBeenCalledTimes(2)
    await expect(api.deleteOrder('100', order.orderNo)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    })
    expect(request).toHaveBeenCalledTimes(4)
  })

  it('does not send deletion after cancellation while waiting behind another operation', async () => {
    let finish!: (response: Response) => void
    const request = fetchMock().mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const api = new ApiClient('buyer', 'test', request)
    const read = api.afterSaleEligibility('100', order.orderNo)
    const controller = new AbortController()
    const deletion = api.deleteOrder('100', order.orderNo, controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce())
    finish(json(eligibility))
    await read
    await expect(deletion).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledOnce()
  })
})

describe('purchase item snapshots and candidate continuation', () => {
  const item = { productName: '无线耳机', specification: '白色', quantity: 1 }

  it('accepts a bounded purchase preview and a read-only candidate intent', async () => {
    const productOrder = {
      ...order,
      items: [item, { productName: '耳机收纳盒', specification: null, quantity: 2 }],
      itemKinds: 5,
    }
    const response = {
      ...reply,
      type: 'ORDER_SELECTION',
      orders: [productOrder],
      selectionIntent: 'LOGISTICS',
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '耳机物流到哪了'),
    ).resolves.toEqual(response)
  })

  it('preserves missing historical product information instead of inventing an empty purchase', async () => {
    const request = fetchMock().mockResolvedValueOnce(json(csrf)).mockResolvedValueOnce(json(reply))
    const result = await new ApiClient('buyer', 'test', request).chat(
      '100',
      '咨询此订单',
      order.orderNo,
    )
    expect(Object.hasOwn(result.orders[0]!, 'items')).toBe(false)
    expect(Object.hasOwn(result.orders[0]!, 'itemKinds')).toBe(false)
  })

  it.each([
    null,
    '/products/demo/black-earbuds.png',
    '/products/demo/white-mouse.webp',
    '/products/demo/white_mug-2.jpg',
    '/products/demo/cotton-bedding.jpeg',
    `/products/demo/${'a'.repeat(240 - '/products/demo/'.length - '.png'.length)}.png`,
  ])('preserves a valid optional product image %j', async (imageUrl) => {
    const response = {
      ...reply,
      orders: [{ ...order, items: [{ ...item, imageUrl }], itemKinds: 1 }],
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '查询此订单', order.orderNo),
    ).resolves.toEqual(response)
  })

  it.each([
    '',
    true,
    42,
    {},
    [],
    'https://example.com/products/demo/white-mug.png',
    '//example.com/products/demo/white-mug.png',
    'data:image/png;base64,aW1hZ2U=',
    'javascript:alert(1)',
    '/products/demo/../white-mug.png',
    '/products/demo/%2e%2e/white-mug.png',
    '/products/demo/subfolder/white-mug.png',
    '/products/demo/white-mug.png?v=1',
    '/products/demo/white-mug.png#image',
    '/products/demo/white:mug.png',
    '/products/demo/white\\mug.png',
    '/products/demo/White-mug.png',
    '/products/demo/white-mug.PNG',
    '/products/demo/white mug.png',
    '/products/demo/白杯.png',
    '/products/demo/.png',
    '/products/demo/white-mug.svg',
    '/products/demo/white-mug.png\n',
    `/products/demo/${'a'.repeat(241 - '/products/demo/'.length - '.png'.length)}.png`,
  ])('rejects an unsafe or malformed product image %j', async (imageUrl) => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(
        json({ ...reply, orders: [{ ...order, items: [{ ...item, imageUrl }], itemKinds: 1 }] }),
      )
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '查询此订单', order.orderNo),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('accepts item and kind-count boundaries and explicit null continuation on a details response', async () => {
    const response = {
      ...reply,
      orders: [
        {
          ...order,
          items: [
            { productName: '名'.repeat(100), specification: '规'.repeat(80), quantity: 9999 },
          ],
          itemKinds: 20,
        },
      ],
      selectionIntent: null,
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '咨询此订单', order.orderNo),
    ).resolves.toEqual(response)
  })

  it.each([
    { items: null },
    { items: {} },
    { items: [item, item, item, item] },
    { items: [null] },
    { items: [{ ...item, productName: '' }] },
    { items: [{ ...item, productName: '  ' }] },
    { items: [{ ...item, productName: 100 }] },
    { items: [{ ...item, productName: '名'.repeat(101) }] },
    { items: [{ ...item, specification: undefined }] },
    { items: [{ ...item, specification: '规'.repeat(81) }] },
    { items: [{ ...item, quantity: 0 }] },
    { items: [{ ...item, quantity: 10000 }] },
    { items: [{ ...item, quantity: 1.5 }] },
    { items: [{ ...item, quantity: '1' }] },
    { items: [item], itemKinds: 0 },
    { items: [], itemKinds: -1 },
    { items: [], itemKinds: 21 },
    { items: [], itemKinds: 1.5 },
    { items: [], itemKinds: '1' },
  ])('rejects invalid purchase metadata %j', async (invalid) => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, orders: [{ ...order, ...invalid }] }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '咨询此订单', order.orderNo),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each(['AFTER_SALE_REQUEST', 'APPROVE', true, 1, {}, ['LOGISTICS']])(
    'rejects non-enumerated selection intent %j',
    async (selectionIntent) => {
      const response = { ...reply, type: 'ORDER_SELECTION', selectionIntent }
      const request = fetchMock()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json(response))
      await expect(
        new ApiClient('buyer', 'test', request).chat('100', '耳机发货了吗'),
      ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    },
  )

  it('rejects continuation metadata on a non-selection response', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, selectionIntent: 'LOGISTICS' }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '耳机发货了吗'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    { orders: [], nextAfterId: null },
    { orders: [order], nextAfterId: 7347 },
  ])('rejects a continuation without bounded candidate orders %j', async (invalid) => {
    const response = { ...reply, type: 'ORDER_SELECTION', selectionIntent: 'LOGISTICS', ...invalid }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '耳机物流到哪了'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('normalizes an omitted cursor on a nonempty candidate continuation to no cursor', async () => {
    const response = {
      reply: '请选择耳机订单',
      type: 'ORDER_SELECTION',
      orders: [order],
      knowledge: null,
      selectionIntent: 'ORDER_STATUS',
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '耳机发货了吗'),
    ).resolves.toEqual({ ...response, nextAfterId: null })
  })

  it('still rejects a product match from another shop', async () => {
    const response = {
      ...reply,
      type: 'ORDER_SELECTION',
      selectionIntent: 'ORDER_STATUS',
      orders: [{ ...order, shopId: 101, items: [item], itemKinds: 1 }],
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '耳机发货了吗'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})

describe('ApiClient', () => {
  it('opens one shop window, reads its combined history and deletes it once with fresh CSRF', async () => {
    const context = { kind: 'NONE', orderNo: null, sourceIds: [], expiresAt: null }
    const window = { ...storedPair, shopId: 100, nextBeforeId: null, context }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(conversation))
      .mockResolvedValueOnce(json(window))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.openShopWindow('100')).resolves.toEqual(conversation)
    await expect(api.shopWindowMessages('100')).resolves.toEqual(window)
    await expect(api.deleteShopWindow('100', conversation.id)).resolves.toBeUndefined()
    expect(request.mock.calls.map(([path, init]) => [path, init?.method])).toEqual([
      ['/api/v1/security/csrf', 'GET'],
      ['/api/v1/shops/100/chat/window', 'POST'],
      ['/api/v1/shops/100/chat/window', 'GET'],
      ['/api/v1/security/csrf', 'GET'],
      [`/api/v1/shops/100/chat/window/${conversation.id}`, 'DELETE'],
    ])
    const deletion = request.mock.calls[4]?.[1]
    expect(deletion?.headers).toMatchObject({ 'X-CSRF-TOKEN': csrf.token })
    expect(deletion?.credentials).toBe('same-origin')
  })

  it('does not replay a delete when the network outcome is unknown', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('network'))
    await expect(
      new ApiClient('buyer', 'test', request).deleteShopWindow('100', conversation.id),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(request).toHaveBeenCalledTimes(2)
    expect(request.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
  })

  it.each([
    { shopId: 101, conversation, messages: [], nextBeforeId: null, context: null },
    {
      shopId: 100,
      conversation: { ...conversation, shopId: 101 },
      messages: [],
      nextBeforeId: null,
      context: null,
    },
    { shopId: 100, conversation, messages: [], nextBeforeId: null, context: { kind: 'ORDER' } },
    {
      shopId: 100,
      conversation,
      messages: [
        { ...assistantMessage, response: { ...reply, orders: [{ ...order, shopId: 101 }] } },
      ],
      nextBeforeId: null,
      context: null,
    },
  ])('rejects a foreign or malformed shop-window response', async (window) => {
    const request = fetchMock().mockResolvedValue(json(window))
    await expect(
      new ApiClient('buyer', 'test', request).shopWindowMessages('100'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('accepts an empty deleted window without creating a replacement during a read', async () => {
    const window = {
      shopId: 100,
      conversation: null,
      messages: [],
      nextBeforeId: null,
      context: null,
    }
    const request = fetchMock().mockResolvedValue(json(window))
    await expect(
      new ApiClient('buyer', 'test', request).shopWindowMessages('100'),
    ).resolves.toEqual(window)
    expect(request.mock.calls.map(([, init]) => init?.method)).toEqual(['GET'])
  })

  it('reads the two new shop labels without exposing or requiring merchant credentials', async () => {
    const shops = [
      { shopId: 102, name: '数码优选店' },
      { shopId: 103, name: '生活家居店' },
    ]
    const request = fetchMock().mockResolvedValue(json(shops))
    await expect(new ApiClient('buyer', 'test', request).shops()).resolves.toEqual(shops)
    expect(request.mock.calls.map(([path, init]) => [path, init?.method])).toEqual([
      ['/api/v1/shops', 'GET'],
    ])
  })

  it('rejects invalid window targets before sending any request', async () => {
    const request = fetchMock()
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.openShopWindow('0')).rejects.toBeInstanceOf(ApiError)
    await expect(api.deleteShopWindow('100', '../../')).rejects.toBeInstanceOf(ApiError)
    await expect(api.shopWindowMessages('100', 'bad-cursor')).rejects.toBeInstanceOf(ApiError)
    expect(request).not.toHaveBeenCalled()
  })

  it('waits through a bounded Agent retry without aborting at the ordinary request timeout or replaying the message', async () => {
    vi.useFakeTimers()
    const signalTimeout = vi.spyOn(AbortSignal, 'timeout').mockImplementation((millis) => {
      const controller = new AbortController()
      setTimeout(() => controller.abort(), millis)
      return controller.signal
    })
    try {
      const request = fetchMock()
        .mockResolvedValueOnce(json(csrf))
        .mockImplementationOnce(
          (_path, init) =>
            new Promise<Response>((resolve, reject) => {
              init?.signal?.addEventListener('abort', () =>
                reject(new DOMException('Timeout', 'AbortError')),
              )
              setTimeout(() => resolve(json(storedPair)), 26_000)
            }),
        )
      const pending = new ApiClient('buyer', 'test', request).sendConversationMessage(
        '100',
        conversation.id,
        '00000000-0000-4000-8000-000000000034',
        '咨询此订单',
        'SF10001',
      )
      const result = expect(pending).resolves.toEqual(storedPair)
      await vi.advanceTimersByTimeAsync(26_000)
      await result
      expect(request.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    } finally {
      signalTimeout.mockRestore()
      vi.clearAllTimers()
      vi.useRealTimers()
    }
  })

  it('reads conversation lists and history with opaque cursors and no CSRF or business POST', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json({ conversations: [conversation], nextBeforeId: conversation.id }))
      .mockResolvedValueOnce(json({ ...storedPair, nextBeforeId: userMessage.id }))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.conversations('100', conversation.id)).resolves.toEqual({
      conversations: [conversation],
      nextBeforeId: conversation.id,
    })
    await expect(api.conversationMessages('100', conversation.id, userMessage.id)).resolves.toEqual(
      { ...storedPair, nextBeforeId: userMessage.id },
    )
    expect(request.mock.calls.map((call) => [call[0], call[1]?.method])).toEqual([
      [`/api/v1/shops/100/chat/conversations?beforeId=${conversation.id}`, 'GET'],
      [
        `/api/v1/shops/100/chat/conversations/${conversation.id}/messages?beforeId=${userMessage.id}`,
        'GET',
      ],
    ])
    expect(request.mock.calls.every((call) => call[1]?.body === undefined)).toBe(true)
  })

  it('creates and sends only explicitly, with fresh CSRF, client deduplication ID and selected order', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(conversation))
      .mockResolvedValueOnce(json({ ...csrf, token: 'next-token' }))
      .mockResolvedValueOnce(json(storedPair))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.createConversation('100')).resolves.toEqual(conversation)
    await expect(
      api.sendConversationMessage(
        '100',
        conversation.id,
        userMessage.id,
        userMessage.text,
        order.orderNo,
      ),
    ).resolves.toEqual(storedPair)
    expect(request.mock.calls[1]?.[1]?.body).toBeUndefined()
    expect(JSON.parse(String(request.mock.calls[3]?.[1]?.body))).toEqual({
      clientMessageId: userMessage.id,
      message: userMessage.text,
      orderNo: order.orderNo,
    })
    expect(request.mock.calls[3]?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'next-token' })
  })

  it.each([
    { conversations: [{ ...conversation, shopId: 101 }], nextBeforeId: null },
    { conversations: [{ ...conversation, id: 'bad-id' }], nextBeforeId: null },
    { conversations: [{ ...conversation, createdAt: 'not-a-date' }], nextBeforeId: null },
    { conversations: [conversation, conversation], nextBeforeId: null },
    { conversations: [conversation], nextBeforeId: 2 },
    {
      conversations: Array.from({ length: 21 }, (_, index) => ({
        ...conversation,
        id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
      })),
      nextBeforeId: null,
    },
  ])('rejects invalid or cross-shop conversation lists: %s', async (body) => {
    const request = fetchMock().mockResolvedValueOnce(json(body))
    await expect(
      new ApiClient('buyer', 'test', request).conversations('100'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    { conversation: { ...conversation, shopId: 101 } },
    { conversation: { ...conversation, id: userMessage.id } },
    { messages: [{ ...userMessage, response: reply }] },
    { messages: [{ ...assistantMessage, response: null }] },
    { messages: [{ ...assistantMessage, text: '不一致' }] },
    {
      messages: [
        { ...assistantMessage, response: { ...reply, orders: [{ ...order, shopId: 101 }] } },
      ],
    },
    { messages: [userMessage, userMessage] },
    { nextBeforeId: '../invalid' },
  ])(
    'rejects malformed or mismatched stored messages without treating them as actions: %s',
    async (overrides) => {
      const request = fetchMock().mockResolvedValueOnce(
        json({ ...storedPair, nextBeforeId: null, ...overrides }),
      )
      await expect(
        new ApiClient('buyer', 'test', request).conversationMessages('100', conversation.id),
      ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
      expect(request).toHaveBeenCalledOnce()
    },
  )

  it.each([
    { messages: [assistantMessage, userMessage] },
    { messages: [{ ...userMessage, text: '另一条消息' }, assistantMessage] },
    { messages: [userMessage] },
  ])('rejects a mismatched send result rather than assuming success: %s', async (overrides) => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...storedPair, ...overrides }))
    await expect(
      new ApiClient('buyer', 'test', request).sendConversationMessage(
        '100',
        conversation.id,
        userMessage.id,
        userMessage.text,
      ),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('never replays a timed out or rejected stored-message POST automatically', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('network'))
    await expect(
      new ApiClient('buyer', 'test', request).sendConversationMessage(
        '100',
        conversation.id,
        userMessage.id,
        userMessage.text,
      ),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('validates conversation IDs and cursors before making requests', async () => {
    const request = fetchMock()
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.conversations('0')).rejects.toBeInstanceOf(ApiError)
    await expect(api.conversations('100', '../../')).rejects.toBeInstanceOf(ApiError)
    await expect(api.conversationMessages('100', '../../')).rejects.toBeInstanceOf(ApiError)
    await expect(
      api.sendConversationMessage('100', conversation.id, 'bad', '你好'),
    ).rejects.toBeInstanceOf(ApiError)
    expect(request).not.toHaveBeenCalled()
  })

  it('normalizes a legacy order without receipt timestamp to null', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, orders: [{ ...order, receivedAt: undefined }] }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '咨询此订单', order.orderNo),
    ).resolves.toEqual(reply)
  })

  it('confirms receipt with fresh CSRF and no client-supplied dates or identity', async () => {
    const result = {
      order: { ...order, status: 'COMPLETED', receivedAt: eligibility.receivedAt },
      alreadyConfirmed: false,
      eligibility,
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
      .mockResolvedValueOnce(json({ ...csrf, token: 'fresh-second-token' }))
      .mockResolvedValueOnce(json({ ...result, alreadyConfirmed: true }))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.confirmReceipt('100', order.orderNo)).resolves.toEqual(result)
    await expect(api.confirmReceipt('100', order.orderNo)).resolves.toMatchObject({
      alreadyConfirmed: true,
    })
    expect(request.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/orders/SF10001/receipt-confirmation',
      '/api/v1/security/csrf',
      '/api/v1/shops/100/orders/SF10001/receipt-confirmation',
    ])
    expect(request.mock.calls[1]?.[1]?.body).toBeUndefined()
    expect(request.mock.calls[1]?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': csrf.token })
    expect(request.mock.calls[3]?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'fresh-second-token',
    })
  })

  it.each([
    { order: { ...order, status: 'SHIPPED', receivedAt: eligibility.receivedAt } },
    { order: { ...order, status: 'COMPLETED', receivedAt: null } },
    { order: { ...order, status: 'COMPLETED', shopId: 101, receivedAt: eligibility.receivedAt } },
    {
      order: {
        ...order,
        status: 'COMPLETED',
        orderNo: 'SFOTHER',
        receivedAt: eligibility.receivedAt,
      },
    },
    { eligibility: { ...eligibility, receivedAt: '2026-10-19T00:00:00Z' } },
    { eligibility: { ...eligibility, orderNo: 'SFOTHER' } },
    { eligibility: { ...eligibility, deadlineAt: null } },
    { alreadyConfirmed: 'true' },
  ])('rejects an inconsistent receipt result: %s', async (overrides) => {
    const result = {
      order: { ...order, status: 'COMPLETED', receivedAt: eligibility.receivedAt },
      alreadyConfirmed: false,
      eligibility,
      ...overrides,
    }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
    await expect(
      new ApiClient('buyer', 'test', request).confirmReceipt('100', order.orderNo),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('does not replay denied or failed receipt confirmations', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'ACCESS_DENIED' }, 403))
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('network'))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.confirmReceipt('100', order.orderNo)).rejects.toMatchObject({ status: 403 })
    expect(request).toHaveBeenCalledTimes(2)
    await expect(api.confirmReceipt('100', order.orderNo)).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    })
    expect(request).toHaveBeenCalledTimes(4)
  })

  it('shows the bounded known receipt conflict without exposing unknown conflict details', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(
        json({ code: 'ORDER_RECEIPT_CONFLICT', message: '订单当前状态不支持确认收货。' }, 409),
      )
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(
        json({ code: 'UNKNOWN_INTERNAL', message: 'private backend detail' }, 409),
      )
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.confirmReceipt('100', order.orderNo)).rejects.toMatchObject({
      message: '订单当前状态不支持确认收货。',
    })
    await expect(api.confirmReceipt('100', order.orderNo)).rejects.not.toMatchObject({
      message: 'private backend detail',
    })
    expect(request).toHaveBeenCalledTimes(4)
  })

  it('serializes receipt confirmation with chat and eligibility reads', async () => {
    const paths: string[] = []
    const result = {
      order: { ...order, status: 'COMPLETED', receivedAt: eligibility.receivedAt },
      alreadyConfirmed: false,
      eligibility,
    }
    const request = fetchMock().mockImplementation(async (path) => {
      paths.push(String(path))
      if (String(path).endsWith('/csrf')) return json(csrf)
      if (String(path).endsWith('/receipt-confirmation')) return json(result)
      if (String(path).endsWith('/after-sale-eligibility')) return json(eligibility)
      return json(reply)
    })
    const api = new ApiClient('buyer', 'test', request)
    await Promise.all([
      api.chat('100', '咨询此订单', order.orderNo),
      api.confirmReceipt('100', order.orderNo),
      api.afterSaleEligibility('100', order.orderNo),
    ])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
      '/api/v1/security/csrf',
      '/api/v1/shops/100/orders/SF10001/receipt-confirmation',
      '/api/v1/shops/100/orders/SF10001/after-sale-eligibility',
    ])
  })

  it('never sends a receipt confirmation cancelled while waiting in the queue', async () => {
    let finish!: (value: Response) => void
    const request = fetchMock().mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const api = new ApiClient('buyer', 'test', request)
    const read = api.afterSaleEligibility('100', order.orderNo)
    const controller = new AbortController()
    const confirmation = api.confirmReceipt('100', order.orderNo, controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    finish(json(eligibility))
    await read
    await expect(confirmation).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledOnce()
  })

  it('reads eligibility without submitting anything', async () => {
    const request = fetchMock().mockResolvedValue(json(eligibility))
    await expect(
      new ApiClient('buyer', 'test', request).afterSaleEligibility('100', 'SF10001'),
    ).resolves.toEqual(eligibility)
    expect(request).toHaveBeenCalledOnce()
    expect(request).toHaveBeenCalledWith(
      '/api/v1/shops/100/orders/SF10001/after-sale-eligibility',
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it.each([
    { ...eligibility, shopId: 101 },
    { ...eligibility, orderNo: 'SF20001' },
    { ...eligibility, status: 'EXPIRED', canSubmit: true },
    { ...eligibility, receivedAt: null },
    { ...eligibility, checkedAt: 'unknown' },
  ])('rejects malformed or wrong-target eligibility: %s', async (value) => {
    const request = fetchMock().mockResolvedValue(json(value))
    await expect(
      new ApiClient('buyer', 'test', request).afterSaleEligibility('100', 'SF10001'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    { ...eligibility, status: 'REQUIRES_REVIEW', receivedAt: null, deadlineAt: null },
    {
      ...eligibility,
      status: 'REQUIRES_REVIEW',
      receivedAt: '2026-10-22T00:00:00Z',
      deadlineAt: '2026-11-06T00:00:00Z',
    },
  ])('accepts missing or abnormal receipt timestamps for manual review: %s', async (value) => {
    const request = fetchMock().mockResolvedValue(json(value))
    await expect(
      new ApiClient('buyer', 'test', request).afterSaleEligibility('100', 'SF10001'),
    ).resolves.toEqual(value)
  })

  it('sends only the confirmed reason with fresh CSRF for every refund attempt', async () => {
    const result = { ...ticket, eligibility }
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
      .mockResolvedValueOnce(json({ ...csrf, token: 'new-token' }))
      .mockResolvedValueOnce(json(result))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.applyAfterSale('100', 'SF10001', '  商品破损  ')).resolves.toEqual(result)
    await api.applyAfterSale('100', 'SF10001', '商品破损')
    expect(request.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/orders/SF10001/after-sales',
      '/api/v1/security/csrf',
      '/api/v1/shops/100/orders/SF10001/after-sales',
    ])
    expect(JSON.parse(String(request.mock.calls[1]?.[1]?.body))).toEqual({ reason: '商品破损' })
    expect(request.mock.calls[3]?.[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'new-token' })
  })

  it.each(['', '   ', '长'.repeat(501)])(
    'rejects invalid refund reason before network access: %s',
    async (reason) => {
      const request = fetchMock()
      await expect(
        new ApiClient('buyer', 'test', request).applyAfterSale('100', 'SF10001', reason),
      ).rejects.toBeInstanceOf(ApiError)
      expect(request).not.toHaveBeenCalled()
    },
  )

  it('shows a safe deadline explanation without replaying the refund POST', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(
        json({ code: 'BUSINESS_RULE_VIOLATION', message: '已超过确认收货后15天的售后期限。' }, 409),
      )
    await expect(
      new ApiClient('buyer', 'test', request).applyAfterSale('100', 'SF10001', '商品破损'),
    ).rejects.toMatchObject({ status: 409, message: '已超过确认收货后15天的售后期限。' })
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('rejects a mismatched refund submission result', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...ticket, orderNo: 'SF20001' }))
    await expect(
      new ApiClient('buyer', 'test', request).applyAfterSale('100', 'SF10001', '商品破损'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('does not submit a refund cancelled while queued', async () => {
    let finish!: (value: Response) => void
    const request = fetchMock().mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const api = new ApiClient('buyer', 'test', request)
    const read = api.afterSaleEligibility('100', 'SF10001')
    const controller = new AbortController()
    const refund = api.applyAfterSale('100', 'SF10001', '商品破损', controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    finish(json(eligibility))
    await read
    await expect(refund).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledTimes(1)
  })

  it.each([{ orders: [] }, { orders: [order] }])(
    'accepts a refund form with zero or one owned order',
    async ({ orders }) => {
      const response = {
        reply: '请填写退款原因',
        type: 'AFTER_SALE_FORM',
        orders,
        nextAfterId: null,
        knowledge: null,
      }
      const request = fetchMock()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json(response))
      await expect(
        new ApiClient('buyer', 'test', request).chat('100', '申请退款'),
      ).resolves.toEqual(response)
    },
  )

  it('rejects an ambiguous multi-order refund form', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ ...reply, type: 'AFTER_SALE_FORM', orders: [order, order] }))
    await expect(
      new ApiClient('buyer', 'test', request).chat('100', '申请退款'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

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

  it('shows safe order-page guidance for a model outage without trusting provider text', async () => {
    const request = fetchMock().mockResolvedValue(
      json({ code: 'MODEL_UNAVAILABLE', message: 'private-provider-detail' }, 503),
    )
    await expect(new ApiClient('buyer', 'test', request).authenticate()).rejects.toMatchObject({
      status: 503,
      code: 'MODEL_UNAVAILABLE',
      message: '智能服务暂时不可用，请稍后重试，或在订单页直接查看订单。',
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
    await expect(new ApiClient('reviewer', 'test', request).pendingReviews('100')).resolves.toEqual(
      [ticket],
    )
    expect(request).toHaveBeenCalledWith(
      '/api/v1/merchant/shops/100/after-sales',
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
    await expect(api.decideReview('100', ticket.id, 'APPROVE', '  核实通过  ')).resolves.toEqual(
      result,
    )
    expect(request.mock.calls[1]?.[0]).toBe(
      `/api/v1/merchant/shops/100/after-sales/${ticket.id}/decision`,
    )
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
      api.pendingReviews('100'),
      api.decideReview('100', ticket.id, 'REJECT', '请补充凭证'),
    ])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      '/api/v1/shops/100/chat/messages',
      '/api/v1/merchant/shops/100/after-sales',
      '/api/v1/security/csrf',
      `/api/v1/merchant/shops/100/after-sales/${ticket.id}/decision`,
    ])
  })

  it('never automatically replays a forbidden or conflicting human decision', async () => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'ACCESS_DENIED' }, 403))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json({ code: 'REVIEW_CONFLICT' }, 409))
    const api = new ApiClient('reviewer', 'test', request)
    await expect(api.decideReview('100', ticket.id, 'APPROVE', '核实通过')).rejects.toMatchObject({
      status: 403,
    })
    expect(request).toHaveBeenCalledTimes(2)
    await expect(api.decideReview('100', ticket.id, 'APPROVE', '核实通过')).rejects.toMatchObject({
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
      new ApiClient('reviewer', 'test', request).decideReview('100', id, 'APPROVE', note),
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
    await expect(
      new ApiClient('reviewer', 'test', request).pendingReviews('100'),
    ).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    })
  })

  it.each([
    { ...ticket, status: 'PENDING_REVIEW' },
    { ...ticket, id: '00000000-0000-4000-8000-000000000002', status: 'APPROVED' },
  ])('does not acknowledge an unrelated or undecided review response: %s', async (result) => {
    const request = fetchMock()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(result))
    await expect(
      new ApiClient('reviewer', 'test', request).decideReview(
        '100',
        ticket.id,
        'APPROVE',
        '核实通过',
      ),
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
    await expect(new ApiClient('reviewer', 'test', request).reviewHistory('100')).resolves.toEqual([
      ticket,
      executionTicket,
    ])
    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/merchant/shops/100/after-sales/history')
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
      new ApiClient('reviewer', 'test', request).retryExecution('100', ticket.id),
    ).resolves.toEqual(result)
    expect(request.mock.calls[1]?.[0]).toBe(
      `/api/v1/merchant/shops/100/after-sales/${ticket.id}/execution/retry`,
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
      new ApiClient('reviewer', 'test', request).retryExecution('100', ticket.id),
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
      api.retryExecution('100', ticket.id),
      api.reviewHistory('100'),
      api.chat('100', '我的订单'),
    ])
    expect(paths).toEqual([
      '/api/v1/security/csrf',
      `/api/v1/merchant/shops/100/after-sales/${ticket.id}/execution/retry`,
      '/api/v1/merchant/shops/100/after-sales/history',
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
    const read = api.reviewHistory('100')
    const controller = new AbortController()
    const retry = api.retryExecution('100', ticket.id, controller.signal)
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
      new ApiClient('reviewer', 'test', request).decideReview(
        '100',
        ticket.id,
        'APPROVE',
        '核实通过',
      ),
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
    const read = api.pendingReviews('100')
    const decision = api.decideReview('100', ticket.id, 'APPROVE', '核实通过', controller.signal)
    controller.abort()
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    finishRead(json([ticket]))
    await read
    await expect(decision).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledTimes(1)
  })
})
