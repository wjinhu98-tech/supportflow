import { computed, ref, type Ref } from 'vue'
import { ApiClient, ApiError } from '../api/client'
import { validKnowledgeInput, validKnowledgeFile } from '../api/knowledge'
import type {
  KnowledgeArticle,
  KnowledgeAudit,
  KnowledgeInput,
  KnowledgeImportPreview,
  KnowledgeIndex,
} from '../types'

interface KnowledgeContext {
  client: () => ApiClient | null
  shop: () => string | null
  signal: () => AbortSignal
  allowed: () => boolean
  submitting: Ref<boolean>
  report: (failure: unknown, signal: AbortSignal, fallback: string) => void
}

/** Local editing is separate from the published snapshot and from the shared business-write lock. */
export function useMerchantKnowledge(context: KnowledgeContext) {
  const knowledgeArticles = ref<KnowledgeArticle[]>([])
  const knowledgeSelected = ref<KnowledgeArticle | null>(null)
  const knowledgeEditing = ref(false)
  const knowledgeTitle = ref('')
  const knowledgeKeywords = ref('')
  const knowledgeContent = ref('')
  const knowledgeNextBeforeId = ref<string | null>(null)
  const knowledgeAudit = ref<KnowledgeAudit[]>([])
  const knowledgeNextAuditBeforeId = ref<string | null>(null)
  const knowledgeLoading = ref(false)
  const knowledgeDetailLoading = ref(false)
  const knowledgeAuditLoading = ref(false)
  const knowledgeError = ref<string | null>(null)
  const knowledgeNotice = ref<string | null>(null)
  const knowledgeNeedsVerification = ref(false)
  const knowledgeImportOpen = ref(false)
  const knowledgeImportFile = ref<File | null>(null)
  const knowledgeImportPreview = ref<KnowledgeImportPreview | null>(null)
  const knowledgeImportTitle = ref('')
  const knowledgeImportKeywords = ref('')
  const knowledgeImportLoading = ref(false)
  const knowledgeImportError = ref<string | null>(null)
  const knowledgeImportNeedsVerification = ref(false)
  const knowledgeIndex = ref<KnowledgeIndex | null>(null)
  const knowledgeIndexLoading = ref(false)
  const knowledgeIndexPreparing = ref(false)
  const knowledgeIndexError = ref<string | null>(null)
  let indexRequest = new AbortController()
  let importRequest = new AbortController()
  let importVersion = 0
  let listRequest = new AbortController()
  let detailRequest = new AbortController()
  let auditRequest = new AbortController()

  function input(): KnowledgeInput {
    return {
      title: knowledgeTitle.value.trim(),
      keywords: knowledgeKeywords.value
        .split(/[,，\n]/u)
        .map((item) => item.trim())
        .filter(Boolean),
      content: knowledgeContent.value.trim(),
    }
  }
  const knowledgeDirty = computed(() => {
    if (!knowledgeEditing.value) return false
    const value = input()
    const original = knowledgeSelected.value
    return original === null
      ? Boolean(value.title || value.content || value.keywords.length)
      : value.title !== original.title ||
          value.content !== original.content ||
          JSON.stringify(value.keywords) !== JSON.stringify(original.keywords)
  })
  const knowledgeHasUnpublishedChanges = computed(() => {
    const article = knowledgeSelected.value
    const snapshot = article?.publishedSnapshot
    return Boolean(
      article &&
      snapshot &&
      (article.title !== snapshot.title ||
        article.content !== snapshot.content ||
        JSON.stringify(article.keywords) !== JSON.stringify(snapshot.keywords)),
    )
  })

  function fill(article: KnowledgeArticle | null) {
    knowledgeTitle.value = article?.title ?? ''
    knowledgeKeywords.value = article?.keywords.join(', ') ?? ''
    knowledgeContent.value = article?.content ?? ''
  }

  function resetEditor() {
    detailRequest.abort()
    auditRequest.abort()
    detailRequest = new AbortController()
    auditRequest = new AbortController()
    knowledgeSelected.value = null
    knowledgeEditing.value = false
    fill(null)
    knowledgeAudit.value = []
    knowledgeNextAuditBeforeId.value = null
    knowledgeDetailLoading.value = false
    knowledgeAuditLoading.value = false
    knowledgeNeedsVerification.value = false
    knowledgeError.value = null
    knowledgeNotice.value = null
  }

  function resetKnowledge() {
    indexRequest.abort()
    indexRequest = new AbortController()
    knowledgeIndex.value = null
    knowledgeIndexLoading.value = false
    knowledgeIndexPreparing.value = false
    knowledgeIndexError.value = null
    listRequest.abort()
    listRequest = new AbortController()
    resetEditor()
    knowledgeArticles.value = []
    knowledgeNextBeforeId.value = null
    knowledgeLoading.value = false
    resetKnowledgeImport()
  }

  function resetKnowledgeImport() {
    importRequest.abort()
    importRequest = new AbortController()
    importVersion++
    knowledgeImportOpen.value = false
    knowledgeImportFile.value = null
    knowledgeImportPreview.value = null
    knowledgeImportTitle.value = ''
    knowledgeImportKeywords.value = ''
    knowledgeImportLoading.value = false
    knowledgeImportError.value = null
    knowledgeImportNeedsVerification.value = false
  }

  function openKnowledgeImport() {
    if (
      !runtime() ||
      context.submitting.value ||
      knowledgeDirty.value ||
      knowledgeNeedsVerification.value
    )
      return
    resetKnowledgeImport()
    knowledgeImportOpen.value = true
  }

  function closeKnowledgeImport() {
    if (!context.submitting.value) resetKnowledgeImport()
  }

  async function previewKnowledgeFile(file: File | null) {
    const current = runtime()
    if (!current || !knowledgeImportOpen.value || context.submitting.value) return
    importRequest.abort()
    importRequest = new AbortController()
    const version = ++importVersion
    const signal = AbortSignal.any([current.baseSignal, importRequest.signal])
    knowledgeImportFile.value = null
    knowledgeImportPreview.value = null
    knowledgeImportError.value = null
    knowledgeImportNeedsVerification.value = false
    knowledgeImportLoading.value = false
    knowledgeImportTitle.value = ''
    knowledgeImportKeywords.value = ''
    if (!file || !validKnowledgeFile(file)) {
      knowledgeImportError.value = '请选择非空的 TXT 或 Markdown 文件，大小不超过64 KiB。'
      return
    }
    knowledgeImportLoading.value = true
    try {
      const preview = await current.client.previewKnowledgeImport(current.shop, file, signal)
      if (signal.aborted || version !== importVersion) return
      knowledgeImportFile.value = file
      knowledgeImportPreview.value = preview
      knowledgeImportTitle.value = preview.title
    } catch (failure) {
      if (!signal.aborted && version === importVersion) {
        knowledgeImportError.value =
          failure instanceof ApiError
            ? failure.message
            : '文件预览失败，请重新选择文件；尚未保存草稿。'
        if (failure instanceof ApiError && [401, 403].includes(failure.status))
          report(failure, signal, '权限已变化，请重新连接。')
      }
    } finally {
      if (!signal.aborted && version === importVersion) knowledgeImportLoading.value = false
    }
  }

  async function confirmKnowledgeImport() {
    const current = runtime()
    const file = knowledgeImportFile.value
    const preview = knowledgeImportPreview.value
    if (
      !current ||
      !file ||
      !preview ||
      !knowledgeImportOpen.value ||
      knowledgeImportLoading.value ||
      knowledgeImportNeedsVerification.value ||
      context.submitting.value
    )
      return
    const value = {
      title: knowledgeImportTitle.value.trim(),
      content: preview.content,
      keywords: knowledgeImportKeywords.value
        .split(/[,，\n]/u)
        .map((item) => item.trim())
        .filter(Boolean),
    }
    if (!validKnowledgeInput(value)) {
      knowledgeImportError.value =
        '请核对标题（1–120字）、1–12个关键词（每个1–40字）和正文（1–4000字）。'
      return
    }
    const signal = AbortSignal.any([current.baseSignal, importRequest.signal])
    const version = importVersion
    context.submitting.value = true
    knowledgeImportError.value = null
    let confirmed = false
    try {
      const result = await current.client.importKnowledgeFile(
        current.shop,
        file,
        value.title,
        value.keywords,
        signal,
      )
      if (signal.aborted || version !== importVersion) return
      confirmed = true
      const article = await current.client.knowledgeArticle(current.shop, result.articleId, signal)
      if (signal.aborted || version !== importVersion) return
      resetEditor()
      knowledgeSelected.value = article
      knowledgeEditing.value = true
      fill(article)
      knowledgeArticles.value = knowledgeArticles.value.some((item) => item.id === article.id)
        ? knowledgeArticles.value.map((item) => (item.id === article.id ? article : item))
        : [article, ...knowledgeArticles.value]
      knowledgeNotice.value = result.reused
        ? '本店已导入过相同正文，已打开原文档；现有草稿和发布版本均未覆盖。'
        : '文件已导入为草稿，尚未发布。请核对后再明确发布到店铺。'
      context.submitting.value = false
      resetKnowledgeImport()
      await loadKnowledgeAudit()
    } catch (failure) {
      if (!signal.aborted && version === importVersion) {
        knowledgeImportNeedsVerification.value =
          confirmed || !(failure instanceof ApiError && [400, 403, 413].includes(failure.status))
        knowledgeImportError.value = confirmed
          ? '导入已保存，但读取文档失败。请取消导入并刷新文档列表核实，不要重复创建。'
          : failure instanceof ApiError && [400, 403, 409, 413].includes(failure.status)
            ? failure.message
            : '导入结果暂时无法确认，请取消导入并刷新文档列表核实，不会自动重复提交。'
        if (failure instanceof ApiError && [401, 403].includes(failure.status))
          report(failure, signal, '权限已变化，请重新连接。')
      }
    } finally {
      if (!signal.aborted && version === importVersion) context.submitting.value = false
    }
  }

  function runtime() {
    const client = context.client()
    const shop = context.shop()
    return client && shop && context.allowed()
      ? { client, shop, baseSignal: context.signal() }
      : null
  }

  async function loadKnowledgeIndex() {
    const current = runtime()
    if (!current || context.submitting.value || knowledgeIndexPreparing.value) return
    indexRequest.abort()
    indexRequest = new AbortController()
    const signal = AbortSignal.any([current.baseSignal, indexRequest.signal])
    knowledgeIndexLoading.value = true
    knowledgeIndexError.value = null
    try {
      const result = await current.client.knowledgeIndex(current.shop, signal)
      if (!signal.aborted) knowledgeIndex.value = result
    } catch (failure) {
      if (!signal.aborted) {
        knowledgeIndexError.value = '读取检索准备状态失败，请稍后刷新。'
        if (failure instanceof ApiError && [401, 403].includes(failure.status))
          report(failure, signal, '权限已变化，请重新登录。')
      }
    } finally {
      if (!signal.aborted) knowledgeIndexLoading.value = false
    }
  }

  async function warmKnowledgeIndex() {
    const current = runtime()
    if (
      !current ||
      context.submitting.value ||
      knowledgeIndexPreparing.value ||
      knowledgeDirty.value ||
      knowledgeImportOpen.value
    )
      return
    indexRequest.abort()
    indexRequest = new AbortController()
    const signal = AbortSignal.any([current.baseSignal, indexRequest.signal])
    context.submitting.value = true
    knowledgeIndexLoading.value = false
    knowledgeIndexPreparing.value = true
    knowledgeIndexError.value = null
    try {
      const result = await current.client.warmKnowledgeIndex(current.shop, signal)
      if (!signal.aborted) knowledgeIndex.value = result
    } catch (failure) {
      if (!signal.aborted) {
        knowledgeIndexError.value = '准备结果暂时无法确认，请先刷新状态核实。'
        if (failure instanceof ApiError && [401, 403].includes(failure.status))
          report(failure, signal, '权限已变化，请重新登录。')
      }
    } finally {
      context.submitting.value = false
      if (!signal.aborted) knowledgeIndexPreparing.value = false
    }
  }

  function report(failure: unknown, signal: AbortSignal, fallback: string) {
    if (signal.aborted) return
    if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
      context.report(failure, signal, fallback)
      resetKnowledge()
    }
    knowledgeError.value = failure instanceof ApiError ? failure.message : fallback
  }

  async function loadKnowledge(more = false) {
    const current = runtime()
    if (!current || context.submitting.value || (more && knowledgeNextBeforeId.value === null))
      return
    if (more && knowledgeLoading.value) return
    listRequest.abort()
    listRequest = new AbortController()
    const signal = AbortSignal.any([current.baseSignal, listRequest.signal])
    const cursor = more ? knowledgeNextBeforeId.value : null
    knowledgeLoading.value = true
    knowledgeError.value = null
    try {
      const page = await current.client.knowledgeArticles(current.shop, cursor, signal)
      if (signal.aborted) return
      if (cursor !== null && page.nextBeforeId === cursor)
        throw new ApiError('分页位置未前进，请重新刷新列表。')
      const items = more ? [...knowledgeArticles.value, ...page.items] : page.items
      knowledgeArticles.value = Array.from(
        new Map(items.map((article) => [article.id, article])).values(),
      )
      knowledgeNextBeforeId.value = page.nextBeforeId
    } catch (failure) {
      report(failure, signal, '读取知识列表失败，请稍后刷新。')
    } finally {
      if (!signal.aborted) knowledgeLoading.value = false
    }
  }

  async function loadKnowledgeAudit(more = false) {
    const current = runtime()
    const id = knowledgeSelected.value?.id
    if (
      !current ||
      !id ||
      context.submitting.value ||
      (more && knowledgeNextAuditBeforeId.value === null)
    )
      return
    if (more && knowledgeAuditLoading.value) return
    auditRequest.abort()
    auditRequest = new AbortController()
    const signal = AbortSignal.any([current.baseSignal, auditRequest.signal])
    const cursor = more ? knowledgeNextAuditBeforeId.value : null
    knowledgeAuditLoading.value = true
    try {
      const page = await current.client.knowledgeAudit(current.shop, id, cursor, signal)
      if (signal.aborted || knowledgeSelected.value?.id !== id) return
      if (cursor !== null && page.nextBeforeId === cursor)
        throw new ApiError('操作记录分页位置未前进，请重新读取。')
      const items = more ? [...knowledgeAudit.value, ...page.items] : page.items
      knowledgeAudit.value = Array.from(new Map(items.map((entry) => [entry.id, entry])).values())
      knowledgeNextAuditBeforeId.value = page.nextBeforeId
    } catch (failure) {
      report(failure, signal, '读取操作记录失败，请稍后重新读取。')
    } finally {
      if (!signal.aborted) knowledgeAuditLoading.value = false
    }
  }

  async function openKnowledge(id: string, preserveInput = false) {
    const current = runtime()
    if (!current || context.submitting.value || knowledgeImportOpen.value) return
    if (
      knowledgeDirty.value &&
      !preserveInput &&
      !(knowledgeNeedsVerification.value && knowledgeSelected.value === null)
    ) {
      knowledgeError.value = '请先保存或取消当前本地编辑，再打开其他文档。'
      return
    }
    detailRequest.abort()
    auditRequest.abort()
    detailRequest = new AbortController()
    auditRequest = new AbortController()
    knowledgeAuditLoading.value = false
    const signal = AbortSignal.any([current.baseSignal, detailRequest.signal])
    knowledgeDetailLoading.value = true
    knowledgeError.value = null
    try {
      const article = await current.client.knowledgeArticle(current.shop, id, signal)
      if (signal.aborted) return
      knowledgeSelected.value = article
      knowledgeEditing.value = true
      knowledgeAudit.value = []
      knowledgeNextAuditBeforeId.value = null
      if (!preserveInput) fill(article)
      knowledgeNeedsVerification.value = false
      knowledgeNotice.value = preserveInput
        ? '已读取最新版本，本地编辑已保留，请对照后再保存或取消。'
        : null
      knowledgeArticles.value = knowledgeArticles.value.map((item) =>
        item.id === article.id ? article : item,
      )
      await loadKnowledgeAudit()
    } catch (failure) {
      report(failure, signal, '读取知识文档失败，请稍后重新读取。')
    } finally {
      if (!signal.aborted) knowledgeDetailLoading.value = false
    }
  }

  async function reloadKnowledge() {
    if (knowledgeSelected.value) await openKnowledge(knowledgeSelected.value.id, true)
    else {
      await loadKnowledge()
      if (knowledgeNeedsVerification.value)
        knowledgeNotice.value =
          '创建结果尚未确认。请先从列表找到刚创建的文档并打开核实，不要重复创建；取消只清除本地输入。'
    }
  }

  function newKnowledge() {
    if (!runtime() || context.submitting.value || knowledgeImportOpen.value) return
    if (knowledgeDirty.value) {
      knowledgeError.value = '请先保存或取消当前本地编辑。'
      return
    }
    resetEditor()
    knowledgeEditing.value = true
  }

  function cancelKnowledge() {
    if (context.submitting.value) return
    const wasUncertain = knowledgeNeedsVerification.value
    resetEditor()
    if (wasUncertain)
      knowledgeNotice.value = '已取消本地编辑；上次操作若因网络异常结果未知，请刷新列表核实。'
  }

  async function write(action: 'save' | 'publish' | 'offline') {
    const current = runtime()
    if (
      !current ||
      context.submitting.value ||
      knowledgeDetailLoading.value ||
      knowledgeNeedsVerification.value ||
      knowledgeImportOpen.value ||
      !knowledgeEditing.value
    )
      return
    const target = knowledgeSelected.value
    const value = input()
    if (action === 'save' && !validKnowledgeInput(value)) {
      knowledgeError.value = '请填写标题（1–120字）、1–12个关键词（每个1–40字）和正文（1–4000字）。'
      return
    }
    if (action !== 'save' && (!target || knowledgeDirty.value)) {
      knowledgeError.value = '请先保存草稿，再发布或下线已保存的文档。'
      return
    }
    if (action === 'offline' && target?.status !== 'PUBLISHED') return
    const signal = current.baseSignal
    context.submitting.value = true
    knowledgeError.value = null
    knowledgeNotice.value = null
    let succeeded = false
    try {
      const result =
        action === 'save'
          ? target
            ? await current.client.saveKnowledgeArticle(
                current.shop,
                target.id,
                target.version,
                value,
                signal,
              )
            : await current.client.createKnowledgeArticle(current.shop, value, signal)
          : action === 'publish'
            ? await current.client.publishKnowledgeArticle(
                current.shop,
                target!.id,
                target!.version,
                signal,
              )
            : await current.client.offlineKnowledgeArticle(
                current.shop,
                target!.id,
                target!.version,
                signal,
              )
      if (signal.aborted) return
      knowledgeSelected.value = result
      fill(result)
      knowledgeArticles.value = knowledgeArticles.value.some((item) => item.id === result.id)
        ? knowledgeArticles.value.map((item) => (item.id === result.id ? result : item))
        : [result, ...knowledgeArticles.value]
      knowledgeNotice.value =
        action === 'save'
          ? result.status === 'PUBLISHED'
            ? '草稿已保存。线上继续使用原发布快照，重新发布后才会更新回答。'
            : '草稿已保存，尚未发布到买家咨询。'
          : action === 'publish'
            ? '发布成功，当前店铺的新咨询将使用本次发布内容。'
            : '已下线，该文档不再参与当前店铺的新咨询检索。'
      knowledgeNeedsVerification.value = false
      succeeded = true
    } catch (failure) {
      if (!signal.aborted) {
        knowledgeNeedsVerification.value = !(failure instanceof ApiError && failure.status === 400)
        report(
          failure,
          signal,
          '操作结果暂时无法确认，请先重新读取文档或刷新列表核实，不要重复提交。',
        )
        if (runtime() && knowledgeNeedsVerification.value)
          knowledgeError.value = `${knowledgeError.value} 请先重新读取核实，本地输入仍保留，不会自动重放操作。`
      }
    } finally {
      context.submitting.value = false
    }
    if (succeeded && !signal.aborted) {
      await loadKnowledgeAudit()
      if (action !== 'save' && !signal.aborted) await loadKnowledgeIndex()
    }
  }

  return {
    knowledgeIndex,
    knowledgeIndexLoading,
    knowledgeIndexPreparing,
    knowledgeIndexError,
    loadKnowledgeIndex,
    warmKnowledgeIndex,
    knowledgeImportOpen,
    knowledgeImportFile,
    knowledgeImportPreview,
    knowledgeImportTitle,
    knowledgeImportKeywords,
    knowledgeImportLoading,
    knowledgeImportError,
    knowledgeImportNeedsVerification,
    openKnowledgeImport,
    closeKnowledgeImport,
    previewKnowledgeFile,
    confirmKnowledgeImport,
    knowledgeArticles,
    knowledgeSelected,
    knowledgeEditing,
    knowledgeTitle,
    knowledgeKeywords,
    knowledgeContent,
    knowledgeNextBeforeId,
    knowledgeAudit,
    knowledgeNextAuditBeforeId,
    knowledgeLoading,
    knowledgeDetailLoading,
    knowledgeAuditLoading,
    knowledgeError,
    knowledgeNotice,
    knowledgeNeedsVerification,
    knowledgeDirty,
    knowledgeHasUnpublishedChanges,
    resetKnowledge,
    loadKnowledge,
    loadKnowledgeAudit,
    openKnowledge,
    reloadKnowledge,
    newKnowledge,
    cancelKnowledge,
    saveKnowledge: () => write('save'),
    publishKnowledge: () => write('publish'),
    offlineKnowledge: () => write('offline'),
  }
}
