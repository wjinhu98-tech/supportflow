import { onScopeDispose, ref } from 'vue'
import { ApiClient, ApiError, validShopId } from '../api/client'
import type { AfterSaleTicket, ChatMessage, Order } from '../types'

/** UI orchestrator: events -> API DTOs -> reactive state. No backend ownership decisions here. */
export function useSupportDesk() {
  const initialShop = new URL(window.location.href).searchParams.get('shopId') ?? '100'
  const shopId = ref(validShopId(initialShop) ? initialShop : '100')
  const username = ref('')
  const connected = ref(false)
  const connecting = ref(false)
  const busy = ref(false)
  const ordersBusy = ref(false)
  const orders = ref<Order[]>([])
  const nextAfterId = ref<number | null>(null)
  const selectedOrder = ref<Order | null>(null)
  const messages = ref<ChatMessage[]>([])
  const error = ref<string | null>(
    validShopId(initialShop) ? null : '链接中的店铺 ID 无效，已回到店铺 100。',
  )
  const loginError = ref<string | null>(null)
  const reviewTickets = ref<AfterSaleTicket[]>([])
  const reviewHistoryTickets = ref<AfterSaleTicket[]>([])
  const reviewView = ref<'pending' | 'history'>('pending')
  const reviewLoading = ref(false)
  const reviewSubmitting = ref(false)
  const reviewError = ref<string | null>(null)
  const ownedAfterSales = ref<AfterSaleTicket[]>([])
  const afterSalesLoading = ref(false)
  const afterSalesError = ref<string | null>(null)
  let client: ApiClient | null = null
  let context = new AbortController()
  let messageId = 0

  function resetContext() {
    context.abort()
    context = new AbortController()
    busy.value = false
    ordersBusy.value = false
    orders.value = []
    nextAfterId.value = null
    selectedOrder.value = null
    messages.value = []
    error.value = null
    reviewTickets.value = []
    reviewHistoryTickets.value = []
    reviewView.value = 'pending'
    reviewLoading.value = false
    reviewSubmitting.value = false
    reviewError.value = null
    ownedAfterSales.value = []
    afterSalesLoading.value = false
    afterSalesError.value = null
  }

  function disconnect() {
    resetContext()
    client = null
    username.value = ''
    connected.value = false
    connecting.value = false
    loginError.value = null
  }

  function report(failure: unknown, signal: AbortSignal) {
    if (signal.aborted) return
    const text = failure instanceof ApiError ? failure.message : '操作失败，请稍后重试。'
    if (failure instanceof ApiError && failure.status === 401) {
      disconnect()
      loginError.value = text
    }
    error.value = text
  }

  async function connect(account: string, password: string): Promise<boolean> {
    if (connecting.value) return false
    disconnect()
    connecting.value = true
    const signal = context.signal
    try {
      const candidate = new ApiClient(account.trim(), password)
      await candidate.authenticate(signal)
      if (signal.aborted) return false
      client = candidate
      username.value = account.trim()
      connected.value = true
      connecting.value = false
      await loadOrders()
      return connected.value
    } catch (failure) {
      if (!signal.aborted) {
        loginError.value = failure instanceof Error ? failure.message : '连接失败，请检查账号信息。'
      }
      return false
    } finally {
      if (!signal.aborted) connecting.value = false
    }
  }

  async function loadOrders(loadMore = false) {
    if (!client || !connected.value || ordersBusy.value) return
    if (loadMore && nextAfterId.value === null) return
    const signal = context.signal
    const cursor = loadMore ? nextAfterId.value : null
    const activeShop = shopId.value
    ordersBusy.value = true
    error.value = null
    try {
      const response = await client.chat(
        activeShop,
        cursor === null ? '我的订单' : `我的订单 ${cursor}`,
        undefined,
        signal,
      )
      if (signal.aborted) return
      if (response.type !== 'ORDER_SELECTION') throw new ApiError('订单列表响应格式不正确。')
      if (response.nextAfterId !== null && cursor !== null && response.nextAfterId <= cursor) {
        throw new ApiError('分页游标没有前进，请刷新订单后重试。')
      }
      const items = loadMore ? [...orders.value, ...response.orders] : response.orders
      orders.value = Array.from(new Map(items.map((order) => [order.orderNo, order])).values())
      nextAfterId.value = response.nextAfterId
    } catch (failure) {
      report(failure, signal)
    } finally {
      if (!signal.aborted) ordersBusy.value = false
    }
  }

  async function changeShop(value: string) {
    const normalized = value.trim()
    if (!validShopId(normalized)) {
      error.value = '店铺 ID 必须为有效的正整数。'
      return
    }
    if (normalized === shopId.value) return
    const reconnectNeeded = connecting.value
    resetContext()
    if (reconnectNeeded) {
      connecting.value = false
      client = null
      connected.value = false
    }
    shopId.value = normalized
    const url = new URL(window.location.href)
    url.searchParams.set('shopId', normalized)
    window.history.replaceState(null, '', url)
    if (connected.value) await loadOrders()
  }

  async function sendMessage(text: string) {
    const message = text.trim()
    if (!message) return
    if (message.length > 1000) {
      error.value = '消息不能超过 1000 个字符。'
      return
    }
    if (!client || !connected.value) {
      error.value = '请先连接账号，再咨询订单。'
      return
    }
    if (busy.value) return
    const signal = context.signal
    const activeShop = shopId.value
    const selected = selectedOrder.value?.orderNo
    busy.value = true
    error.value = null
    messages.value.push({
      id: ++messageId,
      role: 'user',
      text: message,
      createdAt: new Date().toISOString(),
    })
    try {
      const response = await client.chat(activeShop, message, selected, signal)
      if (signal.aborted) return
      messages.value.push({
        id: ++messageId,
        role: 'assistant',
        text: response.reply,
        createdAt: new Date().toISOString(),
        type: response.type,
        orders: response.orders,
      })
      if (response.type === 'ORDER_DETAILS' && response.orders[0])
        selectedOrder.value = response.orders[0]
    } catch (failure) {
      report(failure, signal)
    } finally {
      if (!signal.aborted) busy.value = false
    }
  }

  async function selectOrder(order: Order) {
    if (!connected.value || busy.value || order.shopId !== Number(shopId.value)) return
    selectedOrder.value = order
    await sendMessage('咨询此订单')
  }

  function clearSelectedOrder() {
    selectedOrder.value = null
  }
  function clearConversation() {
    // An approval is a business write: clearing chat must not cancel its result
    // and leave the reviewer unsure whether the server accepted the decision.
    if (connecting.value || reviewSubmitting.value) return
    // Abort old requests before removing their conversation, then preserve the current order list.
    context.abort()
    context = new AbortController()
    busy.value = false
    ordersBusy.value = false
    reviewLoading.value = false
    reviewError.value = null
    afterSalesLoading.value = false
    afterSalesError.value = null
    messages.value = []
    selectedOrder.value = null
    error.value = null
  }
  function dismissError() {
    error.value = null
  }

  async function loadReviews(view: 'pending' | 'history' = reviewView.value) {
    if (!client || !connected.value || reviewLoading.value || reviewSubmitting.value) return
    const signal = context.signal
    reviewView.value = view
    reviewLoading.value = true
    reviewError.value = null
    try {
      const tickets =
        view === 'pending'
          ? await client.pendingReviews(signal)
          : await client.reviewHistory(signal)
      if (!signal.aborted) {
        if (view === 'pending') reviewTickets.value = tickets
        else reviewHistoryTickets.value = tickets
      }
    } catch (failure) {
      if (!signal.aborted) {
        reviewError.value = failure instanceof Error ? failure.message : '读取审核队列失败。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) reviewLoading.value = false
    }
  }

  async function submitReview(payload: {
    id: string
    decision: 'APPROVE' | 'REJECT'
    note: string
  }) {
    if (!client || !connected.value || reviewSubmitting.value) return
    const signal = context.signal
    reviewSubmitting.value = true
    reviewError.value = null
    try {
      const result = await client.decideReview(payload.id, payload.decision, payload.note, signal)
      if (!signal.aborted) {
        reviewTickets.value = reviewTickets.value.filter((ticket) => ticket.id !== payload.id)
        reviewHistoryTickets.value = [
          result,
          ...reviewHistoryTickets.value.filter((ticket) => ticket.id !== result.id),
        ].slice(0, 50)
      }
    } catch (failure) {
      if (!signal.aborted) {
        reviewError.value = failure instanceof Error ? failure.message : '提交审核失败。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) reviewSubmitting.value = false
    }
  }

  async function retryExecution(id: string) {
    if (!client || !connected.value || reviewSubmitting.value || reviewLoading.value) return
    const ticket = reviewHistoryTickets.value.find((item) => item.id === id)
    if (!ticket?.execution?.retryable || ticket.execution.status !== 'FAILED') return
    const signal = context.signal
    reviewSubmitting.value = true
    reviewError.value = null
    try {
      const result = await client.retryExecution(id, signal)
      if (!signal.aborted)
        reviewHistoryTickets.value = reviewHistoryTickets.value.map((item) =>
          item.id === id ? result : item,
        )
    } catch (failure) {
      if (!signal.aborted) {
        reviewError.value =
          failure instanceof Error ? failure.message : '提交重试失败，请刷新后核对处理进度。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) reviewSubmitting.value = false
    }
  }

  async function loadOwnedAfterSales() {
    if (!client || !connected.value || afterSalesLoading.value) return
    const signal = context.signal
    const activeShop = shopId.value
    afterSalesLoading.value = true
    afterSalesError.value = null
    try {
      const tickets = await client.ownedAfterSales(activeShop, signal)
      if (!signal.aborted) ownedAfterSales.value = tickets
    } catch (failure) {
      if (!signal.aborted) {
        afterSalesError.value = failure instanceof Error ? failure.message : '读取售后进度失败。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) afterSalesLoading.value = false
    }
  }
  onScopeDispose(() => {
    context.abort()
    client = null
  })

  return {
    reviewTickets,
    reviewHistoryTickets,
    reviewView,
    reviewLoading,
    reviewSubmitting,
    reviewError,
    loadReviews,
    submitReview,
    retryExecution,
    ownedAfterSales,
    afterSalesLoading,
    afterSalesError,
    loadOwnedAfterSales,
    shopId,
    username,
    connected,
    connecting,
    busy,
    ordersBusy,
    orders,
    nextAfterId,
    selectedOrder,
    messages,
    error,
    loginError,
    connect,
    disconnect,
    changeShop,
    loadOrders,
    selectOrder,
    clearSelectedOrder,
    sendMessage,
    clearConversation,
    dismissError,
  }
}
