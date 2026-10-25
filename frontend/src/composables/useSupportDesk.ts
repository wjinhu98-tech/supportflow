import { onScopeDispose, ref, watch } from 'vue'
import { ApiClient, ApiError, validShopId } from '../api/client'
import type {
  AfterSaleEligibility,
  AfterSaleTicket,
  ChatMessage,
  Conversation,
  ConversationContextRef,
  StoredChatMessage,
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
  const conversations = ref<Conversation[]>([])
  const activeConversation = ref<Conversation | null>(null)
  const conversationContext = ref<ConversationContextRef | null>(null)
  const contextResetPending = ref(false)
  const contextExpired = ref(false)
  let contextExpiryTimer: ReturnType<typeof setTimeout> | null = null
  const nextConversationBeforeId = ref<string | null>(null)
  const nextMessageBeforeId = ref<string | null>(null)
  const conversationsLoading = ref(false)
  const historyLoading = ref(false)
  const conversationError = ref<string | null>(null)
  let conversationRequest = new AbortController()
  const error = ref<string | null>(
    validShopId(initialShop) ? null : '链接中的店铺 ID 无效，已回到店铺 100。',
  )
  const loginError = ref<string | null>(null)
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

  function expireConversationContext() {
    contextExpired.value = true
    if (
      conversationContext.value?.kind === 'ORDER' &&
      selectedOrder.value?.orderNo === conversationContext.value.orderNo
    )
      selectedOrder.value = null
  }

  function setConversationContext(value: ConversationContextRef | null | undefined) {
    if (contextExpiryTimer !== null) clearTimeout(contextExpiryTimer)
    contextExpiryTimer = null
    conversationContext.value = value ? { ...value, sourceIds: [...value.sourceIds] } : null
    contextExpired.value = false
    if (!value || value.kind === 'NONE' || !value.expiresAt) return
    const remaining = Date.parse(value.expiresAt) - Date.now()
    if (remaining <= 0) {
      expireConversationContext()
      return
    }
    contextExpiryTimer = setTimeout(
      () => {
        expireConversationContext()
        contextExpiryTimer = null
      },
      Math.min(remaining, 30 * 60 * 1000),
    )
  }

  function resetConversationContext() {
    setConversationContext(null)
    contextResetPending.value = false
  }

  function clearConversationTopic() {
    if (
      !connected.value ||
      busy.value ||
      historyLoading.value ||
      conversationsLoading.value ||
      businessWriteInProgress()
    )
      return
    selectedOrder.value = null
    setConversationContext(null)
    contextResetPending.value = true
  }

  function resetContext() {
    resetConversationContext()
    conversationRequest.abort()
    conversationRequest = new AbortController()
    conversations.value = []
    activeConversation.value = null
    nextConversationBeforeId.value = null
    nextMessageBeforeId.value = null
    conversationsLoading.value = false
    historyLoading.value = false
    conversationError.value = null
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
      const identity = await candidate.currentAccount(signal)
      if (signal.aborted) return false
      if (identity.accountType !== 'BUYER') {
        throw new ApiError('这是商家员工账号，请使用商家管理端登录。', 0, 'WRONG_ACCOUNT_TYPE')
      }
      client = candidate
      username.value = identity.username
      connected.value = true
      connecting.value = false
      await loadOrders()
      if (!signal.aborted) await loadConversations()
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
    const signal = context.signal
    if (connected.value) {
      await loadOrders()
      if (!signal.aborted) await loadConversations()
    }
  }

  function upsertConversation(conversation: Conversation) {
    // The list is paged by immutable creation order, not latest activity.
    conversations.value = conversations.value.some((item) => item.id === conversation.id)
      ? conversations.value.map((item) => (item.id === conversation.id ? conversation : item))
      : [conversation, ...conversations.value]
    activeConversation.value = conversation
  }

  function restoreMessage(message: StoredChatMessage, historical: boolean): ChatMessage {
    return {
      id: message.id,
      role: message.role === 'USER' ? 'user' : 'assistant',
      text: message.text,
      createdAt: message.createdAt,
      historical,
      ...(message.response
        ? {
            type: message.response.type,
            orders: message.response.orders,
            knowledge: message.response.knowledge ?? null,
            context: message.response.context ?? null,
          }
        : {}),
    }
  }

  function businessWriteInProgress() {
    return refundSubmitting.value || receiptSubmitting.value
  }

  async function loadConversations(loadMore = false) {
    if (!client || !connected.value || conversationsLoading.value) return
    if (loadMore && !nextConversationBeforeId.value) return
    const signal = context.signal
    const cursor = loadMore ? nextConversationBeforeId.value : null
    conversationsLoading.value = true
    conversationError.value = null
    try {
      const result = await client.conversations(shopId.value, cursor, signal)
      if (signal.aborted) return
      if (cursor && result.nextBeforeId === cursor)
        throw new ApiError('咨询列表分页未前进，请刷新后核实。')
      const items = loadMore
        ? [...conversations.value, ...result.conversations]
        : result.conversations
      conversations.value = Array.from(new Map(items.map((item) => [item.id, item])).values())
      nextConversationBeforeId.value = result.nextBeforeId
      conversationsLoading.value = false
      if (
        !loadMore &&
        !activeConversation.value &&
        !messages.value.length &&
        result.conversations[0]
      )
        await selectConversation(result.conversations[0].id)
    } catch (failure) {
      if (!signal.aborted) {
        conversationError.value =
          failure instanceof ApiError ? failure.message : '暂时无法读取历史咨询，请稍后刷新。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) conversationsLoading.value = false
    }
  }

  async function selectConversation(id: string) {
    if (!client || !connected.value || businessWriteInProgress()) return
    const conversation = conversations.value.find((item) => item.id === id)
    if (!conversation || conversation.shopId !== Number(shopId.value)) return
    clearConversation()
    activeConversation.value = conversation
    await loadEarlierMessages(false)
  }

  async function loadEarlierMessages(loadEarlier = true) {
    if (
      !client ||
      !connected.value ||
      !activeConversation.value ||
      historyLoading.value ||
      busy.value
    )
      return
    if (loadEarlier && nextMessageBeforeId.value === null) return
    const signal = AbortSignal.any([context.signal, conversationRequest.signal])
    const id = activeConversation.value.id
    const cursor = loadEarlier ? nextMessageBeforeId.value : null
    historyLoading.value = true
    conversationError.value = null
    try {
      const result = await client.conversationMessages(shopId.value, id, cursor, signal)
      if (signal.aborted) return
      if (cursor && result.nextBeforeId === cursor)
        throw new ApiError('咨询记录分页未前进，请刷新后核实。')
      const restored = result.messages.map((message) => restoreMessage(message, true))
      const items = loadEarlier ? [...restored, ...messages.value] : restored
      messages.value = Array.from(new Map(items.map((item) => [item.id, item])).values())
      activeConversation.value = result.conversation
      nextMessageBeforeId.value = result.nextBeforeId
      if (!loadEarlier && !contextResetPending.value) {
        const latest = result.messages.filter((message) => message.role === 'ASSISTANT').at(-1)
        setConversationContext(latest?.response?.context)
      }
      // Restoring replies never selects stale orders, reopens forms, or replays tools.
    } catch (failure) {
      if (!signal.aborted) {
        conversationError.value =
          failure instanceof ApiError ? failure.message : '暂时无法读取咨询记录，请稍后刷新。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted) historyLoading.value = false
    }
  }

  async function sendMessage(text: string, orderNo?: string) {
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
    if (busy.value || historyLoading.value || conversationsLoading.value || receiptSubmitting.value)
      return
    const signal = AbortSignal.any([context.signal, conversationRequest.signal])
    const activeShop = shopId.value
    const selected = orderNo ?? selectedOrder.value?.orderNo
    const resetTopic = contextResetPending.value
    const clientMessageId = crypto.randomUUID()
    busy.value = true
    error.value = null
    messages.value.push({
      id: clientMessageId,
      role: 'user',
      text: message,
      createdAt: new Date().toISOString(),
      pending: true,
    })
    try {
      let conversation = activeConversation.value
      if (!conversation) {
        conversation = await client.createConversation(activeShop, signal)
        if (signal.aborted) return
        upsertConversation(conversation)
      }
      const result = await client.sendConversationMessage(
        activeShop,
        conversation.id,
        clientMessageId,
        message,
        selected,
        signal,
        resetTopic,
      )
      if (signal.aborted) return
      messages.value = [
        ...messages.value.filter((item) => item.id !== clientMessageId),
        ...result.messages.map((item) => restoreMessage(item, false)),
      ]
      upsertConversation(result.conversation)
      const response = result.messages.find((item) => item.role === 'ASSISTANT')!.response!
      if (
        response.context &&
        (response.context.kind !== 'ORDER' ||
          selectedOrder.value?.orderNo !== response.context.orderNo)
      )
        selectedOrder.value = null
      setConversationContext(response.context)
      contextResetPending.value = false
      if (response.type === 'ORDER_DETAILS' && response.orders[0] && !contextExpired.value)
        selectedOrder.value = response.orders[0]
      if (response.type === 'AFTER_SALE_FORM') await openRefundForm(response.orders[0] ?? null)
    } catch (failure) {
      report(failure, signal)
      if (!signal.aborted && connected.value) {
        messages.value = messages.value.filter((item) => item.id !== clientMessageId)
        if (!(failure instanceof ApiError) || ![400, 403, 404, 409, 429].includes(failure.status))
          error.value = '消息结果暂时无法确认，请刷新咨询记录核实，不要重复发送。'
      }
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
    // Re-query ownership and state before treating a historical snapshot as current.
    selectedOrder.value = null
    await sendMessage('咨询此订单', order.orderNo)
  }

  function clearSelectedOrder() {
    clearConversationTopic()
  }
  function clearConversation() {
    // A pending refund or receipt confirmation must not lose its result.
    if (connecting.value || refundSubmitting.value || receiptSubmitting.value) return
    resetConversationContext()
    resetReceiptConfirmation()
    closeRefundForm()
    detailRequest.abort()
    selectedEligibility.value = null
    selectedEligibilityLoading.value = false
    selectedEligibilityError.value = null
    conversationRequest.abort()
    conversationRequest = new AbortController()
    activeConversation.value = null
    nextMessageBeforeId.value = null
    conversationsLoading.value = false
    historyLoading.value = false
    conversationError.value = null
    // Abort old requests before removing their conversation, then preserve the current order list.
    context.abort()
    context = new AbortController()
    busy.value = false
    ordersBusy.value = false
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
        ...(message.orders && !message.historical
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
    if (contextExpiryTimer !== null) clearTimeout(contextExpiryTimer)
    conversationRequest.abort()
    resetReceiptConfirmation()
    detailRequest.abort()
    closeRefundForm()
    context.abort()
    client = null
  })

  return {
    conversations,
    activeConversation,
    conversationContext,
    contextResetPending,
    contextExpired,
    clearConversationTopic,
    nextConversationBeforeId,
    nextMessageBeforeId,
    conversationsLoading,
    historyLoading,
    conversationError,
    loadConversations,
    selectConversation,
    loadEarlierMessages,
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
