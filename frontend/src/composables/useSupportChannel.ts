import { computed, onScopeDispose, ref, watch } from 'vue'
import { ApiError } from '../api/client'
import type { SupportMessage, SupportMessagePage, SupportSession, SupportSnapshot } from '../types'

/** A scoped transcript. Reads may repeat; every write requires an explicit user action. */
export function useSupportChannel(options: {
  scope: () => string
  enabled: () => boolean
  visible?: () => boolean
  read: (signal: AbortSignal, beforeId?: string) => Promise<SupportSnapshot | SupportMessagePage>
  send: (
    sessionId: string,
    id: string,
    text: string,
    signal: AbortSignal,
  ) => Promise<SupportMessage>
  close: (sessionId: string, signal: AbortSignal) => Promise<SupportSnapshot>
  canWrite: (session: SupportSession) => boolean
  onError?: (failure: unknown, signal: AbortSignal) => void
  onClosed?: () => void
}) {
  const session = ref<SupportSession | null>(null)
  const messages = ref<SupportMessage[]>([])
  const nextBeforeId = ref<string | null>(null)
  const loading = ref(false)
  const submitting = ref(false)
  const ready = ref(false)
  const error = ref<string | null>(null)
  const uncertainMessage = ref<{ id: string; text: string } | null>(null)
  const uncertainClose = ref(false)
  const active = computed(() => !!session.value && session.value.status !== 'CLOSED')
  const writable = computed(
    () => !!session.value && active.value && options.canWrite(session.value),
  )
  let lifetime = new AbortController()
  let readRequest = new AbortController()
  let timer: ReturnType<typeof setTimeout> | null = null

  function stopTimer() {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  function isVisible() {
    return document.visibilityState !== 'hidden' && (options.visible?.() ?? true)
  }

  function schedule() {
    stopTimer()
    if (!options.enabled() || !isVisible() || !active.value) return
    timer = setTimeout(() => void refresh(), 4000)
  }

  function merge(items: SupportMessage[]) {
    messages.value = [
      ...new Map([...messages.value, ...items].map((item) => [item.id, item])).values(),
    ].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
    if (
      uncertainMessage.value &&
      messages.value.some((item) => item.clientMessageId === uncertainMessage.value!.id)
    )
      uncertainMessage.value = null
  }

  function cancelRead() {
    readRequest.abort()
    readRequest = new AbortController()
    loading.value = false
  }

  function accept(result: SupportSnapshot | SupportMessagePage, older = false, fromRead = false) {
    if (!fromRead) cancelRead()
    const existingIds = new Set(messages.value.map((item) => item.id))
    const separatedPage =
      result.messages.length > 0 && !result.messages.some((item) => existingIds.has(item.id))
    merge(result.messages)
    // Refreshing the newest page must not discard an older-page cursor or loaded history.
    if (older || !ready.value || separatedPage) nextBeforeId.value = result.nextBeforeId
    if ('session' in result) {
      const wasActive = active.value
      if (session.value && result.session?.id !== session.value.id) uncertainMessage.value = null
      session.value = result.session
      if (result.session?.status === 'CLOSED') {
        uncertainClose.value = false
        if (wasActive) options.onClosed?.()
      }
    }
    ready.value = true
    schedule()
  }

  function report(failure: unknown, signal: AbortSignal, fallback: string) {
    if (signal.aborted) return
    error.value = failure instanceof ApiError ? failure.message : fallback
    options.onError?.(failure, signal)
  }

  async function refresh(older = false) {
    if (!options.enabled() || submitting.value || (older && (!nextBeforeId.value || loading.value)))
      return
    readRequest.abort()
    readRequest = new AbortController()
    const signal = AbortSignal.any([lifetime.signal, readRequest.signal])
    const cursor = older ? nextBeforeId.value! : undefined
    loading.value = true
    error.value = null
    stopTimer()
    try {
      const result = await options.read(signal, cursor)
      if (signal.aborted) return
      if (cursor && result.nextBeforeId === cursor)
        throw new ApiError('记录分页未前进，请刷新后重试。')
      accept(result, older, true)
    } catch (failure) {
      report(failure, signal, '暂时无法获取人工服务记录，请刷新后重试。')
    } finally {
      if (!signal.aborted) {
        loading.value = false
        schedule()
      }
    }
  }

  async function send(text: string, retry = false) {
    const pending = retry ? uncertainMessage.value : { id: crypto.randomUUID(), text: text.trim() }
    if (
      !options.enabled() ||
      !ready.value ||
      !writable.value ||
      submitting.value ||
      (!retry && uncertainMessage.value) ||
      uncertainClose.value ||
      !pending?.text ||
      pending.text.length > 2000
    )
      return false
    const signal = lifetime.signal
    const target = session.value!.id
    cancelRead()
    submitting.value = true
    error.value = null
    stopTimer()
    try {
      const message = await options.send(target, pending.id, pending.text, signal)
      if (signal.aborted) return false
      merge([message])
      uncertainMessage.value = null
      return true
    } catch (failure) {
      if (!signal.aborted) {
        if (
          !(failure instanceof ApiError) ||
          ![400, 401, 403, 404, 409, 429].includes(failure.status)
        ) {
          uncertainMessage.value = pending
          error.value = '这条消息的发送结果暂无法确认。请刷新记录核实；手动重试会使用同一条消息。'
        } else report(failure, signal, '发送失败，请刷新后重试。')
      }
      return false
    } finally {
      if (!signal.aborted) {
        submitting.value = false
        schedule()
      }
    }
  }

  async function close() {
    if (
      !options.enabled() ||
      !ready.value ||
      !active.value ||
      submitting.value ||
      !session.value ||
      !options.canWrite(session.value)
    )
      return
    const signal = lifetime.signal
    cancelRead()
    submitting.value = true
    stopTimer()
    error.value = null
    try {
      const result = await options.close(session.value.id, signal)
      if (!signal.aborted) accept(result)
    } catch (failure) {
      if (!signal.aborted) {
        uncertainClose.value = true
        report(failure, signal, '结束结果暂无法确认，请刷新记录核实。')
      }
    } finally {
      if (!signal.aborted) {
        submitting.value = false
        schedule()
      }
    }
  }

  function reset() {
    stopTimer()
    lifetime.abort()
    readRequest.abort()
    lifetime = new AbortController()
    readRequest = new AbortController()
    session.value = null
    messages.value = []
    nextBeforeId.value = null
    loading.value = false
    submitting.value = false
    ready.value = false
    error.value = null
    uncertainMessage.value = null
    uncertainClose.value = false
  }

  watch(
    options.scope,
    () => {
      reset()
      if (options.enabled()) void refresh()
    },
    { immediate: true, flush: 'sync' },
  )
  watch(
    () => options.visible?.() ?? true,
    () => {
      stopTimer()
      if (isVisible() && options.enabled()) void refresh()
    },
  )
  function visibilityChanged() {
    stopTimer()
    if (!isVisible()) readRequest.abort()
    else if (options.enabled()) void refresh()
  }
  document.addEventListener('visibilitychange', visibilityChanged)
  onScopeDispose(() => {
    reset()
    document.removeEventListener('visibilitychange', visibilityChanged)
  })
  return {
    session,
    messages,
    nextBeforeId,
    loading,
    submitting,
    ready,
    error,
    uncertainMessage,
    uncertainClose,
    active,
    writable,
    refresh,
    send,
    close,
    accept,
    reset,
  }
}
