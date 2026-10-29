import { computed, onScopeDispose, ref } from 'vue'
import { ApiClient, ApiError } from '../api/client'
import type { AccountCapabilities, AfterSaleTicket, MerchantShop } from '../types'
import { useMerchantKnowledge } from './useMerchantKnowledge'

/** Merchant-only workflow. The server's current authorization is authoritative on every request. */
export function useMerchantDesk() {
  const username = ref('')
  const connected = ref(false)
  const connecting = ref(false)
  const loginError = ref<string | null>(null)
  const capabilities = ref<AccountCapabilities>({ reviewAfterSales: false, manageKnowledge: false })
  const activeModule = ref<'after-sales' | 'knowledge'>('after-sales')
  const canReviewAfterSales = computed(() => connected.value && capabilities.value.reviewAfterSales)
  const canManageKnowledge = computed(() => connected.value && capabilities.value.manageKnowledge)
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
  let connectingClient: ApiClient | null = null
  let accountContext = new AbortController()
  let shopContext = new AbortController()
  const knowledge = useMerchantKnowledge({
    client: () => client,
    shop: () => (selectedShop.value ? shopId.value : null),
    signal: () => shopContext.signal,
    allowed: () => canManageKnowledge.value && activeModule.value === 'knowledge',
    submitting,
    report,
  })
  const navigationBlocked = computed(
    () =>
      submitting.value ||
      knowledge.knowledgeDirty.value ||
      knowledge.knowledgeImportOpen.value ||
      knowledge.knowledgeNeedsVerification.value,
  )

  function guardNavigation(event: BeforeUnloadEvent) {
    if (!navigationBlocked.value) return
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
    knowledge.resetKnowledge()
  }

  function resetAccount() {
    client?.dispose()
    connectingClient?.dispose()
    accountContext.abort()
    accountContext = new AbortController()
    resetShop()
    client = null
    connectingClient = null
    connected.value = false
    connecting.value = false
    username.value = ''
    shops.value = []
    shopId.value = ''
    loginError.value = null
    capabilities.value = { reviewAfterSales: false, manageKnowledge: false }
    activeModule.value = 'after-sales'
  }

  function disconnect(): boolean {
    // Do not cancel a business POST and leave the operator unsure whether it committed.
    if (navigationBlocked.value) return false
    const previous = client ?? connectingClient
    const logout = previous?.logout()
    resetAccount()
    const signal = accountContext.signal
    void logout?.catch(() => {
      if (!signal.aborted && !connected.value)
        loginError.value = '页面数据已清除，但退出登录未能确认。请重新登录，或关闭此页面。'
    })
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
    if (connecting.value || navigationBlocked.value) return false
    disconnect()
    connecting.value = true
    const signal = accountContext.signal
    let candidate: ApiClient | null = null
    try {
      candidate = new ApiClient(account.trim(), password, fetch, {
        authentication: 'session',
        onSessionInvalidated: () => {
          if (signal.aborted) return
          resetAccount()
          loginError.value = '账号已在其他页面切换或退出，请重新登录。'
        },
      })
      connectingClient = candidate
      await candidate.authenticate(signal)
      if (signal.aborted) return false
      const identity = await candidate.currentAccount(signal)
      if (signal.aborted) return false
      if (identity.accountType !== 'MERCHANT_STAFF') {
        throw new ApiError('这是买家账号，请使用用户端登录。', 0, 'WRONG_ACCOUNT_TYPE')
      }
      const granted =
        identity.capabilities.reviewAfterSales || identity.capabilities.manageKnowledge
          ? await candidate.merchantShops(signal)
          : []
      if (signal.aborted) return false
      client = candidate
      connectingClient = null
      username.value = identity.username
      shops.value = granted
      capabilities.value = identity.capabilities
      activeModule.value = identity.capabilities.reviewAfterSales ? 'after-sales' : 'knowledge'
      connected.value = true
      connecting.value = false
      if (granted[0]) await changeShop(String(granted[0].id))
      return connected.value
    } catch (failure) {
      void candidate?.logout().catch(() => undefined)
      if (connectingClient === candidate) connectingClient = null
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
      navigationBlocked.value ||
      !connected.value ||
      !shops.value.some((shop) => String(shop.id) === nextId)
    )
      return
    if (shopId.value === nextId) return
    resetShop()
    shopId.value = nextId
    if (activeModule.value === 'after-sales') await loadReviews('pending')
    else {
      await knowledge.loadKnowledge()
      await knowledge.loadKnowledgeIndex()
    }
  }

  async function switchModule(next: 'after-sales' | 'knowledge') {
    if (navigationBlocked.value || !connected.value || activeModule.value === next) return
    if (
      (next === 'after-sales' && !canReviewAfterSales.value) ||
      (next === 'knowledge' && !canManageKnowledge.value)
    )
      return
    resetShop()
    activeModule.value = next
    if (next === 'after-sales') await loadReviews('pending')
    else {
      await knowledge.loadKnowledge()
      await knowledge.loadKnowledgeIndex()
    }
  }

  async function loadReviews(nextView: 'pending' | 'history' = view.value) {
    if (
      !client ||
      !selectedShop.value ||
      !canReviewAfterSales.value ||
      activeModule.value !== 'after-sales' ||
      loading.value ||
      submitting.value
    )
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
      !canReviewAfterSales.value ||
      activeModule.value !== 'after-sales' ||
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
      !canReviewAfterSales.value ||
      activeModule.value !== 'after-sales' ||
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
    client?.dispose()
    void connectingClient?.logout().catch(() => undefined)
    window.removeEventListener('beforeunload', guardNavigation)
    accountContext.abort()
    shopContext.abort()
    knowledge.resetKnowledge()
    client = null
  })

  return {
    ...knowledge,
    capabilities,
    activeModule,
    canReviewAfterSales,
    canManageKnowledge,
    navigationBlocked,
    switchModule,
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
