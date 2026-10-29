import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { ApiClient } from './api/client'
import KnowledgeImportDialog from './components/KnowledgeImportDialog.vue'
import { useMerchantKnowledge } from './composables/useMerchantKnowledge'
import { knowledgeDraft } from './test/knowledgeFixtures'

enableAutoUnmount(afterEach)
afterEach(() => vi.restoreAllMocks())
const json = (data: unknown) =>
  new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
const file = () => new File(['使用软布整理'], '说明.md', { type: 'text/markdown' })

describe('knowledge file import', () => {
  it('uses multipart with CSRF and lets the browser choose the boundary', async () => {
    const upload = file()
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(
        json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-token' }),
      )
      .mockResolvedValueOnce(
        json({ title: '说明', content: '使用软布整理', characterCount: 6, byteCount: upload.size }),
      )
    const result = await new ApiClient('editor', 'test-only', fetch).previewKnowledgeImport(
      '100',
      upload,
    )
    expect(result.content).toBe('使用软布整理')
    const init = fetch.mock.calls[1]![1]!
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(FormData)
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('test-token')
    expect(new Headers(init.headers).has('Content-Type')).toBe(false)
  })
  it('rejects a corrupted preview without silently accepting truncated content', async () => {
    const upload = file()
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(
        json({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-token' }),
      )
      .mockResolvedValueOnce(
        json({
          title: '说明',
          content: '使用软布整理',
          characterCount: 99,
          byteCount: upload.size,
        }),
      )
    await expect(
      new ApiClient('editor', 'test-only', fetch).previewKnowledgeImport('100', upload),
    ).rejects.toThrow('预览格式')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('shows markup as text and does not confirm on choosing a file or cancelling', async () => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(),
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: vi.fn(),
    })
    const markup = '<script>alert(1)</script><img src=x onerror=alert(1)>'
    const wrapper = mount(KnowledgeImportDialog, {
      props: {
        preview: {
          title: '说明',
          content: markup,
          characterCount: markup.length,
          byteCount: markup.length,
        },
        title: '说明',
        keywords: '杯子',
        loading: false,
        submitting: false,
        error: null,
        needsVerification: false,
      },
    })
    expect(wrapper.findAll('script,img')).toHaveLength(0)
    expect(wrapper.get('pre').text()).toBe(markup)
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('confirm')).toBeUndefined()
  })
  it('only imports after confirmation and never publishes the resulting draft automatically', async () => {
    const api = {
      previewKnowledgeImport: vi.fn().mockResolvedValue({
        title: '说明',
        content: '使用软布整理',
        characterCount: 6,
        byteCount: file().size,
      }),
      importKnowledgeFile: vi
        .fn()
        .mockResolvedValue({ articleId: knowledgeDraft.id, reused: true }),
      knowledgeArticle: vi.fn().mockResolvedValue(knowledgeDraft),
      knowledgeAudit: vi.fn().mockResolvedValue({ items: [], nextBeforeId: null }),
      publishKnowledgeArticle: vi.fn(),
    }
    const state = useMerchantKnowledge({
      client: () => api as unknown as ApiClient,
      shop: () => '100',
      signal: () => new AbortController().signal,
      allowed: () => true,
      submitting: ref(false),
      report: vi.fn(),
    })
    state.openKnowledgeImport()
    await state.previewKnowledgeFile(file())
    expect(api.importKnowledgeFile).not.toHaveBeenCalled()
    state.closeKnowledgeImport()
    expect(api.importKnowledgeFile).not.toHaveBeenCalled()
    state.openKnowledgeImport()
    await state.previewKnowledgeFile(file())
    state.knowledgeImportKeywords.value = '杯子'
    await state.confirmKnowledgeImport()
    expect(api.importKnowledgeFile).toHaveBeenCalledTimes(1)
    expect(api.publishKnowledgeArticle).not.toHaveBeenCalled()
    expect(state.knowledgeSelected.value?.id).toBe(knowledgeDraft.id)
    expect(state.knowledgeNotice.value).toContain('未覆盖')
    expect(state.knowledgeImportOpen.value).toBe(false)
  })
})
