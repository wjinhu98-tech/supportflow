import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const response = { reply: 'reply', type: 'TEXT', orders: [], nextAfterId: null }
const conversation = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  createdAt: '2026-10-25T00:00:00Z',
  updatedAt: '2026-10-25T00:00:00Z',
}
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' }
const none = { kind: 'NONE', orderNo: null, sourceIds: [], expiresAt: null }
const orderContext = {
  kind: 'ORDER',
  orderNo: 'SF10001',
  sourceIds: [],
  expiresAt: '2026-10-25T00:30:00Z',
}
const knowledgeContext = {
  kind: 'KNOWLEDGE',
  orderNo: null,
  sourceIds: ['kb-gift'],
  expiresAt: '2026-10-25T00:30:00Z',
}
const json = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })

function pair(text: string, context: unknown = none) {
  return [
    {
      id: '00000000-0000-4000-8000-000000000002',
      role: 'USER',
      text,
      createdAt: conversation.createdAt,
      response: null,
    },
    {
      id: '00000000-0000-4000-8000-000000000003',
      role: 'ASSISTANT',
      text: response.reply,
      createdAt: conversation.createdAt,
      response: { ...response, context },
    },
  ]
}

async function send(context: unknown) {
  const request = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(json(csrf))
    .mockResolvedValueOnce(json({ ...response, context }))
  return new ApiClient('buyer', 'test', request).chat('100', 'question')
}

describe('controlled conversation context protocol', () => {
  it.each([
    null,
    none,
    orderContext,
    knowledgeContext,
    { ...orderContext, orderNo: 'x'.repeat(64) },
    { ...orderContext, expiresAt: '2026-10-25T08:30:00.123456789+08:00' },
    { ...knowledgeContext, sourceIds: ['kb-gift', 'kb-wash'] },
    { ...orderContext, expiresAt: '2020-01-01T00:00:00Z' },
  ])('accepts compatible pointers, including expired historical pointers: %s', async (context) => {
    await expect(send(context)).resolves.toMatchObject({ context })
  })

  it('keeps legacy missing context empty and accepts explicit null in restored replies', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(response))
      .mockResolvedValueOnce(
        json({ conversation, messages: pair('question', null), nextBeforeId: null }),
      )
    const client = new ApiClient('buyer', 'test', request)
    expect((await client.chat('100', 'question')).context ?? null).toBeNull()
    const restored = await client.conversationMessages('100', conversation.id)
    expect(restored.messages[1]!.response!.context).toBeNull()
  })

  it.each([
    'ORDER',
    [],
    {},
    { ...none, kind: ['NONE'] },
    { ...none, kind: 'OTHER' },
    { ...none, sourceIds: null },
    { ...none, orderNo: 'SF10001' },
    { ...none, sourceIds: ['kb-gift'] },
    { ...none, expiresAt: orderContext.expiresAt },
    { ...orderContext, orderNo: null },
    { ...orderContext, orderNo: '' },
    { ...orderContext, orderNo: 'x'.repeat(65) },
    { ...orderContext, orderNo: 'SF 10001' },
    { ...orderContext, orderNo: 'SF10001\n' },
    { ...orderContext, orderNo: 'SF\u000010001' },
    { ...orderContext, sourceIds: ['kb-gift'] },
    { ...orderContext, expiresAt: null },
    { ...orderContext, expiresAt: 1792888200000 },
    { ...orderContext, expiresAt: '2026-10-25' },
    { ...orderContext, expiresAt: '2026-10-25T00:30:00' },
    { ...orderContext, expiresAt: '2026-02-30T00:30:00Z' },
    { ...orderContext, expiresAt: '2026-10-25T24:00:00Z' },
    { ...orderContext, expiresAt: '2026-10-25T00:30:00Z\n' },
    { ...knowledgeContext, orderNo: 'SF10001' },
    { ...knowledgeContext, sourceIds: [] },
    { ...knowledgeContext, sourceIds: ['kb-gift', 'kb-gift'] },
    { ...knowledgeContext, sourceIds: ['kb-gift', 'kb-wash', 'kb-other'] },
    { ...knowledgeContext, sourceIds: [null] },
    { ...knowledgeContext, sourceIds: ['https://example.com'] },
    { ...knowledgeContext, sourceIds: ['kb-gift\n'] },
    { ...knowledgeContext, sourceIds: ['a'] },
    { ...knowledgeContext, sourceIds: ['x'.repeat(65)] },
  ])('rejects malformed, contradictory or ambiguous pointers: %s', async (context) => {
    await expect(send(context)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('retains only safe reference fields and restores history with one GET, without fetching articles', async () => {
    const context = { ...knowledgeContext, title: 'untrusted fact', authorization: 'ADMIN' }
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        json({ conversation, messages: pair('question', context), nextBeforeId: null }),
      )
    const restored = await new ApiClient('buyer', 'test', request).conversationMessages(
      '100',
      conversation.id,
    )
    expect(restored.messages[1]!.response!.context).toEqual(knowledgeContext)
    expect(request).toHaveBeenCalledOnce()
    expect(request.mock.calls[0]![0]).toBe(
      `/api/v1/shops/100/chat/conversations/${conversation.id}/messages`,
    )
  })

  it('rejects malformed stored pointers rather than treating history as current context', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(
      json({
        conversation,
        messages: pair('question', { ...knowledgeContext, sourceIds: [] }),
        nextBeforeId: null,
      }),
    )
    await expect(
      new ApiClient('buyer', 'test', request).conversationMessages('100', conversation.id),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([true, false])(
    'sends reset only on the user message, preserving CSRF and explicit order: %s',
    async (reset) => {
      const request = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json({ conversation, messages: pair('question') }))
      await new ApiClient('buyer', 'test', request).sendConversationMessage(
        '100',
        conversation.id,
        '00000000-0000-4000-8000-000000000004',
        'question',
        'SF10002',
        undefined,
        reset,
      )
      expect(request).toHaveBeenCalledTimes(2)
      const options = request.mock.calls[1]![1]!
      expect(options.method).toBe('POST')
      expect(options.headers).toMatchObject({ 'X-CSRF-TOKEN': 'test-csrf' })
      expect(JSON.parse(String(options.body))).toEqual({
        clientMessageId: '00000000-0000-4000-8000-000000000004',
        message: 'question',
        orderNo: 'SF10002',
        ...(reset ? { resetContext: true } : {}),
      })
    },
  )

  it.each(['true', 1, null, {}])(
    'rejects a non-boolean reset before sending anything: %s',
    async (reset) => {
      const request = vi.fn<typeof fetch>()
      await expect(
        new ApiClient('buyer', 'test', request).sendConversationMessage(
          '100',
          conversation.id,
          '00000000-0000-4000-8000-000000000004',
          'question',
          undefined,
          undefined,
          reset as unknown as boolean,
        ),
      ).rejects.toThrow()
      expect(request).not.toHaveBeenCalled()
    },
  )
})
