import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from '../api/client'
import { useMerchantKnowledge } from './useMerchantKnowledge'
import {
  knowledgeDraft as draft,
  knowledgePublished as published,
  knowledgeSaved as saved,
  knowledgeRepublished as republished,
  knowledgeOffline as offline,
  knowledgeAudit as audit,
} from '../test/knowledgeFixtures'
import type { KnowledgeArticle, KnowledgePage, KnowledgeAuditPage } from '../types'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function setup() {
  const api = {
    knowledgeArticles: vi.fn().mockResolvedValue({ items: [draft], nextBeforeId: null }),
    knowledgeArticle: vi.fn().mockResolvedValue(draft),
    knowledgeAudit: vi.fn().mockResolvedValue({ items: [audit], nextBeforeId: null }),
    createKnowledgeArticle: vi.fn().mockResolvedValue(draft),
    saveKnowledgeArticle: vi.fn().mockResolvedValue(saved),
    publishKnowledgeArticle: vi.fn().mockResolvedValue(published),
    offlineKnowledgeArticle: vi.fn().mockResolvedValue(offline),
  }
  const submitting = ref(false)
  const report = vi.fn()
  const controller = new AbortController()
  const state = useMerchantKnowledge({
    client: () => api as unknown as ApiClient,
    shop: () => '100',
    signal: () => controller.signal,
    allowed: () => true,
    submitting,
    report,
  })
  function fill() {
    state.knowledgeTitle.value = draft.title
    state.knowledgeKeywords.value = draft.keywords.join(', ')
    state.knowledgeContent.value = draft.content
  }
  return { state, api, controller, submitting, report, fill }
}

describe('useMerchantKnowledge', () => {
  it('does not create anything when starting or cancelling a local draft', () => {
    const { state, api, fill } = setup()
    state.newKnowledge()
    fill()
    expect(state.knowledgeDirty.value).toBe(true)
    state.cancelKnowledge()
    expect(state.knowledgeEditing.value).toBe(false)
    expect(state.knowledgeContent.value).toBe('')
    expect(api.createKnowledgeArticle).not.toHaveBeenCalled()
    expect(api.publishKnowledgeArticle).not.toHaveBeenCalled()
  })
  it('saves an explicitly submitted normalized draft without publishing it', async () => {
    const { state, api } = setup()
    state.newKnowledge()
    state.knowledgeTitle.value = ` ${draft.title} `
    state.knowledgeKeywords.value = '配送，送达时间'
    state.knowledgeContent.value = ` ${draft.content} `
    await state.saveKnowledge()
    expect(api.createKnowledgeArticle).toHaveBeenCalledWith(
      '100',
      { title: draft.title, keywords: draft.keywords, content: draft.content },
      expect.any(AbortSignal),
    )
    expect(state.knowledgeSelected.value).toEqual(draft)
    expect(state.knowledgeDirty.value).toBe(false)
    expect(state.knowledgeArticles.value).toEqual([draft])
    expect(api.publishKnowledgeArticle).not.toHaveBeenCalled()
    expect(api.knowledgeAudit).toHaveBeenCalledWith('100', draft.id, null, expect.any(AbortSignal))
  })
  it('rejects invalid local field lengths before opening a write transaction', async () => {
    const { state, api, fill, submitting } = setup()
    state.newKnowledge()
    fill()
    state.knowledgeContent.value = 'x'.repeat(4001)
    await state.saveKnowledge()
    expect(api.createKnowledgeArticle).not.toHaveBeenCalled()
    expect(submitting.value).toBe(false)
    expect(state.knowledgeError.value).toContain('4000')
    expect(state.knowledgeNeedsVerification.value).toBe(false)
  })
  it('preserves the online snapshot while saving a new editing version, then explicitly republishes', async () => {
    const { state, api } = setup()
    api.knowledgeArticle.mockResolvedValueOnce(published)
    await state.openKnowledge(published.id)
    state.knowledgeContent.value = saved.content
    await state.publishKnowledge()
    expect(api.publishKnowledgeArticle).not.toHaveBeenCalled()
    await state.saveKnowledge()
    expect(api.saveKnowledgeArticle).toHaveBeenCalledWith(
      '100',
      published.id,
      1,
      expect.objectContaining({ content: saved.content }),
      expect.any(AbortSignal),
    )
    expect(state.knowledgeHasUnpublishedChanges.value).toBe(true)
    expect(state.knowledgeSelected.value?.publishedSnapshot?.content).toBe(published.content)
    expect(state.knowledgeNotice.value).toContain('线上继续使用原发布快照')
    api.publishKnowledgeArticle.mockResolvedValueOnce(republished)
    await state.publishKnowledge()
    expect(api.publishKnowledgeArticle).toHaveBeenCalledWith(
      '100',
      published.id,
      2,
      expect.any(AbortSignal),
    )
    expect(state.knowledgeHasUnpublishedChanges.value).toBe(false)
    expect(state.knowledgeSelected.value?.publishedSnapshot?.content).toBe(saved.content)
    await state.offlineKnowledge()
    expect(api.offlineKnowledgeArticle).toHaveBeenCalledWith(
      '100',
      published.id,
      3,
      expect.any(AbortSignal),
    )
    expect(state.knowledgeSelected.value?.publishedSnapshot).toBeNull()
  })
  it('locks cancellation and duplicate writes while preserving the operation original scope', async () => {
    const { state, api, fill, submitting } = setup()
    state.newKnowledge()
    fill()
    const pending = deferred<KnowledgeArticle>()
    api.createKnowledgeArticle.mockReturnValueOnce(pending.promise)
    const request = state.saveKnowledge()
    expect(submitting.value).toBe(true)
    state.cancelKnowledge()
    state.newKnowledge()
    await state.saveKnowledge()
    await state.publishKnowledge()
    expect(state.knowledgeTitle.value).toBe(draft.title)
    expect(api.createKnowledgeArticle).toHaveBeenCalledTimes(1)
    expect(api.publishKnowledgeArticle).not.toHaveBeenCalled()
    pending.resolve(draft)
    await request
    expect(submitting.value).toBe(false)
  })
  it('does not replay a lost create, and reads the matching listed article to resolve uncertainty', async () => {
    const { state, api, fill } = setup()
    state.newKnowledge()
    fill()
    api.createKnowledgeArticle.mockRejectedValueOnce(
      new ApiError('网络结果未知', 0, 'NETWORK_ERROR'),
    )
    await state.saveKnowledge()
    await state.saveKnowledge()
    expect(api.createKnowledgeArticle).toHaveBeenCalledTimes(1)
    expect(state.knowledgeNeedsVerification.value).toBe(true)
    expect(state.knowledgeContent.value).toBe(draft.content)
    await state.reloadKnowledge()
    expect(state.knowledgeNeedsVerification.value).toBe(true)
    expect(state.knowledgeNotice.value).toContain('不要重复创建')
    await state.openKnowledge(draft.id)
    expect(state.knowledgeNeedsVerification.value).toBe(false)
    expect(state.knowledgeSelected.value?.id).toBe(draft.id)
    expect(api.createKnowledgeArticle).toHaveBeenCalledTimes(1)
  })
  it('retains local input after 409, reloads the latest version without automatic overwrites or saves', async () => {
    const { state, api } = setup()
    await state.openKnowledge(draft.id)
    state.knowledgeContent.value = '本地未提交的修改'
    api.saveKnowledgeArticle.mockRejectedValueOnce(
      new ApiError('版本冲突', 409, 'KNOWLEDGE_CONFLICT'),
    )
    await state.saveKnowledge()
    expect(state.knowledgeNeedsVerification.value).toBe(true)
    expect(state.knowledgeContent.value).toBe('本地未提交的修改')
    await state.saveKnowledge()
    expect(api.saveKnowledgeArticle).toHaveBeenCalledTimes(1)
    api.knowledgeArticle.mockResolvedValueOnce({
      ...draft,
      version: 1,
      content: '其他员工的新内容',
    })
    await state.reloadKnowledge()
    expect(state.knowledgeSelected.value?.version).toBe(1)
    expect(state.knowledgeContent.value).toBe('本地未提交的修改')
    expect(state.knowledgeDirty.value).toBe(true)
    expect(state.knowledgeNeedsVerification.value).toBe(false)
    expect(api.saveKnowledgeArticle).toHaveBeenCalledTimes(1)
    await state.saveKnowledge()
    expect(api.saveKnowledgeArticle.mock.lastCall?.[2]).toBe(1)
  })
  it('does not discard dirty content just because the operator opens another document', async () => {
    const { state, api, fill } = setup()
    state.newKnowledge()
    fill()
    await state.openKnowledge(draft.id)
    expect(api.knowledgeArticle).not.toHaveBeenCalled()
    expect(state.knowledgeError.value).toContain('保存或取消')
  })
  it('cancels a detail read without reopening the editor when its late response arrives', async () => {
    const { state, api } = setup()
    const pending = deferred<KnowledgeArticle>()
    api.knowledgeArticle.mockReturnValueOnce(pending.promise)
    const request = state.openKnowledge(draft.id)
    const signal = api.knowledgeArticle.mock.lastCall?.[2] as AbortSignal
    state.cancelKnowledge()
    expect(signal.aborted).toBe(true)
    pending.resolve(draft)
    await request
    expect(state.knowledgeEditing.value).toBe(false)
    expect(state.knowledgeSelected.value).toBeNull()
    expect(api.knowledgeAudit).not.toHaveBeenCalled()
  })
  it.each(['list', 'detail', 'audit'] as const)(
    'ignores late %s data after the host switches shop/account/module',
    async (kind) => {
      const { state, api, controller } = setup()
      const pending = deferred<KnowledgePage | KnowledgeArticle | KnowledgeAuditPage>()
      if (kind === 'list') api.knowledgeArticles.mockReturnValueOnce(pending.promise)
      else if (kind === 'detail') api.knowledgeArticle.mockReturnValueOnce(pending.promise)
      else {
        await state.openKnowledge(draft.id)
        api.knowledgeAudit.mockReturnValueOnce(pending.promise)
      }
      const request =
        kind === 'list'
          ? state.loadKnowledge()
          : kind === 'detail'
            ? state.openKnowledge(draft.id)
            : state.loadKnowledgeAudit()
      controller.abort()
      state.resetKnowledge()
      pending.resolve(
        kind === 'detail'
          ? draft
          : ({ items: [kind === 'list' ? draft : audit], nextBeforeId: null } as
              KnowledgePage | KnowledgeAuditPage),
      )
      await request
      expect(state.knowledgeArticles.value).toEqual([])
      expect(state.knowledgeSelected.value).toBeNull()
      expect(state.knowledgeAudit.value).toEqual([])
      expect(state.knowledgeLoading.value).toBe(false)
    },
  )
  it.each([401, 403])(
    'clears formerly authorized content and reports %s before aborting local reads',
    async (status) => {
      const { state, api, report } = setup()
      await state.openKnowledge(draft.id)
      api.knowledgeArticle.mockRejectedValueOnce(new ApiError('权限已失效', status))
      await state.reloadKnowledge()
      expect(report).toHaveBeenCalledTimes(1)
      expect((report.mock.calls[0]?.[1] as AbortSignal).aborted).toBe(true)
      expect(state.knowledgeSelected.value).toBeNull()
      expect(state.knowledgeContent.value).toBe('')
      expect(state.knowledgeArticles.value).toEqual([])
    },
  )
  it('pages and deduplicates list and audit data without automatically invoking any writes', async () => {
    const { state, api } = setup()
    const second = { ...draft, id: '00000000-0000-4000-8000-000000000102' }
    api.knowledgeArticles
      .mockResolvedValueOnce({ items: [draft], nextBeforeId: draft.id })
      .mockResolvedValueOnce({ items: [draft, second], nextBeforeId: null })
    await state.loadKnowledge()
    await state.loadKnowledge(true)
    expect(state.knowledgeArticles.value).toHaveLength(2)
    expect(api.knowledgeArticles.mock.lastCall?.[1]).toBe(draft.id)
    api.knowledgeAudit
      .mockResolvedValueOnce({ items: [audit], nextBeforeId: '1' })
      .mockResolvedValueOnce({
        items: [{ ...audit, id: '2', action: 'SAVED' }],
        nextBeforeId: null,
      })
    await state.openKnowledge(draft.id)
    await state.loadKnowledgeAudit(true)
    expect(state.knowledgeAudit.value).toHaveLength(2)
    expect(api.knowledgeAudit.mock.lastCall?.[2]).toBe('1')
    expect(api.createKnowledgeArticle).not.toHaveBeenCalled()
  })
  it('rejects a nonadvancing cursor rather than entering a paging loop', async () => {
    const { state, api } = setup()
    api.knowledgeArticles.mockResolvedValue({ items: [draft], nextBeforeId: draft.id })
    await state.loadKnowledge()
    await state.loadKnowledge(true)
    expect(state.knowledgeError.value).toContain('分页位置未前进')
    expect(state.knowledgeArticles.value).toHaveLength(1)
  })
})
