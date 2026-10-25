import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'
import { validKnowledgeInput } from './knowledge'
import {
  knowledgeDraft as draft,
  knowledgePublished as published,
  knowledgeSaved as saved,
  knowledgeRepublished as republished,
  knowledgeOffline as offline,
  knowledgeAudit as audit,
} from '../test/knowledgeFixtures'

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } })
const csrf = { token: 'test-csrf', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }
const input = { title: draft.title, keywords: [...draft.keywords], content: draft.content }
const base = '/api/v1/merchant/shops/100/knowledge'

describe('knowledge API contract', () => {
  it('strictly requires boolean capabilities instead of guessing them from the staff role', async () => {
    for (const capabilities of [
      undefined,
      {},
      { reviewAfterSales: 'true', manageKnowledge: true },
    ]) {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValue(json({ username: 'staff', accountType: 'MERCHANT_STAFF', capabilities }))
      await expect(new ApiClient('staff', 'test', fetcher).currentAccount()).rejects.toMatchObject({
        code: 'INVALID_RESPONSE',
      })
    }
  })
  it('preserves a knowledge-only capability independently of after-sale review', async () => {
    const me = {
      username: 'editor',
      accountType: 'MERCHANT_STAFF',
      capabilities: { reviewAfterSales: false, manageKnowledge: true },
    }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(me))
    expect(await new ApiClient('editor', 'test', fetcher).currentAccount()).toEqual(me)
  })
  it('reads a shop-scoped page with an opaque article cursor and no mutating request', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ items: [draft], nextBeforeId: draft.id }))
    expect(
      await new ApiClient('editor', 'test', fetcher).knowledgeArticles('100', draft.id),
    ).toEqual({ items: [draft], nextBeforeId: draft.id })
    expect(fetcher.mock.calls[0]?.[0]).toBe(`${base}?beforeId=${draft.id}`)
    expect(fetcher.mock.calls[0]?.[1]?.method).toBe('GET')
  })
  it.each([
    { items: [{ ...draft, shopId: 101 }], nextBeforeId: null },
    { items: [draft, draft], nextBeforeId: null },
    { items: [], nextBeforeId: 'not-a-uuid' },
    {
      items: Array.from({ length: 21 }, (_, i) => ({
        ...draft,
        id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
      })),
      nextBeforeId: null,
    },
  ])('rejects cross-shop, duplicate, oversized or malformed pages: %j', async (page) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(page))
    await expect(
      new ApiClient('editor', 'test', fetcher).knowledgeArticles('100'),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('allows a saved published article to differ from its unchanged online snapshot', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(saved))
    const result = await new ApiClient('editor', 'test', fetcher).knowledgeArticle('100', saved.id)
    expect(result.content).not.toBe(result.publishedSnapshot?.content)
    expect(result.publishedRevision).toBe(1)
  })
  it.each([
    { ...published, publishedSnapshot: null },
    { ...published, publishedRevision: 2 },
    { ...draft, publishedSnapshot: published.publishedSnapshot },
    { ...draft, version: -1 },
    { ...draft, id: '00000000-0000-4000-8000-000000000999' },
  ])('rejects inconsistent article versions, snapshots or requested IDs', async (article) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(article))
    await expect(
      new ApiClient('editor', 'test', fetcher).knowledgeArticle('100', draft.id),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('reads bounded audit pages with an encoded opaque cursor', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ items: [audit], nextBeforeId: 'next:cursor/2' }))
    const result = await new ApiClient('editor', 'test', fetcher).knowledgeAudit(
      '100',
      draft.id,
      'cursor/a+b',
    )
    expect(fetcher.mock.calls[0]?.[0]).toBe(`${base}/${draft.id}/audit?beforeId=cursor%2Fa%2Bb`)
    expect(result.items).toEqual([audit])
  })
  it.each([
    { items: [audit, audit], nextBeforeId: null },
    { items: [{ ...audit, action: 'EXECUTED' }], nextBeforeId: null },
    { items: [audit], nextBeforeId: '\n' },
  ])('rejects malformed audit responses', async (page) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(page))
    await expect(
      new ApiClient('editor', 'test', fetcher).knowledgeAudit('100', draft.id),
    ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('creates a draft with only the known input fields and fresh CSRF', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(draft))
    const result = await new ApiClient('editor', 'test', fetcher).createKnowledgeArticle('100', {
      ...input,
      title: ` ${input.title} `,
    })
    expect(result).toEqual(draft)
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/v1/security/csrf')
    expect(fetcher.mock.calls[1]?.[0]).toBe(base)
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': csrf.token },
    })
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body as string)).toEqual(input)
  })
  it('saves the current editor version with PUT and publishes/offlines only that explicit version', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(saved))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(republished))
      .mockResolvedValueOnce(json(csrf))
      .mockResolvedValueOnce(json(offline))
    const api = new ApiClient('editor', 'test', fetcher)
    await api.saveKnowledgeArticle('100', draft.id, 1, input)
    await api.publishKnowledgeArticle('100', draft.id, 2)
    await api.offlineKnowledgeArticle('100', draft.id, 3)
    expect(fetcher.mock.calls[1]?.[0]).toBe(`${base}/${draft.id}`)
    expect(fetcher.mock.calls[1]?.[1]?.method).toBe('PUT')
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body as string)).toEqual({
      expectedVersion: 1,
      ...input,
    })
    expect(fetcher.mock.calls[3]?.[0]).toBe(`${base}/${draft.id}/publish`)
    expect(JSON.parse(fetcher.mock.calls[3]?.[1]?.body as string)).toEqual({ expectedVersion: 2 })
    expect(fetcher.mock.calls[5]?.[0]).toBe(`${base}/${draft.id}/offline`)
    expect(JSON.parse(fetcher.mock.calls[5]?.[1]?.body as string)).toEqual({ expectedVersion: 3 })
  })
  it.each([-1, 1.5, Number.MAX_SAFE_INTEGER + 1])(
    'rejects invalid expected versions before CSRF or business requests: %s',
    async (version) => {
      const fetcher = vi.fn<typeof fetch>()
      await expect(
        new ApiClient('editor', 'test', fetcher).saveKnowledgeArticle(
          '100',
          draft.id,
          version,
          input,
        ),
      ).rejects.toThrow()
      expect(fetcher).not.toHaveBeenCalled()
    },
  )
  it.each([
    { ...input, title: '' },
    { ...input, title: 'x'.repeat(121) },
    { ...input, content: 'x'.repeat(4001) },
    { ...input, keywords: [] },
    { ...input, keywords: Array(13).fill('a') },
    { ...input, keywords: ['x'.repeat(41)] },
  ])('rejects invalid input limits without a network write', async (value) => {
    expect(validKnowledgeInput(value)).toBe(false)
    const fetcher = vi.fn<typeof fetch>()
    await expect(
      new ApiClient('editor', 'test', fetcher).createKnowledgeArticle('100', value),
    ).rejects.toThrow()
    expect(fetcher).not.toHaveBeenCalled()
  })
  it.each(['save', 'publish', 'offline'] as const)(
    'never accepts a cross-shop %s mutation response',
    async (operation) => {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json({ ...published, shopId: 101 }))
      const api = new ApiClient('editor', 'test', fetcher)
      await expect(
        operation === 'save'
          ? api.saveKnowledgeArticle('100', draft.id, 0, input)
          : operation === 'publish'
            ? api.publishKnowledgeArticle('100', draft.id, 0)
            : api.offlineKnowledgeArticle('100', draft.id, 1),
      ).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
      expect(fetcher).toHaveBeenCalledTimes(2)
    },
  )
  it.each(['KNOWLEDGE_CONFLICT', 'KNOWLEDGE_LIMIT_REACHED'])(
    'reports bounded conflicts without replaying the write: %s',
    async (code) => {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(json(csrf))
        .mockResolvedValueOnce(json({ code, message: 'internal detail not exposed' }, 409))
      await expect(
        new ApiClient('editor', 'test', fetcher).publishKnowledgeArticle('100', draft.id, 0),
      ).rejects.toMatchObject({ status: 409, code })
      expect(fetcher).toHaveBeenCalledTimes(2)
    },
  )
  it('never retries a lost create result', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(csrf))
      .mockRejectedValueOnce(new TypeError('lost reply'))
    await expect(
      new ApiClient('editor', 'test', fetcher).createKnowledgeArticle('100', input),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('cancels an already aborted write without obtaining CSRF or posting', async () => {
    const fetcher = vi.fn<typeof fetch>()
    const controller = new AbortController()
    controller.abort()
    await expect(
      new ApiClient('editor', 'test', fetcher).publishKnowledgeArticle(
        '100',
        draft.id,
        0,
        controller.signal,
      ),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(fetcher).not.toHaveBeenCalled()
  })
})
