import { computed, onScopeDispose, ref } from 'vue'
import { ApiClient, ApiError } from '../api/client'
import type { AfterSaleTicket, MerchantShop } from '../types'

/** Merchant-only workflow. The server's current authorization is authoritative on every request. */
export function useMerchantDesk() {
  const username = ref('')
  const connected = ref(false)
  const connecting = ref(false)
  const loginError = ref<string | null>(null)
  const shops = ref<MerchantShop[]>([])
  const shopId = ref('')
  const selectedShop = computed(() => shops.value.find((shop) => String(shop.id) === shopId.value))
  const tickets = ref<AfterSaleTicket[]>([])
  const historyTickets = ref<AfterSaleTicket[]>([])
  const view = ref<'pending' | 'history'>('pending')
  const loading = ref(false)
  const submitting = ref(false)
  const needsVerification = ref(false)
  const error = ref<string | null>(null)
  let client: ApiClient | null = null
  let accountContext = new AbortController()
  let shopContext = new AbortController()

  function guardNavigation(event: BeforeUnloadEvent) {
    if (!submitting.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  window.addEventListener('beforeunload', guardNavigation)

  function resetShop() {
    shopContext.abort()
    shopContext = new AbortController()
    tickets.value = []
    historyTickets.value = []
    view.value = 'pending'
    loading.value = false
    error.value = null
    needsVerification.value = false
  }

  function resetAccount() {
    accountContext.abort()
    accountContext = new AbortController()
    resetShop()
    client = null
    connected.value = false
    connecting.value = false
    username.value = ''
    shops.value = []
    shopId.value = ''
    loginError.value = null
  }

  function disconnect(): boolean {
    // Do not cancel a business POST and leave the operator unsure whether it committed.
    if (submitting.value) return false
    resetAccount()
    return true
  }

  function report(failure: unknown, signal: AbortSignal, fallback: string) {
    if (signal.aborted) return
    const message = failure instanceof ApiError ? failure.message : fallback
    if (failure instanceof ApiError && failure.status === 401) {
      resetAccount()
      loginError.value = message
    } else {
      error.value = message
      if (failure instanceof ApiError && failure.status === 403) {
        // Authorization may have been revoked. Do not retain the formerly accessible data.
        tickets.value = []
        historyTickets.value = []
      }
    }
  }

  async function connect(account: string, password: string): Promise<boolean> {
    if (connecting.value || submitting.value) return false
    resetAccount()
    connecting.value = true
    const signal = accountContext.signal
    try {
      const candidate = new ApiClient(account.trim(), password)
      await candidate.authenticate(signal)
      if (signal.aborted) return false
      const identity = await candidate.currentAccount(signal)
      if (signal.aborted) return false
      if (identity.accountType !== 'MERCHANT_STAFF') {
        throw new ApiError('这是买家账号，请使用用户端登录。', 0, 'WRONG_ACCOUNT_TYPE')
      }
      const granted = await candidate.merchantShops(signal)
      if (signal.aborted) return false
      client = candidate
      username.value = identity.username
      shops.value = granted
      connected.value = true
      connecting.value = false
      if (granted[0]) await changeShop(String(granted[0].id))
      return connected.value
    } catch (failure) {
      if (!signal.aborted) {
        client = null
        loginError.value = failure instanceof Error ? failure.message : '登录失败，请检查账号信息。'
      }
      return false
    } finally {
      if (!signal.aborted) connecting.value = false
    }
  }

  async function changeShop(nextId: string) {
    if (
      submitting.value ||
      !connected.value ||
      !shops.value.some((shop) => String(shop.id) === nextId)
    )
      return
    if (shopId.value === nextId) return
    resetShop()
    shopId.value = nextId
    await loadReviews('pending')
  }

  async function loadReviews(nextView: 'pending' | 'history' = view.value) {
    if (!client || !selectedShop.value || !connected.value || loading.value || submitting.value)
      return
    const activeClient = client
    const activeShop = shopId.value
    const signal = shopContext.signal
    loading.value = true
    view.value = nextView
    error.value = null
    try {
      const rows =
        nextView === 'pending'
          ? await activeClient.pendingReviews(activeShop, signal)
          : await activeClient.reviewHistory(activeShop, signal)
      if (signal.aborted) return
      if (nextView === 'pending') tickets.value = rows
      else historyTickets.value = rows
      needsVerification.value = false
    } catch (failure) {
      report(failure, signal, '读取当前店铺售后列表失败，请稍后刷新。')
    } finally {
      if (!signal.aborted) loading.value = false
    }
  }

  async function submitReview(payload: {
    id: string
    decision: 'APPROVE' | 'REJECT'
    note: string
  }) {
    if (
      !client ||
      !connected.value ||
      !selectedShop.value ||
      loading.value ||
      submitting.value ||
      needsVerification.value
    )
      return
    if (
      !tickets.value.some(
        (ticket) => ticket.id === payload.id && ticket.status === 'PENDING_REVIEW',
      )
    )
      return
    const activeClient = client
    const activeShop = shopId.value
    const signal = shopContext.signal
    submitting.value = true
    error.value = null
    try {
      const result = await activeClient.decideReview(
        activeShop,
        payload.id,
        payload.decision,
        payload.note,
        signal,
      )
      if (signal.aborted) return
      tickets.value = tickets.value.filter((ticket) => ticket.id !== result.id)
      historyTickets.value = [
        result,
        ...historyTickets.value.filter((ticket) => ticket.id !== result.id),
      ].slice(0, 50)
    } catch (failure) {
      if (!signal.aborted) {
        needsVerification.value = true
        report(failure, signal, '审批结果暂时无法确认，请刷新当前店铺记录后核实，不要重复提交。')
        if (connected.value)
          error.value = `${error.value ?? '审批未完成。'} 请先刷新当前店铺记录核实结果。`
      }
    } finally {
      submitting.value = false
    }
  }

  async function retryExecution(id: string) {
    if (
      !client ||
      !connected.value ||
      !selectedShop.value ||
      loading.value ||
      submitting.value ||
      needsVerification.value
    )
      return
    const ticket = historyTickets.value.find((item) => item.id === id)
    if (!ticket?.execution?.retryable || ticket.execution.status !== 'FAILED') return
    const activeClient = client
    const activeShop = shopId.value
    const signal = shopContext.signal
    submitting.value = true
    error.value = null
    try {
      const result = await activeClient.retryExecution(activeShop, id, signal)
      if (!signal.aborted)
        historyTickets.value = historyTickets.value.map((item) => (item.id === id ? result : item))
    } catch (failure) {
      if (!signal.aborted) {
        needsVerification.value = true
        report(failure, signal, '重试结果暂时无法确认，请刷新当前店铺记录后核实。')
        if (connected.value)
          error.value = `${error.value ?? '重试未完成。'} 请先刷新当前店铺记录核实结果。`
      }
    } finally {
      submitting.value = false
    }
  }

  onScopeDispose(() => {
    window.removeEventListener('beforeunload', guardNavigation)
    accountContext.abort()
    shopContext.abort()
    client = null
  })

  return {
    username,
    connected,
    connecting,
    loginError,
    shops,
    shopId,
    selectedShop,
    tickets,
    historyTickets,
    view,
    loading,
    submitting,
    needsVerification,
    error,
    connect,
    disconnect,
    changeShop,
    loadReviews,
    submitReview,
    retryExecution,
  }
}
