import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const source = {
  sourceId: 'kb-gift',
  version: 'v1',
  title: '礼盒与贺卡包装说明',
  content: '礼盒可以搭配贺卡。',
}
const answer = { status: 'ANSWERED', sources: [source] }
const reply = { reply: '可以搭配贺卡。', type: 'TEXT', orders: [], nextAfterId: null }
const conversation = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  createdAt: '2026-10-24T00:00:00Z',
  updatedAt: '2026-10-24T00:00:00Z',
}
const assistant = {
  id: '00000000-0000-4000-8000-000000000002',
  role: 'ASSISTANT',
  text: reply.reply,
  createdAt: conversation.createdAt,
  response: reply,
}
const json = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' }

async function send(knowledge: unknown) {
  const request = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(json(csrf))
    .mockResolvedValueOnce(json({ ...reply, knowledge }))
  return new ApiClient('buyer', 'test', request).chat('100', '能放张祝福卡吗？')
}

describe('chat knowledge evidence contract', () => {
  it.each([
    null,
    answer,
    { status: 'NO_MATCH', sources: [] },
    { status: 'UNAVAILABLE', sources: [] },
  ])('accepts supported evidence states: %s', async (knowledge) => {
    await expect(send(knowledge)).resolves.toMatchObject({ knowledge })
  })

  it('accepts at most two distinct sources and preserves their snapshot text exactly', async () => {
    const second = {
      ...source,
      sourceId: 'public-gift',
      version: '2026.10',
      content: '  原文\n保留空白  ',
    }
    const knowledge = { status: 'ANSWERED', sources: [source, second] }
    await expect(send(knowledge)).resolves.toMatchObject({ knowledge })
  })

  it.each([
    'https://example.com',
    [],
    {},
    { status: 'OTHER', sources: [] },
    { status: ['NO_MATCH'], sources: [] },
    { status: null, sources: [] },
    { status: 'ANSWERED', sources: null },
    { status: 'ANSWERED', sources: [] },
    { status: 'NO_MATCH', sources: [source] },
    { status: 'UNAVAILABLE', sources: [source] },
    { status: 'ANSWERED', sources: [source, source] },
    { status: 'ANSWERED', sources: [source, { ...source, version: 'v2' }] },
    {
      status: 'ANSWERED',
      sources: [source, { ...source, sourceId: 'kb-two' }, { ...source, sourceId: 'kb-three' }],
    },
    ...[
      { sourceId: 'https://external.example' },
      { sourceId: 'A' },
      { sourceId: 'a' },
      { sourceId: 'x'.repeat(65) },
      { sourceId: '../kb-gift' },
      { sourceId: 'kb-gift\n' },
      { version: 'v' },
      { version: 'v1<script>' },
      { version: 'v1\n' },
      { version: 'x'.repeat(65) },
      { title: '' },
      { title: '  ' },
      { title: 'x'.repeat(241) },
      { content: '' },
      { content: '  ' },
      { content: 'x'.repeat(16_385) },
    ].map((override) => ({ status: 'ANSWERED', sources: [{ ...source, ...override }] })),
  ])('rejects malformed, duplicate, ambiguous or oversized evidence: %s', async (knowledge) => {
    await expect(send(knowledge)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('accepts inclusive source length boundaries', async () => {
    const knowledge = {
      status: 'ANSWERED',
      sources: [
        {
          sourceId: 'x'.repeat(64),
          version: 'x'.repeat(64),
          title: 'x'.repeat(240),
          content: 'x'.repeat(16_384),
        },
      ],
    }
    await expect(send(knowledge)).resolves.toMatchObject({ knowledge })
  })

  it.each([
    { type: 'ORDER_SELECTION' },
    { type: 'AFTER_SALE_FORM' },
    { type: ['TEXT'] },
    { nextAfterId: 1 },
  ])(
    'rejects knowledge evidence attached to incompatible business result shapes: %s',
    async (override) => {
      const request = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json({ ...reply, ...override, knowledge: answer }))
      await expect(
        new ApiClient('buyer', 'test', request).chat('100', 'test'),
      ).rejects.toMatchObject({
        code: 'INVALID_RESPONSE',
      })
    },
  )

  it('normalizes old chat and stored response JSON without knowledge to null', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(reply))
      .mockResolvedValueOnce(json({ conversation, messages: [assistant], nextBeforeId: null }))
    const api = new ApiClient('buyer', 'test', request)
    await expect(api.chat('100', '你好')).resolves.toEqual({ ...reply, knowledge: null })
    const restored = await api.conversationMessages('100', conversation.id)
    expect(restored.messages[0]!.response?.knowledge).toBeNull()
  })

  it('restores evidence from response JSON with one history GET and no current article fetch', async () => {
    const response = { ...reply, knowledge: answer }
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(
      json({
        conversation,
        messages: [{ ...assistant, response }],
        nextBeforeId: null,
      }),
    )
    const restored = await new ApiClient('buyer', 'test', request).conversationMessages(
      '100',
      conversation.id,
    )
    expect(restored.messages[0]!.response?.knowledge).toEqual(answer)
    expect(request).toHaveBeenCalledOnce()
    expect(request.mock.calls[0]![0]).toBe(
      `/api/v1/shops/100/chat/conversations/${conversation.id}/messages`,
    )
  })

  it('rejects malformed stored evidence rather than dropping it and showing an ungrounded answer', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(
      json({
        conversation,
        messages: [
          { ...assistant, response: { ...reply, knowledge: { status: 'ANSWERED', sources: [] } } },
        ],
        nextBeforeId: null,
      }),
    )
    await expect(
      new ApiClient('buyer', 'test', request).conversationMessages('100', conversation.id),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})
