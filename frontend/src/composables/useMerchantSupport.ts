import { computed, onScopeDispose, ref, watch } from 'vue'
import { ApiClient, ApiError } from '../api/client'
import type { SupportSession } from '../types'
import { useSupportChannel } from './useSupportChannel'

export function useMerchantSupport(options: {
  client: () => ApiClient | null
  scope: () => string
  shop: () => string
  allowed: () => boolean
  report: (failure: unknown, signal: AbortSignal, fallback: string) => void
}) {
  const supportSessions = ref<SupportSession[]>([])
  const supportView = ref<SupportSession['status']>('WAITING')
  const supportNextBeforeId = ref<string | null>(null)
  const supportSelectedId = ref<string | null>(null)
  const supportLoading = ref(false)
  const supportClaiming = ref(false)
  const supportError = ref<string | null>(null)
  let context = new AbortController()
  let timer: ReturnType<typeof setTimeout> | null = null
  const channel = useSupportChannel({
    scope: () => `${options.scope()}/${supportSelectedId.value ?? ''}/${options.allowed()}`,
    enabled: () => options.allowed() && !!supportSelectedId.value && !!options.client(),
    read: (signal, beforeId) =>
      options
        .client()!
        .merchantSupportSnapshot(options.shop(), supportSelectedId.value!, signal, beforeId),
    send: (sessionId, id, text, signal) =>
      options.client()!.sendSupportMessage(options.shop(), null, sessionId, id, text, signal),
    close: (sessionId, signal) =>
      options.client()!.closeSupport(options.shop(), null, sessionId, signal),
    canWrite: (session) => session.status === 'ACTIVE' && session.assignedToMe,
    onError: (failure, signal) => {
      if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
        if (failure.status === 401) {
          options.report(failure, signal, '请重新登录。')
          return
        }
        supportError.value = '这条咨询当前无法访问，请刷新接待列表。'
        channel.reset()
        supportSelectedId.value = null
      }
    },
    onClosed: () => void loadSupportSessions(),
  })
  const supportSubmitting = computed(() => supportClaiming.value || channel.submitting.value)

  function stopTimer() {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }
  function schedule() {
    stopTimer()
    if (
      !options.allowed() ||
      document.visibilityState === 'hidden' ||
      supportView.value === 'CLOSED'
    )
      return
    timer = setTimeout(() => void loadSupportSessions(), 4000)
  }
  async function loadSupportSessions(older = false) {
    if (
      !options.client() ||
      !options.allowed() ||
      supportLoading.value ||
      supportSubmitting.value ||
      (older && !supportNextBeforeId.value)
    )
      return
    const signal = context.signal
    const cursor = older ? supportNextBeforeId.value : null
    supportLoading.value = true
    supportError.value = null
    stopTimer()
    try {
      const result = await options
        .client()!
        .supportSessions(options.shop(), supportView.value, cursor, signal)
      if (signal.aborted) return
      if (cursor && cursor === result.nextBeforeId)
        throw new ApiError('接待列表分页未前进，请刷新后重试。')
      supportSessions.value = [
        ...new Map(
          [...(older ? supportSessions.value : []), ...result.items].map((item) => [item.id, item]),
        ).values(),
      ]
      supportNextBeforeId.value = result.nextBeforeId
    } catch (failure) {
      if (!signal.aborted) {
        supportError.value =
          failure instanceof ApiError ? failure.message : '暂时无法获取接待列表，请刷新后重试。'
        if (failure instanceof ApiError && failure.status === 403) {
          supportSessions.value = []
          supportSelectedId.value = null
        }
        options.report(failure, signal, supportError.value)
      }
    } finally {
      if (!signal.aborted) {
        supportLoading.value = false
        schedule()
      }
    }
  }
  async function changeSupportView(status: SupportSession['status']) {
    if (supportSubmitting.value || supportLoading.value || !options.allowed()) return
    supportView.value = status
    supportSelectedId.value = null
    supportSessions.value = []
    supportNextBeforeId.value = null
    await loadSupportSessions()
  }
  function openSupport(id: string) {
    if (
      !options.allowed() ||
      supportSubmitting.value ||
      !supportSessions.value.some((item) => item.id === id)
    )
      return
    if (supportSelectedId.value === id) void channel.refresh()
    else supportSelectedId.value = id
  }
  async function claimSupport() {
    if (
      !options.client() ||
      !options.allowed() ||
      supportSubmitting.value ||
      channel.session.value?.status !== 'WAITING' ||
      !supportSelectedId.value
    )
      return
    const signal = context.signal
    const id = supportSelectedId.value
    supportClaiming.value = true
    supportError.value = null
    try {
      const result = await options.client()!.claimSupport(options.shop(), id, signal)
      if (!signal.aborted) channel.accept(result)
    } catch (failure) {
      if (!signal.aborted) {
        supportError.value =
          failure instanceof ApiError && [403, 404, 409].includes(failure.status)
            ? '这条接待已由其他客服处理，请刷新接待列表。'
            : '接待结果暂无法确认，请刷新记录核实，不会自动重复接待。'
        if (failure instanceof ApiError && failure.status === 401) {
          options.report(failure, signal, '请重新登录。')
          return
        }
        // A read resolves a claim race or a lost response; it never replays the claim.
        await channel.refresh()
      }
    } finally {
      if (!signal.aborted) {
        supportClaiming.value = false
        await loadSupportSessions()
      }
    }
  }
  function resetSupport() {
    stopTimer()
    context.abort()
    context = new AbortController()
    supportSessions.value = []
    supportNextBeforeId.value = null
    supportSelectedId.value = null
    supportView.value = 'WAITING'
    supportLoading.value = false
    supportClaiming.value = false
    supportError.value = null
    channel.reset()
  }
  watch(
    () => `${options.scope()}/${options.allowed()}`,
    () => {
      resetSupport()
      if (options.allowed()) void loadSupportSessions()
    },
    { immediate: true, flush: 'sync' },
  )
  function visible() {
    stopTimer()
    if (document.visibilityState !== 'hidden' && options.allowed()) void loadSupportSessions()
  }
  document.addEventListener('visibilitychange', visible)
  onScopeDispose(() => {
    resetSupport()
    document.removeEventListener('visibilitychange', visible)
  })
  return {
    supportSessions,
    supportView,
    supportNextBeforeId,
    supportSelectedId,
    supportLoading,
    supportSubmitting,
    supportError,
    supportSession: channel.session,
    supportMessages: channel.messages,
    supportMessageBeforeId: channel.nextBeforeId,
    supportDetailLoading: channel.loading,
    supportDetailError: channel.error,
    supportWritable: channel.writable,
    supportUncertainMessage: channel.uncertainMessage,
    supportUncertainClose: channel.uncertainClose,
    loadSupportSessions,
    changeSupportView,
    openSupport,
    claimSupport,
    resetSupport,
    refreshSupport: channel.refresh,
    sendSupportReply: channel.send,
    closeSupportSession: channel.close,
    retrySupportReply: () => channel.send('', true),
  }
}
