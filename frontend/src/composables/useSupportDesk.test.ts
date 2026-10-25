import { effectScope, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AfterSaleEligibility,
  AfterSaleTicket,
  ChatResponse,
  Conversation,
  ConversationContextRef,
  ConversationMessages,
  ConversationMessagePage,
  StoredChatMessage,
  Order,
  ReceiptConfirmation,
} from '../types'

const mocks = vi.hoisted(() => ({
  authenticate: vi.fn(),
  currentAccount: vi.fn(),
  chat: vi.fn(),
  reply: vi.fn(),
  conversations: vi.fn(),
  createConversation: vi.fn(),
  conversationMessages: vi.fn(),
  sendConversationMessage: vi.fn(),
  pendingReviews: vi.fn(),
  decideReview: vi.fn(),
  reviewHistory: vi.fn(),
  retryExecution: vi.fn(),
  ownedAfterSales: vi.fn(),
  afterSaleEligibility: vi.fn(),
  applyAfterSale: vi.fn(),
  confirmReceipt: vi.fn(),
}))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    ApiClient: class {
      authenticate = mocks.authenticate
      currentAccount = mocks.currentAccount
      chat = mocks.chat
      conversations = mocks.conversations
      createConversation = mocks.createConversation
      conversationMessages = mocks.conversationMessages
      sendConversationMessage = mocks.sendConversationMessage
      pendingReviews = mocks.pendingReviews
      decideReview = mocks.decideReview
      reviewHistory = mocks.reviewHistory
      retryExecution = mocks.retryExecution
      ownedAfterSales = mocks.ownedAfterSales
      afterSaleEligibility = mocks.afterSaleEligibility
      applyAfterSale = mocks.applyAfterSale
      confirmReceipt = mocks.confirmReceipt
    },
  }
})
import { ApiError } from '../api/client'
import { useSupportDesk } from './useSupportDesk'

const order: Order = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'SHIPPED',
  statusDescription: '已发货',
  totalAmount: 199.9,
  currency: 'CNY',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
}
const selection = (orders = [order], nextAfterId: number | null = null): ChatResponse => ({
  reply: '请选择订单',
  type: 'ORDER_SELECTION',
  orders,
  nextAfterId,
  knowledge: null,
})
const textReply: ChatResponse = {
  reply: '你好',
  type: 'TEXT',
  orders: [],
  nextAfterId: null,
  knowledge: null,
}
const conversation: Conversation = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
}
function storedPair(text: string, response: ChatResponse = textReply): StoredChatMessage[] {
  return [
    {
      id: crypto.randomUUID(),
      role: 'USER',
      text,
      createdAt: conversation.createdAt,
      response: null,
    },
    {
      id: crypto.randomUUID(),
      role: 'ASSISTANT',
      text: response.reply,
      createdAt: conversation.createdAt,
      response,
    },
  ]
}
const ticket: AfterSaleTicket = {
  id: 'a5be2bac-e14a-4810-959e-c1b7f3be24e7',
  shopId: 100,
  orderNo: 'SF10001',
  reason: '商品破损',
  status: 'PENDING_REVIEW',
  reviewer: null,
  reviewNote: null,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
  execution: null,
  eligibility: null,
}
const eligible: AfterSaleEligibility = {
  shopId: 100,
  orderNo: 'SF10001',
  status: 'ELIGIBLE',
  canSubmit: true,
  message: '订单在售后期限内，请填写退款原因。',
  receivedAt: '2026-10-20T00:00:00Z',
  deadlineAt: '2026-11-04T00:00:00Z',
  checkedAt: '2026-10-21T00:00:00Z',
  windowDays: 15,
}
const reviewedTicket: AfterSaleTicket = {
  ...ticket,
  status: 'APPROVED',
  reviewer: 'reviewer',
  reviewNote: '核对订单后批准',
}
const scopes: ReturnType<typeof effectScope>[] = []
const createDesk = () => {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(useSupportDesk)!
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

beforeEach(() => {
  window.history.replaceState(null, '', '/?shopId=100')
  localStorage.clear()
  sessionStorage.clear()
  mocks.authenticate.mockReset().mockResolvedValue(undefined)
  mocks.currentAccount.mockReset().mockResolvedValue({
    username: 'buyer',
    accountType: 'BUYER',
    capabilities: { reviewAfterSales: false, manageKnowledge: false },
  })
  mocks.chat.mockReset().mockResolvedValue(selection())
  mocks.reply.mockReset().mockResolvedValue(textReply)
  mocks.conversations.mockReset().mockResolvedValue({ conversations: [], nextBeforeId: null })
  mocks.createConversation
    .mockReset()
    .mockImplementation(async (shopId: string) => ({ ...conversation, shopId: Number(shopId) }))
  mocks.conversationMessages
    .mockReset()
    .mockResolvedValue({ conversation, messages: [], nextBeforeId: null })
  mocks.sendConversationMessage
    .mockReset()
    .mockImplementation(
      async (
        shopId: string,
        id: string,
        _clientMessageId: string,
        text: string,
        orderNo?: string,
        signal?: AbortSignal,
      ) => ({
        conversation: { ...conversation, id, shopId: Number(shopId) },
        messages: storedPair(text, await mocks.reply(shopId, text, orderNo, signal)),
      }),
    )
  mocks.pendingReviews.mockReset().mockResolvedValue([ticket])
  mocks.decideReview.mockReset().mockResolvedValue(reviewedTicket)
  mocks.reviewHistory.mockReset().mockResolvedValue([reviewedTicket])
  mocks.retryExecution.mockReset()
  mocks.ownedAfterSales.mockReset().mockResolvedValue([reviewedTicket])
  mocks.afterSaleEligibility.mockReset().mockResolvedValue(eligible)
  mocks.applyAfterSale.mockReset().mockResolvedValue({ ...ticket, eligibility: eligible })
  mocks.confirmReceipt.mockReset().mockResolvedValue({
    order: { ...order, status: 'COMPLETED', receivedAt: eligible.receivedAt },
    alreadyConfirmed: false,
    eligibility: eligible,
  })
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
  vi.useRealTimers()
})

describe('useSupportDesk', () => {
  const orderTopic = (orderNo = order.orderNo): ConversationContextRef => ({
    kind: 'ORDER',
    orderNo,
    sourceIds: [],
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  })
  const knowledgeTopic = (): ConversationContextRef => ({
    kind: 'KNOWLEDGE',
    orderNo: null,
    sourceIds: ['kb-gift'],
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  })
  const noTopic: ConversationContextRef = {
    kind: 'NONE',
    orderNo: null,
    sourceIds: [],
    expiresAt: null,
  }

  it('restores only the latest topic hint without selecting an old order or replaying a form', async () => {
    const latestTopic = knowledgeTopic()
    const latest = storedPair('latest', { ...textReply, context: latestTopic })
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    mocks.conversationMessages
      .mockResolvedValueOnce({ conversation, messages: latest, nextBeforeId: latest[0]!.id })
      .mockResolvedValueOnce({
        conversation,
        messages: storedPair('old', {
          ...textReply,
          type: 'AFTER_SALE_FORM',
          orders: [order],
          context: orderTopic(),
        }),
        nextBeforeId: null,
      })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    expect(desk.conversationContext.value).toEqual(latestTopic)
    await desk.loadEarlierMessages()
    expect(desk.conversationContext.value).toEqual(latestTopic)
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.refundOpen.value).toBe(false)
    expect(mocks.sendConversationMessage).not.toHaveBeenCalled()
    expect(mocks.afterSaleEligibility).not.toHaveBeenCalled()
  })

  it.each([undefined, null, noTopic])(
    'clears a previous topic when latest history has no topic: %s',
    async (context) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      mocks.reply.mockResolvedValueOnce({ ...textReply, context: orderTopic() })
      await desk.sendMessage('first')
      mocks.conversationMessages.mockResolvedValueOnce({
        conversation,
        messages: storedPair('last', {
          ...textReply,
          ...(context === undefined ? {} : { context }),
        }),
        nextBeforeId: null,
      })
      await desk.selectConversation(conversation.id)
      expect(desk.conversationContext.value?.kind ?? 'NONE').toBe('NONE')
      expect(desk.contextResetPending.value).toBe(false)
      expect(desk.selectedOrder.value).toBeNull()
    },
  )

  it('replaces an old selected order with a knowledge topic and sends its follow-up without the old order', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({
      ...textReply,
      type: 'ORDER_DETAILS',
      orders: [order],
      context: orderTopic(),
    })
    await desk.selectOrder(order)
    expect(desk.selectedOrder.value?.orderNo).toBe(order.orderNo)
    const topic = knowledgeTopic()
    mocks.reply.mockResolvedValueOnce({ ...textReply, context: topic })
    await desk.sendMessage('gift packaging policy')
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.conversationContext.value).toEqual(topic)
    await desk.sendMessage('tell me more')
    expect(mocks.sendConversationMessage.mock.calls[2]?.[4]).toBeUndefined()
  })

  it.each(['NONE', 'ORDER'] as const)(
    'drops stale selection for %s without selecting a pointer as a live order',
    async (kind) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      mocks.reply.mockResolvedValueOnce({
        ...textReply,
        type: 'ORDER_DETAILS',
        orders: [order],
        context: orderTopic(),
      })
      await desk.selectOrder(order)
      const topic = kind === 'NONE' ? noTopic : orderTopic('SF10002')
      mocks.reply.mockResolvedValueOnce({ ...textReply, context: topic })
      await desk.sendMessage('different topic')
      expect(desk.selectedOrder.value).toBeNull()
      expect(desk.conversationContext.value).toEqual(topic)
      expect(desk.refundOpen.value).toBe(false)
    },
  )

  it.each([400, 500])(
    'retains a pending clear through a %s failure until a successful message confirms it',
    async (status) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      mocks.reply.mockResolvedValueOnce({
        ...textReply,
        type: 'ORDER_DETAILS',
        orders: [order],
        context: orderTopic(),
      })
      await desk.selectOrder(order)
      const beforeClear = mocks.sendConversationMessage.mock.calls.length
      desk.clearSelectedOrder()
      expect(desk.selectedOrder.value).toBeNull()
      expect(desk.conversationContext.value).toBeNull()
      expect(desk.contextResetPending.value).toBe(true)
      expect(mocks.sendConversationMessage).toHaveBeenCalledTimes(beforeClear)
      mocks.sendConversationMessage.mockRejectedValueOnce(new ApiError('failed', status))
      await desk.sendMessage('new topic')
      expect(mocks.sendConversationMessage.mock.lastCall?.[6]).toBe(true)
      expect(desk.contextResetPending.value).toBe(true)
      const topic = knowledgeTopic()
      mocks.reply.mockResolvedValueOnce({ ...textReply, context: topic })
      await desk.sendMessage('another topic')
      expect(mocks.sendConversationMessage.mock.lastCall?.[6]).toBe(true)
      expect(desk.contextResetPending.value).toBe(false)
      expect(desk.conversationContext.value).toEqual(topic)
      await desk.sendMessage('follow up')
      expect(mocks.sendConversationMessage.mock.lastCall?.[6]).toBe(false)
    },
  )

  it('keeps pending reset with an explicit newly selected order and accepts the fresh result', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.clearConversationTopic()
    const nextOrder = { ...order, orderNo: 'SF10002' }
    mocks.reply.mockResolvedValueOnce({
      ...textReply,
      type: 'ORDER_DETAILS',
      orders: [nextOrder],
      context: orderTopic(nextOrder.orderNo),
    })
    await desk.selectOrder(nextOrder)
    expect(mocks.sendConversationMessage.mock.lastCall?.[4]).toBe(nextOrder.orderNo)
    expect(mocks.sendConversationMessage.mock.lastCall?.[6]).toBe(true)
    expect(desk.contextResetPending.value).toBe(false)
    expect(desk.selectedOrder.value).toEqual(nextOrder)
  })

  it('does not revive a cleared topic from a history refresh before the next message is sent', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const topic = orderTopic()
    mocks.reply.mockResolvedValueOnce({ ...textReply, context: topic })
    await desk.sendMessage('order')
    desk.clearConversationTopic()
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation,
      messages: storedPair('old', { ...textReply, context: topic }),
      nextBeforeId: null,
    })
    await desk.loadEarlierMessages(false)
    expect(desk.contextResetPending.value).toBe(true)
    expect(desk.conversationContext.value).toBeNull()
    expect(mocks.sendConversationMessage).toHaveBeenCalledOnce()
  })

  it.each([
    'busy',
    'historyLoading',
    'conversationsLoading',
    'refundSubmitting',
    'receiptSubmitting',
  ] as const)('disables topic clearing while %s is active', async (state) => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const topic = knowledgeTopic()
    mocks.reply.mockResolvedValueOnce({ ...textReply, context: topic })
    await desk.sendMessage('question')
    desk[state].value = true
    desk.clearConversationTopic()
    expect(desk.conversationContext.value).toEqual(topic)
    expect(desk.contextResetPending.value).toBe(false)
  })

  it('marks the topic expired, stops carrying the old selection, and makes no automatic request', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-25T00:00:00Z'))
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({
      ...textReply,
      type: 'ORDER_DETAILS',
      orders: [order],
      context: orderTopic(),
    })
    await desk.selectOrder(order)
    expect(desk.contextExpired.value).toBe(false)
    await vi.advanceTimersByTimeAsync(30 * 60 * 1000)
    expect(desk.contextExpired.value).toBe(true)
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.contextResetPending.value).toBe(false)
    expect(mocks.sendConversationMessage).toHaveBeenCalledOnce()
    await desk.sendMessage('follow up')
    expect(mocks.sendConversationMessage.mock.lastCall?.[4]).toBeUndefined()
  })

  it('does not carry pending reset or pointers across a new conversation, shop or account', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({ ...textReply, context: knowledgeTopic() })
    await desk.sendMessage('question')
    desk.clearConversationTopic()
    desk.clearConversation()
    expect(desk.conversationContext.value).toBeNull()
    expect(desk.contextResetPending.value).toBe(false)
    desk.clearConversationTopic()
    await desk.changeShop('101')
    expect(desk.contextResetPending.value).toBe(false)
    expect(desk.conversationContext.value).toBeNull()
    desk.clearConversationTopic()
    await desk.connect('another-buyer', 'test')
    expect(desk.contextResetPending.value).toBe(false)
    expect(desk.conversationContext.value).toBeNull()
    desk.disconnect()
    desk.clearConversationTopic()
    expect(desk.contextResetPending.value).toBe(false)
  })

  it('discards an old in-flight context response after switching conversations', async () => {
    const second = { ...conversation, id: '00000000-0000-4000-8000-000000000009' }
    mocks.conversations.mockResolvedValueOnce({
      conversations: [conversation, second],
      nextBeforeId: null,
    })
    const topic = knowledgeTopic()
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation,
      messages: [],
      nextBeforeId: null,
    })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ConversationMessages>()
    mocks.sendConversationMessage.mockReturnValueOnce(pending.promise)
    const sending = desk.sendMessage('old question')
    const oldSignal = mocks.sendConversationMessage.mock.lastCall?.[5] as AbortSignal
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation: second,
      messages: storedPair('new question', { ...textReply, context: topic }),
      nextBeforeId: null,
    })
    await desk.selectConversation(second.id)
    expect(oldSignal.aborted).toBe(true)
    pending.resolve({
      conversation,
      messages: storedPair('old question', { ...textReply, context: orderTopic() }),
    })
    await sending
    expect(desk.activeConversation.value?.id).toBe(second.id)
    expect(desk.conversationContext.value).toEqual(topic)
    expect(desk.contextResetPending.value).toBe(false)
  })

  const citedReply: ChatResponse = {
    ...textReply,
    reply: '可以搭配贺卡。',
    knowledge: {
      status: 'ANSWERED',
      sources: [
        { sourceId: 'kb-gift', version: 'v1', title: '包装说明', content: '礼盒可搭配贺卡。' },
      ],
    },
  }

  it('propagates structured evidence on send and restores its original snapshot without a new query', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce(citedReply)
    await desk.sendMessage('能放张祝福卡吗？')
    expect(desk.messages.value[1]).toMatchObject({
      knowledge: citedReply.knowledge,
      historical: false,
    })
    const persisted = storedPair('能放张祝福卡吗？', citedReply)
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation,
      messages: persisted,
      nextBeforeId: null,
    })
    const sendCount = mocks.sendConversationMessage.mock.calls.length
    const replyCount = mocks.reply.mock.calls.length
    await desk.selectConversation(conversation.id)
    expect(desk.messages.value[1]).toMatchObject({
      knowledge: citedReply.knowledge,
      historical: true,
    })
    expect(mocks.sendConversationMessage).toHaveBeenCalledTimes(sendCount)
    expect(mocks.reply).toHaveBeenCalledTimes(replyCount)
    expect(desk.selectedOrder.value).toBeNull()
  })

  it('preserves evidence while prepending history and clears it on shop changes and disconnect', async () => {
    const recent = storedPair('recent', citedReply)
    const older = storedPair('older', {
      ...citedReply,
      knowledge: {
        status: 'ANSWERED',
        sources: [
          { sourceId: 'kb-wash', version: 'v2', title: '洗护说明', content: '请低温手洗。' },
        ],
      },
    })
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    mocks.conversationMessages
      .mockResolvedValueOnce({ conversation, messages: recent, nextBeforeId: recent[0]!.id })
      .mockResolvedValueOnce({ conversation, messages: older, nextBeforeId: null })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadEarlierMessages()
    expect(
      desk.messages.value
        .filter((message) => message.role === 'assistant')
        .map((message) => message.knowledge),
    ).toEqual([older[1]!.response!.knowledge, citedReply.knowledge])
    expect(mocks.reply).not.toHaveBeenCalled()
    await desk.changeShop('101')
    expect(desk.messages.value).toEqual([])
    desk.disconnect()
    expect(desk.messages.value).toEqual([])
  })

  it('restores the latest conversation without re-running an old refund prompt or order action', async () => {
    const history = storedPair('申请退款', {
      ...textReply,
      type: 'AFTER_SALE_FORM',
      orders: [order],
    })
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation,
      messages: history,
      nextBeforeId: null,
    })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    expect(desk.activeConversation.value).toEqual(conversation)
    expect(desk.messages.value.map((message) => message.id)).toEqual(
      history.map((message) => message.id),
    )
    expect(desk.messages.value.every((message) => message.historical)).toBe(true)
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.refundOpen.value).toBe(false)
    expect(mocks.afterSaleEligibility).not.toHaveBeenCalled()
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    expect(mocks.confirmReceipt).not.toHaveBeenCalled()
    expect(mocks.createConversation).not.toHaveBeenCalled()
    expect(mocks.sendConversationMessage).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('creates a conversation only on first explicit send and preserves history on new conversation', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.clearConversation()
    expect(mocks.createConversation).not.toHaveBeenCalled()
    await desk.sendMessage('你好')
    expect(mocks.createConversation).toHaveBeenCalledOnce()
    expect(mocks.sendConversationMessage).toHaveBeenCalledWith(
      '100',
      conversation.id,
      expect.stringMatching(/^[\da-f-]{36}$/i),
      '你好',
      undefined,
      expect.any(AbortSignal),
      false,
    )
    expect(desk.messages.value).toHaveLength(2)
    expect(desk.messages.value.every((message) => !message.historical && !message.pending)).toBe(
      true,
    )
    desk.clearConversation()
    expect(desk.messages.value).toEqual([])
    expect(desk.activeConversation.value).toBeNull()
    expect(desk.conversations.value).toEqual([conversation])
    expect(mocks.createConversation).toHaveBeenCalledOnce()
    await desk.sendMessage('另一段咨询')
    expect(mocks.createConversation).toHaveBeenCalledTimes(2)
  })

  it('loads older messages by the returned UUID cursor, prepends in server order, and deduplicates', async () => {
    const recent = storedPair('新消息')
    const earlier = storedPair('旧消息')
    const cursor = recent[0]!.id
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    const first: ConversationMessagePage = { conversation, messages: recent, nextBeforeId: cursor }
    mocks.conversationMessages.mockResolvedValueOnce(first).mockResolvedValueOnce({
      conversation,
      messages: [...earlier, recent[0]],
      nextBeforeId: null,
    })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadEarlierMessages()
    expect(mocks.conversationMessages.mock.calls[1]).toEqual([
      '100',
      conversation.id,
      cursor,
      expect.any(AbortSignal),
    ])
    expect(desk.messages.value.map((message) => message.id)).toEqual(
      [...earlier, ...recent].map((message) => message.id),
    )
    await desk.loadEarlierMessages()
    expect(mocks.conversationMessages).toHaveBeenCalledTimes(2)
  })

  it('loads more conversations with the returned cursor without reopening the selected history', async () => {
    const older = { ...conversation, id: '00000000-0000-4000-8000-000000000002' }
    mocks.conversations
      .mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: conversation.id })
      .mockResolvedValueOnce({ conversations: [conversation, older], nextBeforeId: null })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadConversations(true)
    expect(mocks.conversations.mock.calls[1]).toEqual([
      '100',
      conversation.id,
      expect.any(AbortSignal),
    ])
    expect(desk.conversations.value).toEqual([conversation, older])
    expect(mocks.conversationMessages).toHaveBeenCalledOnce()
    await desk.loadConversations(true)
    expect(mocks.conversations).toHaveBeenCalledTimes(2)
  })

  it('preserves creation-order pagination when an older conversation receives a new reply', async () => {
    const older = { ...conversation, id: '00000000-0000-4000-8000-000000000002' }
    mocks.conversations.mockResolvedValueOnce({
      conversations: [conversation, older],
      nextBeforeId: older.id,
    })
    mocks.conversationMessages
      .mockResolvedValueOnce({ conversation, messages: [], nextBeforeId: null })
      .mockResolvedValueOnce({ conversation: older, messages: [], nextBeforeId: null })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.selectConversation(older.id)
    await desk.sendMessage('继续咨询')
    expect(desk.conversations.value.map((item) => item.id)).toEqual([conversation.id, older.id])
    expect(desk.activeConversation.value?.id).toBe(older.id)
    expect(desk.nextConversationBeforeId.value).toBe(older.id)
    expect(mocks.createConversation).not.toHaveBeenCalled()
  })

  it('does not let a late history response replace another selected conversation', async () => {
    const other = { ...conversation, id: '00000000-0000-4000-8000-000000000002' }
    mocks.conversations.mockResolvedValueOnce({
      conversations: [conversation, other],
      nextBeforeId: null,
    })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ConversationMessagePage>()
    mocks.conversationMessages.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({
      conversation: other,
      messages: storedPair('另一个会话'),
      nextBeforeId: null,
    })
    const oldLoad = desk.selectConversation(conversation.id)
    const oldSignal = mocks.conversationMessages.mock.calls[1]?.[3] as AbortSignal
    await desk.selectConversation(other.id)
    expect(oldSignal.aborted).toBe(true)
    pending.resolve({ conversation, messages: storedPair('不应出现'), nextBeforeId: null })
    await oldLoad
    expect(desk.activeConversation.value?.id).toBe(other.id)
    expect(desk.messages.value[0]?.text).toBe('另一个会话')
    expect(desk.historyLoading.value).toBe(false)
  })

  it.each(['logout', 'shop switch'] as const)(
    'discards late conversation-list results after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      const pending = deferred<{ conversations: Conversation[]; nextBeforeId: null }>()
      mocks.conversations.mockReturnValueOnce(pending.promise)
      const oldLoad = desk.loadConversations()
      if (transition === 'logout') {
        desk.disconnect()
        await desk.connect('another-buyer', 'test')
      } else await desk.changeShop('101')
      pending.resolve({ conversations: [conversation], nextBeforeId: null })
      await oldLoad
      expect(desk.conversations.value).toEqual([])
      expect(desk.messages.value).toEqual([])
      expect(desk.activeConversation.value).toBeNull()
    },
  )

  it('keeps historical snapshots immutable when a current order is confirmed', async () => {
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    mocks.conversationMessages.mockResolvedValueOnce({
      conversation,
      messages: storedPair('订单', { ...textReply, type: 'ORDER_DETAILS', orders: [order] }),
      nextBeforeId: null,
    })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.openReceiptConfirmation(order)
    await desk.confirmReceipt()
    expect(desk.orders.value[0]?.status).toBe('COMPLETED')
    expect(desk.messages.value.at(-1)?.orders?.[0]?.status).toBe('SHIPPED')
  })

  it('rechecks a historical card with the server and never selects it after access is denied', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockRejectedValueOnce(new ApiError('订单不存在', 404))
    await desk.selectOrder(order)
    expect(mocks.reply).toHaveBeenCalledWith(
      '100',
      '咨询此订单',
      order.orderNo,
      expect.any(AbortSignal),
    )
    expect(desk.selectedOrder.value).toBeNull()
  })

  it('does not replay an uncertain message and offers history verification instead', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.sendConversationMessage.mockRejectedValueOnce(
      new ApiError('网络错误', 0, 'NETWORK_ERROR'),
    )
    await desk.sendMessage('你好')
    expect(desk.messages.value).toEqual([])
    expect(desk.error.value).toContain('刷新咨询记录核实')
    expect(mocks.sendConversationMessage).toHaveBeenCalledOnce()
    await desk.loadEarlierMessages(false)
    expect(mocks.sendConversationMessage).toHaveBeenCalledOnce()
  })

  it('blocks conversation switches while a refund business write has an uncertain outcome', async () => {
    mocks.conversations.mockResolvedValueOnce({ conversations: [conversation], nextBeforeId: null })
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.openRefundForm(order)
    desk.refundReason.value = '商品破损'
    const pending = deferred<AfterSaleTicket>()
    mocks.applyAfterSale.mockReturnValueOnce(pending.promise)
    const submission = desk.submitRefund()
    const signal = mocks.applyAfterSale.mock.calls[0]?.[3] as AbortSignal
    desk.clearConversation()
    await desk.selectConversation(conversation.id)
    expect(signal.aborted).toBe(false)
    expect(desk.refundSubmitting.value).toBe(true)
    expect(mocks.conversationMessages).toHaveBeenCalledOnce()
    pending.resolve({ ...ticket, eligibility: eligible })
    await submission
  })

  it('requires explicit confirmation and updates every copy of the confirmed order', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    await desk.openRefundForm(order)
    desk.openReceiptConfirmation(order)
    expect(desk.receiptOpen.value).toBe(true)
    expect(mocks.confirmReceipt).not.toHaveBeenCalled()
    desk.closeReceiptConfirmation()
    expect(mocks.confirmReceipt).not.toHaveBeenCalled()
    desk.openReceiptConfirmation(order)
    const pending = deferred<ReceiptConfirmation>()
    mocks.confirmReceipt.mockReturnValueOnce(pending.promise)
    const confirmation = desk.confirmReceipt()
    await desk.confirmReceipt()
    desk.closeReceiptConfirmation()
    desk.clearConversation()
    expect(desk.receiptOpen.value).toBe(true)
    expect(desk.receiptSubmitting.value).toBe(true)
    expect(mocks.confirmReceipt).toHaveBeenCalledTimes(1)
    const result: ReceiptConfirmation = {
      order: { ...order, status: 'COMPLETED', receivedAt: eligible.receivedAt! },
      alreadyConfirmed: false,
      eligibility: eligible,
    }
    pending.resolve(result)
    await confirmation
    await nextTick()
    expect(desk.orders.value[0]).toEqual(result.order)
    expect(desk.selectedOrder.value).toEqual(result.order)
    expect(desk.messages.value.at(-1)?.orders).toEqual([result.order])
    expect(desk.receiptResult.value).toEqual(result)
    expect(desk.selectedEligibility.value).toEqual(eligible)
    expect(desk.refundOpen.value).toBe(false)
    await desk.confirmReceipt()
    expect(mocks.confirmReceipt).toHaveBeenCalledTimes(1)
  })

  it.each([
    { ...order, status: 'COMPLETED', receivedAt: null },
    { ...order, status: 'PAID' },
    { ...order, receivedAt: '2026-10-20T00:00:00Z' },
    { ...order, shopId: 101 },
  ])('does not offer receipt confirmation for an ineligible UI target: %s', async (target) => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.openReceiptConfirmation(target)
    await desk.confirmReceipt()
    expect(desk.receiptOpen.value).toBe(false)
    expect(mocks.confirmReceipt).not.toHaveBeenCalled()
  })

  it.each(['logout', 'shop switch'] as const)(
    'ignores late receipt results after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      desk.openReceiptConfirmation(order)
      const pending = deferred<ReceiptConfirmation>()
      mocks.confirmReceipt.mockReturnValueOnce(pending.promise)
      const confirmation = desk.confirmReceipt()
      const signal = mocks.confirmReceipt.mock.calls[0]?.[2] as AbortSignal
      if (transition === 'logout') desk.disconnect()
      else {
        mocks.chat.mockResolvedValueOnce(selection([{ ...order, shopId: 101 }]))
        await desk.changeShop('101')
      }
      pending.resolve({
        order: { ...order, status: 'COMPLETED', receivedAt: eligible.receivedAt! },
        alreadyConfirmed: false,
        eligibility: eligible,
      })
      await confirmation
      expect(signal.aborted).toBe(true)
      expect(desk.receiptResult.value).toBeNull()
      expect(desk.receiptOpen.value).toBe(false)
      expect(desk.orders.value.some((item) => item.status === 'COMPLETED')).toBe(false)
    },
  )

  it.each([
    [new ApiError('没有确认收货权限', 403), '没有确认收货权限'],
    [new ApiError('网络错误', 0, 'NETWORK_ERROR'), '请先刷新订单核实状态'],
  ])(
    'keeps the server decision and does not replay uncertain receipt outcomes',
    async (failure, expected) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      desk.openReceiptConfirmation(order)
      mocks.confirmReceipt.mockRejectedValueOnce(failure)
      await desk.confirmReceipt()
      expect(desk.receiptError.value).toContain(expected)
      expect(desk.orders.value).toEqual([order])
      expect(desk.receiptResult.value).toBeNull()
      expect(mocks.confirmReceipt).toHaveBeenCalledOnce()
    },
  )

  it('fetches deadlines only for the selected order with a known receipt timestamp', async () => {
    const desk = createDesk()
    const known = { ...order, status: 'COMPLETED', receivedAt: eligible.receivedAt }
    mocks.chat.mockResolvedValueOnce(selection([known]))
    await desk.connect('buyer', 'test')
    expect(mocks.afterSaleEligibility).not.toHaveBeenCalled()
    desk.selectedOrder.value = known
    await nextTick()
    await vi.waitFor(() => expect(desk.selectedEligibility.value).toEqual(eligible))
    expect(mocks.afterSaleEligibility).toHaveBeenCalledOnce()
    desk.selectedOrder.value = { ...known }
    await nextTick()
    expect(mocks.afterSaleEligibility).toHaveBeenCalledOnce()
  })

  it('ignores a late deadline result after another order is selected', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<AfterSaleEligibility>()
    mocks.afterSaleEligibility.mockReturnValueOnce(pending.promise)
    desk.selectedOrder.value = { ...order, status: 'COMPLETED', receivedAt: eligible.receivedAt }
    await nextTick()
    desk.selectedOrder.value = { ...order, orderNo: 'SFOTHER', receivedAt: null }
    await nextTick()
    pending.resolve(eligible)
    await nextTick()
    expect(desk.selectedEligibility.value).toBeNull()
    expect(desk.selectedEligibilityLoading.value).toBe(false)
  })

  it('opens the server selected refund form and checks eligibility without submitting', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({ ...textReply, type: 'AFTER_SALE_FORM', orders: [order] })
    await desk.sendMessage('将该订单退款')
    expect(desk.refundOpen.value).toBe(true)
    expect(desk.refundOrder.value).toEqual(order)
    expect(desk.refundEligibility.value).toEqual(eligible)
    expect(mocks.afterSaleEligibility).toHaveBeenCalledWith(
      '100',
      order.orderNo,
      expect.any(AbortSignal),
    )
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    desk.refundReason.value = '商品破损'
    desk.closeRefundForm()
    expect(desk.refundReason.value).toBe('')
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
  })

  it('requires a new order choice when the server reports an ambiguous refund target', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    desk.selectedOrder.value = order
    mocks.reply.mockResolvedValueOnce({ ...textReply, type: 'AFTER_SALE_FORM', orders: [] })
    await desk.sendMessage('申请退款')
    expect(desk.refundOrder.value).toBeNull()
    expect(mocks.afterSaleEligibility).not.toHaveBeenCalled()
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
  })

  it.each(['close', 'shop switch', 'logout'] as const)(
    'ignores a late eligibility result after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      const pending = deferred<AfterSaleEligibility>()
      mocks.afterSaleEligibility.mockReturnValueOnce(pending.promise)
      const opening = desk.openRefundForm(order)
      const signal = mocks.afterSaleEligibility.mock.calls[0]?.[2] as AbortSignal
      if (transition === 'close') desk.closeRefundForm()
      else if (transition === 'shop switch') await desk.changeShop('101')
      else desk.disconnect()
      pending.resolve(eligible)
      await opening
      expect(signal.aborted).toBe(true)
      expect(desk.refundOpen.value).toBe(false)
      expect(desk.refundEligibility.value).toBeNull()
      expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    },
  )

  it('does not attach an old eligibility response to a newly chosen order', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<AfterSaleEligibility>()
    mocks.afterSaleEligibility.mockReturnValueOnce(pending.promise)
    const first = desk.openRefundForm(order)
    const other = { ...order, orderNo: 'SF10002' }
    const otherEligibility = {
      ...eligible,
      orderNo: other.orderNo,
      status: 'EXPIRED' as const,
      canSubmit: false,
    }
    mocks.afterSaleEligibility.mockResolvedValueOnce(otherEligibility)
    await desk.chooseRefundOrder(other)
    pending.resolve(eligible)
    await first
    expect(desk.refundOrder.value).toEqual(other)
    expect(desk.refundEligibility.value).toEqual(otherEligibility)
  })

  it.each(['EXPIRED', 'INELIGIBLE'] as const)(
    'prevents a %s refund despite a filled reason',
    async (status) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      mocks.afterSaleEligibility.mockResolvedValueOnce({ ...eligible, status, canSubmit: false })
      await desk.openRefundForm(order)
      desk.refundReason.value = '商品破损'
      await desk.submitRefund()
      expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    },
  )

  it('submits a manually reviewable request only on confirmation, trims reason and prevents double clicks', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const manual: AfterSaleEligibility = {
      ...eligible,
      status: 'REQUIRES_REVIEW',
      receivedAt: null,
      deadlineAt: null,
    }
    mocks.afterSaleEligibility.mockResolvedValueOnce(manual)
    await desk.openRefundForm(order)
    desk.refundReason.value = '   '
    await desk.submitRefund()
    expect(mocks.applyAfterSale).not.toHaveBeenCalled()
    desk.refundReason.value = '  商品破损  '
    const pending = deferred<AfterSaleTicket>()
    mocks.applyAfterSale.mockReturnValueOnce(pending.promise)
    const submitting = desk.submitRefund()
    await desk.submitRefund()
    desk.clearConversation()
    expect(desk.refundSubmitting.value).toBe(true)
    expect(mocks.applyAfterSale).toHaveBeenCalledTimes(1)
    expect(mocks.applyAfterSale).toHaveBeenCalledWith(
      '100',
      order.orderNo,
      '商品破损',
      expect.any(AbortSignal),
    )
    pending.resolve({ ...ticket, eligibility: manual })
    await submitting
    expect(desk.refundOpen.value).toBe(false)
    expect(desk.ownedAfterSales.value).toHaveLength(1)
    expect(desk.messages.value.at(-1)?.text).toContain('核实收货时间和售后期限')
  })

  it('preserves the reason and reports a server deadline conflict without replaying', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.openRefundForm(order)
    desk.refundReason.value = '商品破损'
    mocks.applyAfterSale.mockRejectedValueOnce(
      new ApiError('已超过售后申请期限', 409, 'BUSINESS_RULE_VIOLATION'),
    )
    await desk.submitRefund()
    expect(desk.refundError.value).toBe('已超过售后申请期限')
    expect(desk.refundReason.value).toBe('商品破损')
    expect(desk.refundOpen.value).toBe(true)
    expect(mocks.applyAfterSale).toHaveBeenCalledTimes(1)
    expect(desk.ownedAfterSales.value).toEqual([])
  })

  it('discards a late refund submission response after changing shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.openRefundForm(order)
    desk.refundReason.value = '商品破损'
    const pending = deferred<AfterSaleTicket>()
    mocks.applyAfterSale.mockReturnValueOnce(pending.promise)
    const submitting = desk.submitRefund()
    await desk.changeShop('101')
    pending.resolve({ ...ticket, eligibility: eligible })
    await submitting
    expect(desk.ownedAfterSales.value).toEqual([])
    expect(desk.messages.value).toEqual([])
    expect(desk.refundOpen.value).toBe(false)
  })

  it('loads buyer progress from the current shop without requiring review access', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadOwnedAfterSales()
    expect(mocks.ownedAfterSales).toHaveBeenCalledWith('100', expect.any(AbortSignal))
    expect(desk.ownedAfterSales.value).toEqual([reviewedTicket])
    expect(desk.afterSalesLoading.value).toBe(false)
    expect(mocks.pendingReviews).not.toHaveBeenCalled()
  })

  it.each(['logout', 'shop switch'] as const)(
    'discards an old buyer progress result after %s',
    async (transition) => {
      const desk = createDesk()
      await desk.connect('buyer', 'test')
      const pending = deferred<AfterSaleTicket[]>()
      mocks.ownedAfterSales.mockReturnValueOnce(pending.promise)
      const request = desk.loadOwnedAfterSales()
      if (transition === 'logout') desk.disconnect()
      else await desk.changeShop('101')
      pending.resolve([reviewedTicket])
      await request
      expect(desk.ownedAfterSales.value).toEqual([])
      expect(desk.afterSalesLoading.value).toBe(false)
    },
  )

  it('clearing chat resets a cancelled progress load without keeping its late response', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<AfterSaleTicket[]>()
    mocks.ownedAfterSales.mockReturnValueOnce(pending.promise)
    const request = desk.loadOwnedAfterSales()
    desk.clearConversation()
    expect(desk.afterSalesLoading.value).toBe(false)
    pending.resolve([reviewedTicket])
    await request
    expect(desk.ownedAfterSales.value).toEqual([])
    await desk.loadOwnedAfterSales()
    expect(desk.ownedAfterSales.value).toEqual([reviewedTicket])
  })
  it('initializes from shop context without treating it as authenticated identity', () => {
    window.history.replaceState(null, '', '/?shopId=101')
    const desk = createDesk()
    expect(desk.shopId.value).toBe('101')
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toEqual([])
  })

  it('reports invalid shop links and falls back to a safe context', () => {
    window.history.replaceState(null, '', '/?shopId=../../')
    const desk = createDesk()
    expect(desk.shopId.value).toBe('100')
    expect(desk.error.value).toContain('无效')
  })

  it('loads current-shop orders after authentication without writing credentials to storage', async () => {
    const desk = createDesk()
    expect(await desk.connect('buyer', 'public-test-only')).toBe(true)
    expect(desk.orders.value).toEqual([order])
    expect(mocks.chat).toHaveBeenCalledWith('100', '我的订单', undefined, expect.any(AbortSignal))
    expect(desk.messages.value).toHaveLength(0)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('uses the returned opaque cursor, not page number arithmetic, and deduplicates', async () => {
    const desk = createDesk()
    mocks.chat
      .mockResolvedValueOnce(selection([order], 7347))
      .mockResolvedValueOnce(selection([order, { ...order, orderNo: 'SF10002' }]))
    await desk.connect('buyer', 'test')
    await desk.loadOrders(true)
    expect(mocks.chat.mock.calls[1]?.[1]).toBe('我的订单 7347')
    expect(desk.orders.value.map((item) => item.orderNo)).toEqual(['SF10001', 'SF10002'])
    expect(desk.nextAfterId.value).toBeNull()
  })

  it('does not request additional pages when there is no next cursor', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.loadOrders(true)
    expect(mocks.chat).toHaveBeenCalledTimes(1)
  })

  it('selects an order and carries it on subsequent messages', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValue({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    await desk.sendMessage('发货了吗')
    expect(mocks.reply.mock.calls[0]?.slice(0, 3)).toEqual(['100', '咨询此订单', 'SF10001'])
    expect(mocks.reply.mock.calls[1]?.slice(0, 3)).toEqual(['100', '发货了吗', 'SF10001'])
    expect(desk.messages.value.map((item) => item.role)).toEqual([
      'user',
      'assistant',
      'user',
      'assistant',
    ])
  })

  it('refuses selecting a card from another shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    await desk.selectOrder({ ...order, shopId: 101 })
    expect(desk.selectedOrder.value).toBeNull()
    expect(mocks.chat).toHaveBeenCalledTimes(1)
  })

  it('resets order and conversation context when changing shop', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockResolvedValueOnce({ ...textReply, type: 'ORDER_DETAILS', orders: [order] })
    await desk.selectOrder(order)
    mocks.chat.mockResolvedValueOnce(selection([{ ...order, shopId: 101 }]))
    await desk.changeShop('101')
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.orders.value[0]?.shopId).toBe(101)
    expect(new URL(window.location.href).searchParams.get('shopId')).toBe('101')
  })

  it('ignores a late chat result after switching shops', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.reply.mockReturnValueOnce(pending.promise)
    mocks.chat.mockResolvedValueOnce(selection([]))
    const oldRequest = desk.sendMessage('你好')
    await desk.changeShop('101')
    pending.resolve(textReply)
    await oldRequest
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.busy.value).toBe(false)
  })

  it('ignores a late order-list result after disconnecting', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.chat.mockReturnValueOnce(pending.promise)
    const oldRequest = desk.loadOrders()
    desk.disconnect()
    pending.resolve(selection())
    await oldRequest
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toHaveLength(0)
  })

  it('prevents double submissions while a message is in flight', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.reply.mockReturnValueOnce(pending.promise)
    const first = desk.sendMessage('第一条')
    await desk.sendMessage('第二条')
    expect(desk.messages.value).toHaveLength(1)
    pending.resolve(textReply)
    await first
    expect(desk.messages.value).toHaveLength(2)
  })

  it('clears sensitive UI state on a 401 while preserving a readable login error', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockRejectedValueOnce(new ApiError('重新连接', 401))
    await desk.sendMessage('你好')
    expect(desk.connected.value).toBe(false)
    expect(desk.orders.value).toHaveLength(0)
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.username.value).toBe('')
    expect(desk.loginError.value).toBe('重新连接')
  })

  it('does not pretend a 403 is successful, and does not resend automatically', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    mocks.reply.mockRejectedValueOnce(new ApiError('权限不足', 403))
    await desk.sendMessage('你好')
    expect(desk.error.value).toBe('权限不足')
    expect(desk.connected.value).toBe(true)
    expect(mocks.sendConversationMessage).toHaveBeenCalledTimes(1)
  })

  it('clearing local conversation aborts a pending reply and selected context', async () => {
    const desk = createDesk()
    await desk.connect('buyer', 'test')
    const pending = deferred<ChatResponse>()
    mocks.reply.mockReturnValueOnce(pending.promise)
    const oldRequest = desk.selectOrder(order)
    desk.clearConversation()
    pending.resolve(textReply)
    await oldRequest
    expect(desk.messages.value).toHaveLength(0)
    expect(desk.selectedOrder.value).toBeNull()
    expect(desk.orders.value).toHaveLength(1)
  })

  it('retains the current shop on an invalid switch attempt', async () => {
    const desk = createDesk()
    await desk.changeShop('0')
    expect(desk.shopId.value).toBe('100')
    expect(desk.error.value).toContain('正整数')
  })

  it('does not authenticate again concurrently', async () => {
    const desk = createDesk()
    const pending = deferred<void>()
    mocks.authenticate.mockReturnValueOnce(pending.promise)
    const first = desk.connect('buyer', 'test')
    expect(await desk.connect('other', 'test')).toBe(false)
    pending.resolve()
    await first
    expect(desk.username.value).toBe('buyer')
  })

  it('does not retain a late authentication after disconnect', async () => {
    const desk = createDesk()
    const pending = deferred<void>()
    mocks.authenticate.mockReturnValueOnce(pending.promise)
    const request = desk.connect('buyer', 'test')
    desk.disconnect()
    pending.resolve()
    expect(await request).toBe(false)
    expect(desk.connected.value).toBe(false)
  })

  it('rejects a merchant identity before loading buyer business data', async () => {
    const desk = createDesk()
    mocks.currentAccount.mockResolvedValueOnce({
      username: 'staff',
      accountType: 'MERCHANT_STAFF',
      capabilities: { reviewAfterSales: true, manageKnowledge: false },
    })
    expect(await desk.connect('staff', 'test')).toBe(false)
    expect(desk.connected.value).toBe(false)
    expect(desk.loginError.value).toContain('商家管理端')
    expect(mocks.chat).not.toHaveBeenCalled()
    expect(mocks.conversations).not.toHaveBeenCalled()
    expect(desk.username.value).toBe('')
  })

  it('discards a late identity response after disconnect', async () => {
    const desk = createDesk()
    const pending = deferred<{
      username: string
      accountType: string
      capabilities: { reviewAfterSales: boolean; manageKnowledge: boolean }
    }>()
    mocks.currentAccount.mockReturnValueOnce(pending.promise)
    const request = desk.connect('buyer', 'test')
    await Promise.resolve()
    desk.disconnect()
    pending.resolve({
      username: 'buyer',
      accountType: 'BUYER',
      capabilities: { reviewAfterSales: false, manageKnowledge: false },
    })
    expect(await request).toBe(false)
    expect(desk.connected.value).toBe(false)
  })
})
