import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from './api/client'
import { useMerchantKnowledge } from './composables/useMerchantKnowledge'
import KnowledgeIndexPanel from './components/KnowledgeIndexPanel.vue'
import type { KnowledgeIndex } from './types'

const partial: KnowledgeIndex = {
  status: 'PARTIAL',
  expectedChunks: 4,
  indexedChunks: 2,
  modelFingerprint: 'local-model-v1',
}
const ready: KnowledgeIndex = { ...partial, status: 'READY', indexedChunks: 4 }
const json = (value: unknown) =>
  new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })

describe('knowledge index preparation', () => {
  it('reads status without generating vectors and only explicitly posts a CSRF protected warmup with no body', async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(json(partial))
      .mockResolvedValueOnce(
        json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fresh-token' }),
      )
      .mockResolvedValueOnce(json(ready))
    const api = new ApiClient('editor', 'test-only', fetch)
    await expect(api.knowledgeIndex('100')).resolves.toEqual(partial)
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0]![1]!.method).toBe('GET')
    await expect(api.warmKnowledgeIndex('100')).resolves.toEqual(ready)
    const [path, request] = fetch.mock.calls[2]!
    expect(path).toBe('/api/v1/merchant/shops/100/knowledge/index/warmup')
    expect(request!.method).toBe('POST')
    expect(request!.body).toBeUndefined()
    expect(new Headers(request!.headers).get('X-CSRF-TOKEN')).toBe('fresh-token')
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it.each([
    { ...partial, status: 'SECRET' },
    { ...partial, expectedChunks: -1 },
    { ...partial, expectedChunks: 1.2 },
    { ...partial, indexedChunks: 5 },
    { ...partial, indexedChunks: '2' },
    { ...partial, modelFingerprint: '' },
  ])('rejects malformed state rather than displaying misleading progress: %j', async (value) => {
    const api = new ApiClient('editor', 'test-only', vi.fn().mockResolvedValue(json(value)))
    await expect(api.knowledgeIndex('100')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('requires an explicit preparation action and drops a late result after the shop changes', async () => {
    let complete!: (value: KnowledgeIndex) => void
    const api = {
      knowledgeIndex: vi.fn().mockResolvedValue(partial),
      warmKnowledgeIndex: vi.fn().mockReturnValue(
        new Promise<KnowledgeIndex>((resolve) => {
          complete = resolve
        }),
      ),
    }
    const shop = ref('100')
    const submitting = ref(false)
    let controller = new AbortController()
    const state = useMerchantKnowledge({
      client: () => api as unknown as ApiClient,
      shop: () => shop.value,
      signal: () => controller.signal,
      allowed: () => true,
      submitting,
      report: vi.fn(),
    })
    await state.loadKnowledgeIndex()
    expect(state.knowledgeIndex.value).toEqual(partial)
    expect(api.warmKnowledgeIndex).not.toHaveBeenCalled()
    const pending = state.warmKnowledgeIndex()
    expect(submitting.value).toBe(true)
    await state.warmKnowledgeIndex()
    expect(api.warmKnowledgeIndex).toHaveBeenCalledOnce()
    controller.abort()
    controller = new AbortController()
    shop.value = '101'
    state.resetKnowledge()
    complete(ready)
    await pending
    expect(state.knowledgeIndex.value).toBeNull()
    expect(state.knowledgeIndexPreparing.value).toBe(false)
    expect(submitting.value).toBe(false)
  })

  it('clears knowledge data through the permission handler when access is revoked', async () => {
    const api = { knowledgeIndex: vi.fn().mockRejectedValue(new ApiError('权限已变化', 403)) }
    const report = vi.fn()
    const state = useMerchantKnowledge({
      client: () => api as unknown as ApiClient,
      shop: () => '100',
      signal: () => new AbortController().signal,
      allowed: () => true,
      submitting: ref(false),
      report,
    })
    state.knowledgeIndex.value = ready
    await state.loadKnowledgeIndex()
    expect(report).toHaveBeenCalledOnce()
    expect(state.knowledgeIndex.value).toBeNull()
    expect(state.knowledgeArticles.value).toEqual([])
  })

  it('does not automatically retry a busy or unavailable preparation', async () => {
    const api = { warmKnowledgeIndex: vi.fn().mockResolvedValue({ ...partial, status: 'BUSY' }) }
    const state = useMerchantKnowledge({
      client: () => api as unknown as ApiClient,
      shop: () => '100',
      signal: () => new AbortController().signal,
      allowed: () => true,
      submitting: ref(false),
      report: vi.fn(),
    })
    await state.warmKnowledgeIndex()
    expect(state.knowledgeIndex.value?.status).toBe('BUSY')
    expect(api.warmKnowledgeIndex).toHaveBeenCalledOnce()
  })

  it('shows concise scoped progress, requires a click, and disables repeat or unnecessary preparations', async () => {
    const panel = mount(KnowledgeIndexPanel, {
      props: { index: partial, loading: false, preparing: false, blocked: false, error: null },
    })
    expect(panel.text()).toContain('已准备 2 / 4 段资料')
    expect(panel.emitted('prepare')).toBeUndefined()
    await panel.get('[data-testid="knowledge-index-prepare"]').trigger('click')
    expect(panel.emitted('prepare')).toHaveLength(1)
    await panel.setProps({ preparing: true })
    expect(
      panel.get('[data-testid="knowledge-index-prepare"]').attributes('disabled'),
    ).toBeDefined()
    await panel.setProps({ preparing: false, index: ready })
    expect(
      panel.get('[data-testid="knowledge-index-prepare"]').attributes('disabled'),
    ).toBeDefined()
    expect(panel.text()).not.toContain('local-model-v1')
    panel.unmount()
  })
})
