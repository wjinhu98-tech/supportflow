import { onScopeDispose, ref, watch } from 'vue'
import { ApiClient, ApiError, validShopId } from '../api/client'
import { useSupportChannel } from './useSupportChannel'
import type {
  AfterSaleEligibility,
  AfterSaleTicket,
  ChatMessage,
  Conversation,
  ShopOption,
  ConversationContextRef,
  StoredChatMessage,
  Order,
  OrderSelectionIntent,
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
  const activeConversation = ref<Conversation | null>(null)
  const shopOptions = ref<ShopOption[]>([])
  const shopDirectoryError = ref<string | null>(null)
  const deletingConversation = ref(false)
  const conversationContext = ref<ConversationContextRef | null>(null)
  const contextResetPending = ref(false)
  const contextExpired = ref(false)
  let contextExpiryTimer: ReturnType<typeof setTimeout> | null = null
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
  const orderDeletionOpen = ref(false)
  const orderDeletionOrder = ref<Order | null>(null)
  const orderDeletionSubmitting = ref(false)
  const orderDeletionError = ref<string | null>(null)
  const orderDeletionNotice = ref<string | null>(null)
  const deletedOrderKeys = ref(new Set<string>())
  let orderDeletionRequest = new AbortController()
  let orderDeletionVersion = 0
  const selectedEligibility = ref<AfterSaleEligibility | null>(null)
  const selectedEligibilityLoading = ref(false)
  const selectedEligibilityError = ref<string | null>(null)
  let receiptRequest = new AbortController()
  let receiptVersion = 0
  let detailRequest = new AbortController()
  let refundRequest = new AbortController()
  let refundVersion = 0
  let client: ApiClient | null = null
  let connectingClient: ApiClient | null = null
  let context = new AbortController()
  let messageId = 0
  const humanVisible = ref(true)
  const humanRequesting = ref(false)
  const humanRequestUncertain = ref(false)
  let humanRequestId: string | null = null
  const human = useSupportChannel({
    scope: () =>
      `${connected.value}/${username.value}/${shopId.value}/${activeConversation.value?.id ?? ''}`,
    enabled: () => !!client && connected.value && !!activeConversation.value,
    visible: () => humanVisible.value,
    read: (signal, beforeId) =>
      beforeId
        ? client!.supportMessages(shopId.value, activeConversation.value!.id, beforeId, signal)
        : client!.supportSnapshot(shopId.value, activeConversation.value!.id, signal),
    send: (sessionId, id, text, signal) =>
      client!.sendSupportMessage(
        shopId.value,
        activeConversation.value!.id,
        sessionId,
        id,
        text,
        signal,
      ),
    close: (sessionId, signal) =>
      client!.closeSupport(shopId.value, activeConversation.value!.id, sessionId, signal),
    canWrite: () => true,
    onError: (failure, signal) => {
      if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
    },
    onClosed: () => {
      selectedOrder.value = null
      resetConversationContext()
    },
  })
  watch(
    () =>
      `${connected.value}/${username.value}/${shopId.value}/${activeConversation.value?.id ?? ''}`,
    () => {
      humanRequestId = null
      humanRequesting.value = false
      humanRequestUncertain.value = false
    },
    { flush: 'sync' },
  )
  watch(
    human.active,
    (active) => {
      if (active) {
        humanRequestUncertain.value = false
        humanRequestId = null
      }
    },
    { flush: 'sync' },
  )

  async function requestHumanSupport() {
    if (
      !client ||
      !connected.value ||
      busy.value ||
      humanRequesting.value ||
      human.submitting.value ||
      conversationsLoading.value ||
      historyLoading.value ||
      businessWriteInProgress()
    )
      return
    const signal = context.signal
    humanRequesting.value = true
    try {
      if (!activeConversation.value) {
        const conversation = await client.openShopWindow(shopId.value, signal)
        if (signal.aborted) return
        activeConversation.value = conversation
        humanRequesting.value = true
      }
      await human.refresh()
      if (signal.aborted || !human.ready.value || human.active.value) return
      humanRequestId ??= crypto.randomUUID()
      const result = await client.requestSupport(
        shopId.value,
        activeConversation.value!.id,
        humanRequestId,
        signal,
      )
      if (signal.aborted) return
      human.accept(result)
      humanRequestId = null
      humanRequestUncertain.value = false
      selectedOrder.value = null
      resetConversationContext()
      human.error.value = null
    } catch (failure) {
      if (!signal.aborted) {
        humanRequestUncertain.value = true
        human.error.value = '联系人工的结果暂无法确认，请刷新记录核实。再次联系会核实同一次请求。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
        if (failure instanceof ApiError && failure.status === 409) await human.refresh()
      }
    } finally {
      if (!signal.aborted) humanRequesting.value = false
    }
  }

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
    activeConversation.value = null
    nextMessageBeforeId.value = null
    conversationsLoading.value = false
    historyLoading.value = false
    conversationError.value = null
    deletingConversation.value = false
    resetReceiptConfirmation()
    resetOrderDeletion()
    orderDeletionNotice.value = null
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

  function resetAccount() {
    client?.dispose()
    connectingClient?.dispose()
    resetContext()
    client = null
    connectingClient = null
    username.value = ''
    connected.value = false
    connecting.value = false
    loginError.value = null
    shopOptions.value = []
    shopDirectoryError.value = null
    deletedOrderKeys.value = new Set()
  }

  function disconnect() {
    const previous = client ?? connectingClient
    const logout = previous?.logout()
    resetAccount()
    const signal = context.signal
    void logout?.catch(() => {
      if (!signal.aborted && !connected.value)
        loginError.value = '页面数据已清除，但退出登录未能确认。请重新登录，或关闭此页面。'
    })
  }

  function report(failure: unknown, signal: AbortSignal) {
    if (signal.aborted) return
    const text = failure instanceof ApiError ? failure.message : '操作失败，请稍后重试。'
    if (failure instanceof ApiError && failure.status === 401) {
      resetAccount()
      loginError.value = text
    }
    error.value = text
  }

  async function connect(account: string, password: string): Promise<boolean> {
    if (connecting.value) return false
    disconnect()
    connecting.value = true
    const signal = context.signal
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
      if (identity.accountType !== 'BUYER') {
        throw new ApiError('这是商家员工账号，请使用商家管理端登录。', 0, 'WRONG_ACCOUNT_TYPE')
      }
      client = candidate
      connectingClient = null
      username.value = identity.username
      connected.value = true
      connecting.value = false
      await loadShops()
      if (signal.aborted) return false
      await loadOrders()
      if (!signal.aborted) await loadConversations()
      return connected.value
    } catch (failure) {
      void candidate?.logout().catch(() => undefined)
      if (connectingClient === candidate) connectingClient = null
      if (!signal.aborted) {
        loginError.value = failure instanceof Error ? failure.message : '连接失败，请检查账号信息。'
      }
      return false
    } finally {
      if (!signal.aborted) connecting.value = false
    }
  }

  async function loadOrders(loadMore = false) {
    if (
      !client ||
      !connected.value ||
      ordersBusy.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value
    )
      return
    if (loadMore && nextAfterId.value === null) return
    const signal = context.signal
    const cursor = loadMore ? nextAfterId.value : null
    const activeShop = shopId.value
    ordersBusy.value = true
    error.value = null
    try {
      const response = await client.shopOrders(activeShop, cursor, signal)
      if (signal.aborted) return
      if (response.nextAfterId !== null && cursor !== null && response.nextAfterId <= cursor) {
        throw new ApiError('分页游标没有前进，请刷新订单后重试。')
      }
      const items = loadMore ? [...orders.value, ...response.items] : response.items
      orders.value = Array.from(
        new Map(
          items.filter((order) => !isOrderDeleted(order)).map((order) => [order.orderNo, order]),
        ).values(),
      )
      nextAfterId.value = response.nextAfterId
    } catch (failure) {
      report(failure, signal)
    } finally {
      if (!signal.aborted) ordersBusy.value = false
    }
  }

  async function changeShop(value: string) {
    if (deletingConversation.value) return
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
    activeConversation.value = conversation
  }

  async function loadShops() {
    if (!client || !connected.value) return
    const signal = context.signal
    try {
      const options = await client.shops(signal)
      if (!signal.aborted) shopOptions.value = options
    } catch (failure) {
      if (!signal.aborted) {
        shopDirectoryError.value = '暂时无法读取店铺列表，也可以输入店铺 ID 进入。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    }
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
            ...(!historical &&
            message.response.type === 'ORDER_SELECTION' &&
            message.response.selectionIntent
              ? { selectionIntent: message.response.selectionIntent }
              : {}),
          }
        : {}),
    }
  }

  function businessWriteInProgress() {
    return (
      refundSubmitting.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value ||
      deletingConversation.value
    )
  }

  async function loadConversations() {
    if (!client || !connected.value || conversationsLoading.value || orderDeletionSubmitting.value)
      return
    const signal = context.signal
    conversationsLoading.value = true
    conversationError.value = null
    try {
      const result = await client.openShopWindow(shopId.value, signal)
      if (signal.aborted) return
      activeConversation.value = result
      conversationsLoading.value = false
      await loadEarlierMessages(false)
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

  async function loadEarlierMessages(loadEarlier = true) {
    if (
      !client ||
      !connected.value ||
      historyLoading.value ||
      busy.value ||
      orderDeletionSubmitting.value ||
      deletingConversation.value
    )
      return
    if (loadEarlier && nextMessageBeforeId.value === null) return
    const signal = AbortSignal.any([context.signal, conversationRequest.signal])
    const cursor = loadEarlier ? nextMessageBeforeId.value : null
    historyLoading.value = true
    conversationError.value = null
    try {
      const result = await client.shopWindowMessages(shopId.value, cursor, signal)
      if (signal.aborted) return
      if (cursor && result.nextBeforeId === cursor)
        throw new ApiError('咨询记录分页未前进，请刷新后核实。')
      const restored = result.messages.map((message) => restoreMessage(message, true))
      const items = loadEarlier ? [...restored, ...messages.value] : restored
      messages.value = Array.from(new Map(items.map((item) => [item.id, item])).values())
      activeConversation.value = result.conversation
      nextMessageBeforeId.value = result.nextBeforeId
      if (!loadEarlier && !contextResetPending.value) {
        setConversationContext(result.context)
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
    if (message.length > (human.active.value ? 2000 : 1000)) {
      error.value = '消息不能超过 1000 个字符。'
      return
    }
    if (!client || !connected.value) {
      error.value = '请先连接账号，再咨询订单。'
      return
    }
    if (
      busy.value ||
      humanRequesting.value ||
      human.submitting.value ||
      historyLoading.value ||
      conversationsLoading.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value ||
      deletingConversation.value
    )
      return
    const signal = AbortSignal.any([context.signal, conversationRequest.signal])
    const activeShop = shopId.value
    const activeClient = client
    if (activeConversation.value) {
      if (!human.ready.value) await human.refresh()
      if (signal.aborted || client !== activeClient) return
      if (!human.ready.value || humanRequestUncertain.value) {
        human.error.value = '请先刷新人工服务状态，确认后再发送消息。'
        return
      }
      if (human.active.value) {
        if (orderNo) {
          human.error.value = '人工服务期间，请直接说明订单和问题。'
          return
        }
        await human.send(message)
        return
      }
    }
    const selected = orderNo ?? selectedOrder.value?.orderNo
    if (selected && deletedOrderKeys.value.has(`${activeShop}/${selected}`)) {
      error.value = '这笔订单已从你的订单记录中删除。'
      return
    }
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
        conversation = await activeClient.openShopWindow(activeShop, signal)
        if (signal.aborted) return
        upsertConversation(conversation)
      }
      const result = await activeClient.sendConversationMessage(
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
        response.type === 'ORDER_SELECTION' ||
        (response.context &&
          (response.context.kind !== 'ORDER' ||
            selectedOrder.value?.orderNo !== response.context.orderNo))
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
        if (failure instanceof ApiError && failure.status === 409) await human.refresh()
        messages.value = messages.value.filter((item) => item.id !== clientMessageId)
        if (
          failure instanceof ApiError &&
          failure.status === 503 &&
          failure.code === 'MODEL_UNAVAILABLE'
        )
          error.value =
            '智能服务暂时不可用。请稍后查看咨询记录，或在订单页直接查看订单，不要重复发送。'
        else if (
          !(failure instanceof ApiError) ||
          ![400, 403, 404, 409, 429].includes(failure.status)
        )
          error.value = '消息结果暂时无法确认，请刷新咨询记录核实，不要重复发送。'
      }
    } finally {
      if (!signal.aborted) busy.value = false
    }
  }

  async function selectOrder(order: Order, selectionIntent: OrderSelectionIntent | null = null) {
    if (
      !connected.value ||
      busy.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value ||
      isOrderDeleted(order) ||
      order.shopId !== Number(shopId.value)
    )
      return
    if (human.active.value) {
      if (!client || ordersBusy.value) return
      const signal = context.signal
      const sessionId = human.session.value?.id
      ordersBusy.value = true
      try {
        const current = await client.getShopOrder(shopId.value, order.orderNo, signal)
        if (!signal.aborted && human.active.value && human.session.value?.id === sessionId)
          selectedOrder.value = current
      } catch (failure) {
        report(failure, signal)
      } finally {
        if (!signal.aborted) ordersBusy.value = false
      }
      return
    }
    // Re-query ownership and state before treating a historical snapshot as current.
    selectedOrder.value = null
    // A candidate can continue only these two reads; never replay the user's original text.
    await sendMessage(selectionIntent === 'LOGISTICS' ? '查物流' : '咨询此订单', order.orderNo)
  }

  function clearSelectedOrder() {
    clearConversationTopic()
  }
  async function deleteCurrentConversation(): Promise<boolean> {
    if (
      !client ||
      !connected.value ||
      !activeConversation.value ||
      !human.ready.value ||
      human.active.value ||
      humanRequesting.value ||
      humanRequestUncertain.value ||
      human.submitting.value ||
      busy.value ||
      historyLoading.value ||
      conversationsLoading.value ||
      businessWriteInProgress()
    )
      return false
    const signal = context.signal
    const id = activeConversation.value.id
    deletingConversation.value = true
    error.value = null
    try {
      await client.deleteShopWindow(shopId.value, id, signal)
      if (signal.aborted) return false
      deletingConversation.value = false
      clearConversation()
      return true
    } catch (failure) {
      if (!signal.aborted) {
        report(failure, signal)
        if (failure instanceof ApiError && failure.status === 409) await human.refresh()
        if (!(failure instanceof ApiError) || ![400, 403, 404, 409].includes(failure.status))
          error.value = '删除结果暂时无法确认，请刷新对话核实，不会自动重复删除。'
      }
      return false
    } finally {
      if (!signal.aborted) deletingConversation.value = false
    }
  }
  function clearConversation() {
    // A pending refund or receipt confirmation must not lose its result.
    if (
      connecting.value ||
      refundSubmitting.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value
    )
      return
    resetConversationContext()
    resetReceiptConfirmation()
    resetOrderDeletion()
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

  function isOrderDeleted(order: Order): boolean {
    return deletedOrderKeys.value.has(`${order.shopId}/${order.orderNo}`)
  }

  function orderDeletionBlocked(order: Order): boolean {
    return ownedAfterSales.value.some(
      (ticket) =>
        ticket.shopId === order.shopId &&
        ticket.orderNo === order.orderNo &&
        (ticket.status === 'PENDING_REVIEW' ||
          (ticket.status === 'APPROVED' && ticket.execution?.status !== 'SUCCEEDED')),
    )
  }

  function resetOrderDeletion() {
    orderDeletionRequest.abort()
    orderDeletionRequest = new AbortController()
    orderDeletionVersion++
    orderDeletionOpen.value = false
    orderDeletionOrder.value = null
    orderDeletionSubmitting.value = false
    orderDeletionError.value = null
  }

  function closeOrderDeletion() {
    if (!orderDeletionSubmitting.value) resetOrderDeletion()
  }

  function openOrderDeletion(order: Order) {
    if (
      !client ||
      !connected.value ||
      busy.value ||
      ordersBusy.value ||
      historyLoading.value ||
      conversationsLoading.value ||
      businessWriteInProgress() ||
      order.shopId !== Number(shopId.value) ||
      !['COMPLETED', 'CANCELLED'].includes(order.status) ||
      isOrderDeleted(order) ||
      orderDeletionBlocked(order)
    )
      return
    resetOrderDeletion()
    orderDeletionNotice.value = null
    orderDeletionOrder.value = order
    orderDeletionOpen.value = true
  }

  async function confirmOrderDeletion() {
    const order = orderDeletionOrder.value
    if (
      !client ||
      !connected.value ||
      !orderDeletionOpen.value ||
      orderDeletionSubmitting.value ||
      busy.value ||
      ordersBusy.value ||
      historyLoading.value ||
      conversationsLoading.value ||
      refundSubmitting.value ||
      receiptSubmitting.value ||
      deletingConversation.value ||
      !order ||
      order.shopId !== Number(shopId.value) ||
      !['COMPLETED', 'CANCELLED'].includes(order.status) ||
      isOrderDeleted(order) ||
      orderDeletionBlocked(order)
    )
      return
    const signal = AbortSignal.any([context.signal, orderDeletionRequest.signal])
    const version = orderDeletionVersion
    orderDeletionSubmitting.value = true
    orderDeletionError.value = null
    try {
      await client.deleteOrder(shopId.value, order.orderNo, signal)
      if (signal.aborted || version !== orderDeletionVersion) return
      deletedOrderKeys.value = new Set([
        ...deletedOrderKeys.value,
        `${order.shopId}/${order.orderNo}`,
      ])
      orders.value = orders.value.filter((item) => !sameOrder(item, order))
      if (sameOrder(selectedOrder.value, order)) selectedOrder.value = null
      if (
        conversationContext.value?.kind === 'ORDER' &&
        conversationContext.value.orderNo === order.orderNo
      ) {
        setConversationContext(null)
        contextResetPending.value = true
      }
      if (sameOrder(refundOrder.value, order)) closeRefundForm()
      if (sameOrder(receiptOrder.value, order)) resetReceiptConfirmation()
      messages.value = messages.value.map((message) => {
        if (message.historical || !message.orders?.some((item) => sameOrder(item, order)))
          return message
        return message.type === 'ORDER_SELECTION'
          ? { ...message, orders: message.orders.filter((item) => !sameOrder(item, order)) }
          : { ...message, historical: true }
      })
      resetOrderDeletion()
      orderDeletionNotice.value = '订单已从你的订单记录中删除。'
    } catch (failure) {
      if (!signal.aborted && version === orderDeletionVersion) {
        orderDeletionError.value =
          failure instanceof ApiError && [400, 403, 404, 409].includes(failure.status)
            ? failure.message
            : '删除结果暂时无法确认，请先刷新订单核实，不会自动重复提交。'
        if (failure instanceof ApiError && failure.status === 401) report(failure, signal)
      }
    } finally {
      if (!signal.aborted && version === orderDeletionVersion) orderDeletionSubmitting.value = false
    }
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
      orderDeletionSubmitting.value ||
      isOrderDeleted(order) ||
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
      orderDeletionSubmitting.value ||
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
    if (
      !client ||
      !connected.value ||
      refundSubmitting.value ||
      receiptSubmitting.value ||
      orderDeletionSubmitting.value
    )
      return
    if (order && isOrderDeleted(order)) return
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
      orderDeletionSubmitting.value ||
      isOrderDeleted(order) ||
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
      orderDeletionSubmitting.value ||
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
    client?.dispose()
    void connectingClient?.logout().catch(() => undefined)
    if (contextExpiryTimer !== null) clearTimeout(contextExpiryTimer)
    conversationRequest.abort()
    resetReceiptConfirmation()
    resetOrderDeletion()
    detailRequest.abort()
    closeRefundForm()
    context.abort()
    client = null
  })

  return {
    humanSession: human.session,
    humanMessages: human.messages,
    humanNextBeforeId: human.nextBeforeId,
    humanLoading: human.loading,
    humanSubmitting: human.submitting,
    humanReady: human.ready,
    humanError: human.error,
    humanActive: human.active,
    humanUncertainMessage: human.uncertainMessage,
    humanUncertainClose: human.uncertainClose,
    humanVisible,
    humanRequesting,
    humanRequestUncertain,
    refreshHumanSupport: human.refresh,
    retryHumanMessage: () => human.send('', true),
    closeHumanSupport: human.close,
    requestHumanSupport,
    activeConversation,
    shopOptions,
    shopDirectoryError,
    deletingConversation,
    deleteCurrentConversation,
    conversationContext,
    contextResetPending,
    contextExpired,
    clearConversationTopic,
    nextMessageBeforeId,
    conversationsLoading,
    historyLoading,
    conversationError,
    loadConversations,
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
    orderDeletionOpen,
    orderDeletionOrder,
    orderDeletionSubmitting,
    orderDeletionError,
    orderDeletionNotice,
    openOrderDeletion,
    closeOrderDeletion,
    confirmOrderDeletion,
    isOrderDeleted,
    orderDeletionBlocked,
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
