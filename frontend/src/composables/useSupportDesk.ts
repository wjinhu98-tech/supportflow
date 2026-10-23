import { onScopeDispose, ref, watch } from 'vue'
import { ApiClient, ApiError, validShopId } from '../api/client'
import type {
  AfterSaleEligibility,
  AfterSaleTicket,
  ChatMessage,
  Order,
  ReceiptConfirmation,
} from '../types'

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
  const refundOpen = ref(false)
  const refundOrder = ref<Order | null>(null)
  const refundReason = ref('')
  const refundEligibility = ref<AfterSaleEligibility | null>(null)
  const refundLoading = ref(false)
  const refundSubmitting = ref(false)
  const refundError = ref<string | null>(null)
  const receiptOpen = ref(false)
  const receiptOrder = ref<Order | null>(null)
  const receiptSubmitting = ref(false)
  const receiptError = ref<string | null>(null)
  const receiptResult = ref<ReceiptConfirmation | null>(null)
  const selectedEligibility = ref<AfterSaleEligibility | null>(null)
  const selectedEligibilityLoading = ref(false)
  const selectedEligibilityError = ref<string | null>(null)
  let receiptRequest = new AbortController()
  let receiptVersion = 0
  let detailRequest = new AbortController()
  let refundRequest = new AbortController()
  let refundVersion = 0
  let client: ApiClient | null = null
  let context = new AbortController()
  let messageId = 0

  function resetContext() {
    resetReceiptConfirmation()
    detailRequest.abort()
    selectedEligibility.value = null
    selectedEligibilityLoading.value = false
    selectedEligibilityError.value = null
    closeRefundForm()
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
    if (!client || !connected.value || ordersBusy.value || receiptSubmitting.value) return
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
    if (busy.value || receiptSubmitting.value) return
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
      if (response.type === 'AFTER_SALE_FORM') await openRefundForm(response.orders[0] ?? null)
    } catch (failure) {
      report(failure, signal)
    } finally {
      if (!signal.aborted) busy.value = false
    }
  }

  async function selectOrder(order: Order) {
    if (
      !connected.value ||
      busy.value ||
      receiptSubmitting.value ||
      order.shopId !== Number(shopId.value)
    )
      return
    selectedOrder.value = order
    await sendMessage('咨询此订单')
  }

  function clearSelectedOrder() {
    selectedOrder.value = null
  }
  function clearConversation() {
    // An approval is a business write: clearing chat must not cancel its result
    // and leave the reviewer unsure whether the server accepted the decision.
    if (
      connecting.value ||
      reviewSubmitting.value ||
      refundSubmitting.value ||
      receiptSubmitting.value
    )
      return
    resetReceiptConfirmation()
    closeRefundForm()
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

  function sameOrder(left: Order | null | undefined, right: Order) {
    return left?.shopId === right.shopId && left.orderNo === right.orderNo
  }

  function resetReceiptConfirmation() {
    receiptRequest.abort()
    receiptRequest = new AbortController()
    receiptVersion++
    receiptOpen.value = false
    receiptOrder.value = null
    receiptSubmitting.value = false
    receiptError.value = null
    receiptResult.value = null
  }

  function closeReceiptConfirmation() {
    if (!receiptSubmitting.value) resetReceiptConfirmation()
  }

  function openReceiptConfirmation(order: Order) {
    if (
      !client ||
      !connected.value ||
      busy.value ||
      ordersBusy.value ||
      receiptSubmitting.value ||
      refundSubmitting.value ||
      order.shopId !== Number(shopId.value) ||
      order.status !== 'SHIPPED' ||
      order.receivedAt
    )
      return
    resetReceiptConfirmation()
    receiptOrder.value = order
    receiptOpen.value = true
  }

  async function confirmReceipt() {
    const order = receiptOrder.value
    if (
      !client ||
      !connected.value ||
      !receiptOpen.value ||
      receiptSubmitting.value ||
      receiptResult.value ||
      !order ||
      order.shopId !== Number(shopId.value) ||
      order.status !== 'SHIPPED' ||
      order.receivedAt
    )
      return
    const signal = AbortSignal.any([context.signal, receiptRequest.signal])
    const version = receiptVersion
    receiptSubmitting.value = true
    receiptError.value = null
    try {
      const result = await client.confirmReceipt(shopId.value, order.orderNo, signal)
      if (signal.aborted || version !== receiptVersion) return
      const updated = result.order
      orders.value = orders.value.map((item) => (sameOrder(item, updated) ? updated : item))
      if (sameOrder(selectedOrder.value, updated)) {
        selectedEligibility.value = result.eligibility
        selectedOrder.value = updated
      }
      messages.value = messages.value.map((message) => ({
        ...message,
        ...(message.orders
          ? { orders: message.orders.map((item) => (sameOrder(item, updated) ? updated : item)) }
          : {}),
      }))
      if (sameOrder(refundOrder.value, updated)) closeRefundForm()
      receiptOrder.value = updated
      receiptResult.value = result
    } catch (failure) {
      if (!signal.aborted && version === receiptVersion) {
        receiptError.value =
          failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)
            ? failure.message
            : '暂时无法确认收货结果，请先刷新订单核实状态，再决定是否重新操作。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted && version === receiptVersion) receiptSubmitting.value = false
    }
  }

  async function loadSelectedEligibility() {
    detailRequest.abort()
    detailRequest = new AbortController()
    const order = selectedOrder.value
    selectedEligibilityLoading.value = false
    selectedEligibilityError.value = null
    if (
      !client ||
      !connected.value ||
      !order?.receivedAt ||
      order.shopId !== Number(shopId.value)
    ) {
      selectedEligibility.value = null
      return
    }
    if (
      selectedEligibility.value?.shopId === order.shopId &&
      selectedEligibility.value.orderNo === order.orderNo &&
      selectedEligibility.value.receivedAt === order.receivedAt
    )
      return
    selectedEligibility.value = null
    const signal = AbortSignal.any([context.signal, detailRequest.signal])
    selectedEligibilityLoading.value = true
    try {
      const result = await client.afterSaleEligibility(shopId.value, order.orderNo, signal)
      if (!signal.aborted) selectedEligibility.value = result
    } catch (failure) {
      if (!signal.aborted) {
        selectedEligibilityError.value =
          failure instanceof ApiError
            ? failure.message
            : '暂时无法读取售后截止时间，请稍后重新查询订单。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) selectedEligibilityLoading.value = false
    }
  }

  watch(
    () => [
      selectedOrder.value?.shopId,
      selectedOrder.value?.orderNo,
      selectedOrder.value?.receivedAt,
    ],
    loadSelectedEligibility,
  )

  function closeRefundForm() {
    refundRequest.abort()
    refundRequest = new AbortController()
    refundVersion++
    refundOpen.value = false
    refundOrder.value = null
    refundReason.value = ''
    refundEligibility.value = null
    refundLoading.value = false
    refundSubmitting.value = false
    refundError.value = null
  }

  async function openRefundForm(order: Order | null = selectedOrder.value) {
    if (!client || !connected.value || refundSubmitting.value || receiptSubmitting.value) return
    closeRefundForm()
    refundOpen.value = true
    if (order?.shopId === Number(shopId.value)) await chooseRefundOrder(order)
    else if (!orders.value.length && !ordersBusy.value) await loadOrders()
  }

  async function chooseRefundOrder(order: Order) {
    if (
      !client ||
      !connected.value ||
      !refundOpen.value ||
      refundSubmitting.value ||
      order.shopId !== Number(shopId.value)
    )
      return
    refundRequest.abort()
    refundRequest = new AbortController()
    const signal = AbortSignal.any([context.signal, refundRequest.signal])
    const version = ++refundVersion
    refundOrder.value = order
    refundEligibility.value = null
    refundReason.value = ''
    refundError.value = null
    refundLoading.value = true
    try {
      const result = await client.afterSaleEligibility(shopId.value, order.orderNo, signal)
      if (!signal.aborted && version === refundVersion) refundEligibility.value = result
    } catch (failure) {
      if (!signal.aborted && version === refundVersion) {
        refundError.value =
          failure instanceof Error ? failure.message : '暂时无法核实售后期限，请稍后重试。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted && version === refundVersion) refundLoading.value = false
    }
  }

  async function submitRefund() {
    if (
      !client ||
      !connected.value ||
      !refundOpen.value ||
      refundLoading.value ||
      refundSubmitting.value
    )
      return
    const order = refundOrder.value
    const eligibility = refundEligibility.value
    if (!order || !eligibility?.canSubmit || order.shopId !== Number(shopId.value)) return
    const reason = refundReason.value.trim()
    if (!reason || reason.length > 500) {
      refundError.value = '请填写 1–500 字的退款原因。'
      return
    }
    const signal = AbortSignal.any([context.signal, refundRequest.signal])
    const version = refundVersion
    refundSubmitting.value = true
    refundError.value = null
    try {
      const result = await client.applyAfterSale(shopId.value, order.orderNo, reason, signal)
      if (signal.aborted || version !== refundVersion) return
      ownedAfterSales.value = [
        result,
        ...ownedAfterSales.value.filter((ticket) => ticket.id !== result.id),
      ].slice(0, 50)
      messages.value.push({
        id: ++messageId,
        role: 'assistant',
        createdAt: new Date().toISOString(),
        text:
          result.eligibility?.status === 'REQUIRES_REVIEW'
            ? `订单 ${result.orderNo} 的退款申请已提交，客服会核实收货时间和售后期限，请在售后进度中查看处理结果。`
            : `订单 ${result.orderNo} 的退款申请已提交，请在售后进度中查看审核结果。`,
      })
      closeRefundForm()
    } catch (failure) {
      if (!signal.aborted && version === refundVersion) {
        refundError.value =
          failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)
            ? failure.message
            : '提交结果暂时无法确认，请先查看售后进度，勿重复提交。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted && version === refundVersion) refundSubmitting.value = false
    }
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
    resetReceiptConfirmation()
    detailRequest.abort()
    closeRefundForm()
    context.abort()
    client = null
  })

  return {
    receiptOpen,
    receiptOrder,
    receiptSubmitting,
    receiptError,
    receiptResult,
    selectedEligibility,
    selectedEligibilityLoading,
    selectedEligibilityError,
    openReceiptConfirmation,
    closeReceiptConfirmation,
    confirmReceipt,
    refundOpen,
    refundOrder,
    refundReason,
    refundEligibility,
    refundLoading,
    refundSubmitting,
    refundError,
    openRefundForm,
    closeRefundForm,
    chooseRefundOrder,
    submitRefund,
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
