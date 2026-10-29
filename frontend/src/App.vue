<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  Check,
  ChevronDown,
  CircleHelp,
  Clock3,
  KeyRound,
  LoaderCircle,
  LogOut,
  MessageSquare,
  Trash2,
  RefreshCw,
  Send,
  ShieldCheck,
  ShoppingBag,
  Sparkles,
  Store,
  X,
} from '@lucide/vue'
import OrderCard from './components/OrderCard.vue'
import KnowledgeSources from './components/KnowledgeSources.vue'
import ConversationContextNotice from './components/ConversationContextNotice.vue'
import AfterSalesPanel from './components/AfterSalesPanel.vue'
import RefundDialog from './components/RefundDialog.vue'
import ReceiptConfirmationDialog from './components/ReceiptConfirmationDialog.vue'
import { formatAmount, formatDate, formatTime } from './components/format'
import { useSupportDesk } from './composables/useSupportDesk'
import type { Order, OrderSelectionIntent } from './types'

const {
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
  dismissError,
} = useSupportDesk()

const messageDraft = ref('')
const loginUsername = ref('')
const loginPassword = ref('')
const shopDraft = ref('')
const shopError = ref('')
const shopChanging = ref(false)
const loginDialog = ref<HTMLDialogElement | null>(null)
const shopDialog = ref<HTMLDialogElement | null>(null)
const helpDialog = ref<HTMLDialogElement | null>(null)
const deleteDialog = ref<HTMLDialogElement | null>(null)
const deleteError = ref('')
const messageInput = ref<HTMLTextAreaElement | null>(null)
const messageList = ref<HTMLDivElement | null>(null)
type WorkspaceView = 'chat' | 'orders'
const activeView = ref<WorkspaceView>('chat')
const compositionActive = ref(false)
const showAfterSales = ref(false)
const loadingEarlier = ref(false)
let retryAction: (() => Promise<void>) | null = null
let uiContextVersion = 0
let conversationScrollPending = false

const canSend = computed(
  () =>
    connected.value &&
    !busy.value &&
    !historyLoading.value &&
    !conversationsLoading.value &&
    !deletingConversation.value &&
    messageDraft.value.trim().length > 0,
)
const orderCountLabel = computed(
  () => `${orders.value.length}${nextAfterId.value !== null ? '+' : ''}`,
)

function openLogin() {
  loginUsername.value = username.value
  loginPassword.value = ''
  loginDialog.value?.showModal()
}

function closeLogin() {
  if (connecting.value) disconnect()
  loginPassword.value = ''
  loginDialog.value?.close()
}

function onLoginClosed() {
  loginPassword.value = ''
  if (connecting.value) disconnect()
}

async function submitConnection() {
  if (connecting.value) return
  try {
    const success = await connect(loginUsername.value.trim(), loginPassword.value)
    if (success) {
      closeLogin()
      await nextTick()
      messageInput.value?.focus()
    }
  } finally {
    loginPassword.value = ''
  }
}

function openShop() {
  shopDraft.value = shopId.value
  shopError.value = ''
  shopDialog.value?.showModal()
}

async function submitShop() {
  const value = shopDraft.value.trim()
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
    shopError.value = '请输入有效的正整数店铺 ID。'
    return
  }
  if (shopChanging.value) return
  shopChanging.value = true
  try {
    retryAction = null
    messageDraft.value = ''
    await changeShop(value)
    shopDialog.value?.close()
  } catch {
    shopError.value = '店铺切换失败，请稍后重试。'
  } finally {
    shopChanging.value = false
  }
}

async function enterShop(id: number) {
  shopDraft.value = String(id)
  await submitShop()
}

async function submitMessage(text = messageDraft.value) {
  if (!connected.value || busy.value || !text.trim()) return
  const actionContext = uiContextVersion
  // A timed-out POST may already be committed. A retry reads history rather than replaying it.
  retryAction = refreshConversation
  if (text === messageDraft.value) messageDraft.value = ''
  await sendMessage(text)
  if (actionContext !== uiContextVersion) return
  await nextTick()
  messageInput.value?.focus()
}

async function requestOrders(loadMore = false) {
  retryAction = () => requestOrders(loadMore)
  await loadOrders(loadMore)
}

async function consultOrder(order: Order, selectionIntent: OrderSelectionIntent | null = null) {
  retryAction = refreshConversation
  await focusConversation()
  await selectOrder(order, selectionIntent)
}

async function refreshConversation() {
  dismissError()
  if (activeConversation.value) await loadEarlierMessages(false)
  else await loadConversations()
}

async function readEarlierMessages() {
  const element = messageList.value
  const previousHeight = element?.scrollHeight ?? 0
  const previousTop = element?.scrollTop ?? 0
  loadingEarlier.value = true
  try {
    await loadEarlierMessages()
    await nextTick()
    if (element) element.scrollTop = previousTop + element.scrollHeight - previousHeight
  } finally {
    loadingEarlier.value = false
  }
}

function retryLastRequest() {
  if (!connected.value) {
    openLogin()
    return
  }
  void (retryAction ? retryAction() : requestOrders())
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || compositionActive.value)
    return
  event.preventDefault()
  if (canSend.value) void submitMessage()
}

function closeOnBackdrop(event: MouseEvent, dialog: HTMLDialogElement | null) {
  if (event.target !== dialog) return
  if (dialog === deleteDialog.value && deletingConversation.value) return
  if (dialog === loginDialog.value) closeLogin()
  else dialog?.close()
}

async function focusConversation() {
  activeView.value = 'chat'
  await nextTick()
  if (conversationScrollPending && messageList.value) {
    messageList.value.scrollTop = messageList.value.scrollHeight
    conversationScrollPending = false
  }
  messageInput.value?.focus()
}

function showOrders() {
  activeView.value = 'orders'
}

async function openAfterSales() {
  showAfterSales.value = true
  await loadOwnedAfterSales()
}

function openDeleteConversation() {
  deleteError.value = ''
  deleteDialog.value?.showModal()
}

async function confirmDeleteConversation() {
  deleteError.value = ''
  if (await deleteCurrentConversation()) {
    uiContextVersion++
    messageDraft.value = ''
    retryAction = null
    deleteDialog.value?.close()
  } else deleteError.value = error.value ?? '暂时无法删除对话，请稍后重试。'
}

watch([() => messages.value.length, busy], async () => {
  if (loadingEarlier.value) return
  await nextTick()
  // Hidden panels have no scroll height. Follow new replies once chat is visible again.
  if (activeView.value !== 'chat') {
    conversationScrollPending = true
    return
  }
  const element = messageList.value
  if (element) element.scrollTop = element.scrollHeight
})

watch(
  [connected, shopId],
  ([isConnected, activeShop], [, previousShop]) => {
    if (!isConnected || activeShop !== previousShop) {
      deleteDialog.value?.close()
      deleteError.value = ''
      showAfterSales.value = false
      uiContextVersion++
      messageDraft.value = ''
      retryAction = null
    }
  },
  { flush: 'sync' },
)
</script>

<template>
  <div class="app-shell">
    <aside
      class="navigation-rail"
      aria-label="工作台导航"
    >
      <a
        class="brand-mark"
        href="#"
        aria-label="SupportFlow 客服工作台"
        @click.prevent="focusConversation"
      >
        <span
          class="brand-glyph"
          aria-hidden="true"
        >
          s
          <span></span>
        </span>
      </a>
      <div class="rail-divider"></div>
      <nav class="rail-links">
        <button
          class="rail-link"
          :class="{ 'is-active': activeView === 'chat' }"
          type="button"
          aria-label="会话"
          :aria-current="activeView === 'chat' ? 'page' : undefined"
          @click="focusConversation"
        >
          <MessageSquare
            :size="21"
            :stroke-width="1.7"
            aria-hidden="true"
          />
          <span>会话</span>
        </button>
        <button
          class="rail-link"
          :class="{ 'is-active': activeView === 'orders' }"
          type="button"
          aria-label="查看订单面板"
          :aria-current="activeView === 'orders' ? 'page' : undefined"
          @click="showOrders"
        >
          <ShoppingBag
            :size="21"
            :stroke-width="1.7"
            aria-hidden="true"
          />
          <span>订单</span>
        </button>
      </nav>
      <div class="rail-bottom">
        <button
          class="rail-link"
          type="button"
          aria-label="使用帮助"
          @click="helpDialog?.showModal()"
        >
          <CircleHelp
            :size="21"
            :stroke-width="1.7"
            aria-hidden="true"
          />
          <span>帮助</span>
        </button>
        <div
          class="rail-avatar"
          aria-label="客服工作台"
        >
          SF
        </div>
      </div>
    </aside>

    <main class="main-workspace">
      <header class="topbar">
        <div class="workspace-title">
          <h1>{{ activeView === 'chat' ? '店铺客服' : '我的订单' }}</h1>
        </div>
        <div class="topbar-actions">
          <button
            v-if="connected && activeView === 'orders'"
            type="button"
            class="button button-secondary"
            data-testid="open-after-sales"
            @click="openAfterSales"
          >
            售后进度
          </button>
          <button
            class="shop-switch"
            type="button"
            aria-label="切换店铺"
            :disabled="deletingConversation"
            @click="openShop"
          >
            <span class="shop-switch-icon">
              <Store
                :size="18"
                aria-hidden="true"
              />
            </span>
            <span>
              <span class="small-label">当前店铺</span>
              <strong>店铺 {{ shopId }}</strong>
            </span>
            <ChevronDown
              :size="15"
              aria-hidden="true"
            />
          </button>
          <div class="topbar-separator"></div>
          <div
            v-if="connected"
            class="account-connected"
          >
            <div class="account-info">
              <span class="connection-indicator"></span>
              <span>{{ username }}</span>
            </div>
            <button
              class="icon-button disconnect-button"
              type="button"
              data-testid="disconnect-button"
              aria-label="断开连接"
              title="断开连接"
              @click="disconnect"
            >
              <LogOut
                :size="17"
                aria-hidden="true"
              />
            </button>
          </div>
          <button
            v-else
            class="button button-primary connect-top-button"
            type="button"
            @click="openLogin"
          >
            <KeyRound
              :size="15"
              aria-hidden="true"
            />
            连接账号
          </button>
        </div>
      </header>

      <div
        v-if="error"
        class="global-notice"
        role="alert"
      >
        <span class="notice-icon">!</span>
        <span>{{ error }}</span>
        <button
          class="notice-action"
          type="button"
          :disabled="busy || ordersBusy"
          @click="retryLastRequest"
        >
          {{ connected ? '重试' : '重新连接' }}
        </button>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭提示"
          @click="dismissError"
        >
          <X
            :size="16"
            aria-hidden="true"
          />
        </button>
      </div>

      <div
        class="workspace"
        :class="{
          'workspace-chat': activeView === 'chat',
          'workspace-orders': activeView === 'orders',
          'has-selection': activeView === 'orders' && selectedOrder,
        }"
      >
        <section
          v-show="activeView === 'orders'"
          class="orders-panel surface"
          data-testid="orders-panel"
          aria-labelledby="orders-heading"
        >
          <div class="panel-heading">
            <div class="heading-line">
              <h2 id="orders-heading">我的订单</h2>
              <span
                v-if="connected && orders.length"
                class="count-chip"
              >
                {{ orderCountLabel }}
              </span>
            </div>
            <button
              class="icon-button"
              type="button"
              aria-label="刷新订单"
              title="刷新订单"
              :disabled="!connected || ordersBusy || busy"
              @click="requestOrders()"
            >
              <RefreshCw
                :size="16"
                :class="{ spinning: ordersBusy }"
                aria-hidden="true"
              />
            </button>
          </div>
          <p class="panel-description">仅展示你在当前店铺的订单</p>
          <div class="orders-scope">
            <Store
              :size="14"
              aria-hidden="true"
            />
            <span>店铺 {{ shopId }}</span>
            <span class="scope-tag">当前店铺</span>
          </div>
          <div class="orders-scroll">
            <div
              v-if="!connected"
              class="empty-state order-empty"
            >
              <div class="empty-icon">
                <ShoppingBag
                  :size="27"
                  :stroke-width="1.5"
                  aria-hidden="true"
                />
              </div>
              <h3>你的订单，在这里</h3>
              <p>
                连接账号后，查看当前店铺的
                <br />
                真实订单并发起咨询。
              </p>
              <button
                class="text-button"
                type="button"
                @click="openLogin"
              >
                连接账号
                <ArrowRight
                  :size="14"
                  aria-hidden="true"
                />
              </button>
            </div>
            <div
              v-else-if="ordersBusy && !orders.length"
              class="order-skeletons"
              role="status"
              aria-label="正在加载订单"
            >
              <div
                v-for="item in 3"
                :key="item"
                class="order-skeleton"
              >
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
            <div
              v-else-if="!orders.length"
              class="empty-state order-empty"
            >
              <div class="empty-icon">
                <ShoppingBag
                  :size="27"
                  :stroke-width="1.5"
                  aria-hidden="true"
                />
              </div>
              <h3>暂未加载到订单</h3>
              <p>
                这里会展示当前账号在
                <br />
                店铺 {{ shopId }} 的订单。
              </p>
              <button
                class="text-button"
                type="button"
                :disabled="ordersBusy"
                @click="requestOrders()"
              >
                重新加载
                <RefreshCw
                  :size="14"
                  aria-hidden="true"
                />
              </button>
            </div>
            <template v-else>
              <OrderCard
                v-for="order in orders"
                :key="`${order.shopId}-${order.orderNo}`"
                :order="order"
                :selected="
                  selectedOrder?.orderNo === order.orderNo && selectedOrder?.shopId === order.shopId
                "
                :busy="busy || receiptSubmitting"
                @select="consultOrder"
                @confirm-receipt="openReceiptConfirmation"
              />
              <button
                v-if="nextAfterId !== null"
                type="button"
                class="load-more"
                data-testid="load-more-orders"
                :disabled="ordersBusy || busy"
                @click="requestOrders(true)"
              >
                <LoaderCircle
                  v-if="ordersBusy"
                  class="spinning"
                  :size="14"
                  aria-hidden="true"
                />
                <ArrowDown
                  v-else
                  :size="14"
                  aria-hidden="true"
                />
                {{ ordersBusy ? '正在加载' : '加载更多订单' }}
              </button>
              <p
                v-else
                class="list-end"
              >
                已展示全部订单
              </p>
            </template>
          </div>
          <div class="orders-footer">
            <ShieldCheck
              :size="15"
              aria-hidden="true"
            />
            <span>只查看本人订单，保护你的隐私</span>
          </div>
        </section>

        <section
          v-show="activeView === 'chat'"
          class="conversation-panel surface"
          data-testid="conversation-panel"
          aria-labelledby="conversation-heading"
        >
          <header class="conversation-heading">
            <div class="assistant-identity">
              <span class="assistant-avatar">
                <Sparkles
                  :size="20"
                  :stroke-width="1.7"
                  aria-hidden="true"
                />
              </span>
              <div>
                <h2 id="conversation-heading">店铺智能客服</h2>
                <p>
                  <span :class="['small-status-dot', { active: connected }]"></span>
                  {{ connected ? `店铺 ${shopId} · 在线` : '尚未连接账号' }}
                </p>
              </div>
            </div>
            <button
              type="button"
              class="clear-chat-button delete-chat-button"
              data-testid="delete-conversation"
              :disabled="
                !connected ||
                !activeConversation ||
                !messages.length ||
                connecting ||
                busy ||
                historyLoading ||
                conversationsLoading ||
                refundSubmitting ||
                receiptSubmitting ||
                deletingConversation
              "
              title="删除当前店铺的对话"
              @click="openDeleteConversation"
            >
              <Trash2
                :size="14"
                aria-hidden="true"
              />
              <span>删除对话</span>
            </button>
          </header>

          <p
            v-if="conversationError"
            class="history-notice"
            role="alert"
          >
            {{ conversationError }}
          </p>

          <ConversationContextNotice
            :context="conversationContext"
            :pending-reset="contextResetPending"
            :expired="contextExpired"
            :disabled="
              busy ||
              historyLoading ||
              conversationsLoading ||
              refundSubmitting ||
              receiptSubmitting ||
              connecting
            "
            @clear="clearConversationTopic"
          />

          <div
            v-if="selectedOrder"
            class="selected-context"
            data-testid="selected-order"
          >
            <span class="context-icon">
              <ShoppingBag
                :size="17"
                aria-hidden="true"
              />
            </span>
            <div>
              <span class="context-label">正在咨询</span>
              <strong>{{ selectedOrder.items?.[0]?.productName || selectedOrder.orderNo }}</strong>
              <span class="context-description">
                <template v-if="selectedOrder.items?.length">
                  {{ selectedOrder.orderNo }} ·
                </template>
                {{ selectedOrder.statusDescription }} ·
                {{ formatAmount(selectedOrder.totalAmount, selectedOrder.currency) }}
              </span>
            </div>
            <button
              type="button"
              class="text-button"
              :disabled="
                busy ||
                historyLoading ||
                conversationsLoading ||
                refundSubmitting ||
                receiptSubmitting
              "
              @click="openRefundForm()"
            >
              申请退款
            </button>
            <button
              type="button"
              class="icon-button"
              aria-label="取消选中订单"
              :disabled="
                busy ||
                historyLoading ||
                conversationsLoading ||
                refundSubmitting ||
                receiptSubmitting
              "
              @click="clearSelectedOrder"
            >
              <X
                :size="16"
                aria-hidden="true"
              />
            </button>
          </div>

          <div
            ref="messageList"
            class="message-list"
            role="log"
            aria-label="客服对话"
            aria-live="polite"
            aria-relevant="additions"
          >
            <button
              v-if="nextMessageBeforeId"
              type="button"
              class="load-more history-load-earlier"
              data-testid="load-earlier-messages"
              :disabled="historyLoading || busy"
              @click="readEarlierMessages"
            >
              {{ historyLoading ? '加载中…' : '加载更早消息' }}
            </button>
            <p
              v-if="historyLoading && !messages.length"
              class="history-notice"
              role="status"
            >
              正在恢复咨询记录…
            </p>
            <div
              v-if="!messages.length && !historyLoading"
              class="conversation-welcome"
            >
              <h3>{{ connected ? '你好，有什么可以帮你？' : '从一段对话开始' }}</h3>
              <p class="welcome-description">
                {{
                  connected
                    ? '直接输入问题，也可以从「订单」选择一笔订单咨询。'
                    : '连接账号后，就可以开始聊天。'
                }}
              </p>
              <button
                v-if="!connected"
                type="button"
                class="button button-primary welcome-connect"
                @click="openLogin"
              >
                连接账号，开始咨询
                <ArrowUpRight
                  :size="16"
                  aria-hidden="true"
                />
              </button>
            </div>
            <template v-else>
              <div class="conversation-date">
                <span>{{ activeConversation ? '咨询记录' : '新的咨询' }}</span>
              </div>
              <article
                v-for="message in messages"
                :key="message.id"
                class="message"
                :class="`message-${message.role}`"
                data-testid="chat-message"
              >
                <span
                  v-if="message.role === 'assistant'"
                  class="message-avatar"
                >
                  <Sparkles
                    :size="16"
                    aria-hidden="true"
                  />
                </span>
                <div class="message-content">
                  <div class="message-byline">
                    <span>{{ message.role === 'assistant' ? '店铺智能客服' : '你' }}</span>
                    <time :datetime="message.createdAt">{{ formatTime(message.createdAt) }}</time>
                  </div>
                  <div class="message-bubble">
                    <p>{{ message.text }}</p>
                  </div>
                  <KnowledgeSources
                    v-if="message.knowledge?.status === 'ANSWERED'"
                    :knowledge="message.knowledge"
                    :historical="message.historical"
                  />
                  <div
                    v-if="message.orders?.length"
                    class="message-order-list"
                  >
                    <p
                      v-if="message.historical"
                      class="historical-snapshot"
                    >
                      历史快照 · 订单信息以重新查询为准
                    </p>
                    <OrderCard
                      v-for="order in message.orders"
                      :key="`${order.shopId}-${order.orderNo}`"
                      :order="order"
                      compact
                      :historical="message.historical"
                      :busy="busy || receiptSubmitting"
                      :selected="
                        selectedOrder?.orderNo === order.orderNo &&
                        selectedOrder?.shopId === order.shopId
                      "
                      @select="
                        consultOrder(
                          $event,
                          message.historical ? null : (message.selectionIntent ?? null),
                        )
                      "
                      @confirm-receipt="openReceiptConfirmation"
                    />
                  </div>
                </div>
                <span
                  v-if="message.role === 'user'"
                  class="user-message-avatar"
                >
                  {{ username.slice(0, 1).toUpperCase() || '你' }}
                </span>
              </article>
            </template>
            <div
              v-if="busy"
              class="message message-assistant"
              role="status"
              aria-label="正在查询订单"
            >
              <span class="message-avatar">
                <Sparkles
                  :size="16"
                  aria-hidden="true"
                />
              </span>
              <div class="message-content">
                <div class="message-byline">店铺智能客服</div>
                <div class="typing-bubble">
                  <span></span>
                  <span></span>
                  <span></span>
                  <span class="typing-label">正在为你查询</span>
                </div>
              </div>
            </div>
          </div>

          <div class="composer-area">
            <form
              class="composer"
              :class="{ 'is-disabled': !connected }"
              @submit.prevent="submitMessage()"
            >
              <label
                for="message-input"
                class="sr-only"
              >
                咨询内容
              </label>
              <textarea
                id="message-input"
                ref="messageInput"
                v-model="messageDraft"
                data-testid="message-input"
                :disabled="!connected || busy || historyLoading || conversationsLoading"
                maxlength="1000"
                rows="2"
                :placeholder="
                  connected
                    ? selectedOrder
                      ? `关于 ${selectedOrder.items?.[0]?.productName || selectedOrder.orderNo}，你想了解什么？`
                      : '直接说商品名称和问题，例如：我买的耳机发货了吗？'
                    : '请先连接账号，再开始咨询…'
                "
                @keydown="onComposerKeydown"
                @compositionstart="compositionActive = true"
                @compositionend="compositionActive = false"
              ></textarea>
              <div class="composer-bottom">
                <span class="input-hint">
                  Enter 发送
                  <span>·</span>
                  Shift + Enter 换行
                </span>
                <div class="composer-right">
                  <span
                    class="character-count"
                    :class="{ 'at-limit': messageDraft.length >= 1000 }"
                  >
                    {{ messageDraft.length }}
                    <span>/1000</span>
                  </span>
                  <button
                    type="submit"
                    class="send-button"
                    data-testid="send-button"
                    :disabled="!canSend"
                    aria-label="发送消息"
                  >
                    <LoaderCircle
                      v-if="busy"
                      class="spinning"
                      :size="16"
                      aria-hidden="true"
                    />
                    <Send
                      v-else
                      :size="16"
                      aria-hidden="true"
                    />
                    <span>发送</span>
                  </button>
                </div>
              </div>
            </form>
          </div>
        </section>

        <aside
          v-if="activeView === 'orders' && selectedOrder"
          class="order-detail surface"
          aria-labelledby="detail-heading"
        >
          <div class="panel-heading">
            <h2 id="detail-heading">咨询订单</h2>
            <button
              class="icon-button"
              type="button"
              aria-label="关闭订单详情"
              :disabled="
                busy ||
                historyLoading ||
                conversationsLoading ||
                refundSubmitting ||
                receiptSubmitting
              "
              @click="clearSelectedOrder"
            >
              <X
                :size="16"
                aria-hidden="true"
              />
            </button>
          </div>
          <div class="detail-symbol">
            <ShoppingBag
              :size="31"
              :stroke-width="1.35"
              aria-hidden="true"
            />
          </div>
          <p class="detail-order-label">订单编号</p>
          <h3 class="detail-order-number">{{ selectedOrder.orderNo }}</h3>
          <span
            class="status-badge"
            :data-status="selectedOrder.status"
          >
            <span class="status-dot"></span>
            {{ selectedOrder.statusDescription || selectedOrder.status }}
          </span>
          <div class="detail-amount">
            <span>订单金额</span>
            <strong>{{ formatAmount(selectedOrder.totalAmount, selectedOrder.currency) }}</strong>
          </div>
          <dl class="order-facts">
            <div>
              <dt>所属店铺</dt>
              <dd>店铺 {{ selectedOrder.shopId }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ formatDate(selectedOrder.createdAt, true) }}</dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{{ formatDate(selectedOrder.updatedAt, true) }}</dd>
            </div>
            <div>
              <dt>币种</dt>
              <dd>{{ selectedOrder.currency }}</dd>
            </div>
            <div v-if="selectedOrder.receivedAt">
              <dt>确认收货时间</dt>
              <dd data-testid="order-received-at">
                {{ formatDate(selectedOrder.receivedAt, true) }}
              </dd>
            </div>
            <div v-if="selectedEligibility?.deadlineAt">
              <dt>售后申请截止</dt>
              <dd data-testid="order-after-sale-deadline">
                {{ formatDate(selectedEligibility.deadlineAt, true) }}
              </dd>
            </div>
          </dl>
          <p
            v-if="selectedEligibilityLoading"
            role="status"
          >
            正在读取售后截止时间…
          </p>
          <p
            v-if="selectedEligibilityError"
            role="alert"
          >
            {{ selectedEligibilityError }}
          </p>
          <button
            v-if="selectedOrder.status === 'SHIPPED' && !selectedOrder.receivedAt"
            class="button button-primary detail-query"
            type="button"
            :disabled="busy || ordersBusy || receiptSubmitting"
            @click="openReceiptConfirmation(selectedOrder)"
          >
            确认收货
          </button>
          <div class="detail-note">
            <Clock3
              :size="16"
              aria-hidden="true"
            />
            <p>此处展示查询时的订单信息。发送消息可再次查询最新状态。</p>
          </div>
          <button
            class="button button-secondary detail-query"
            type="button"
            :disabled="busy"
            @click="consultOrder(selectedOrder)"
          >
            <RefreshCw
              :size="14"
              aria-hidden="true"
            />
            查询最新状态
          </button>
        </aside>
      </div>
    </main>
    <dialog
      ref="loginDialog"
      class="modal connection-modal"
      aria-labelledby="login-title"
      @click="closeOnBackdrop($event, loginDialog)"
      @cancel.prevent="closeLogin"
      @close="onLoginClosed"
    >
      <form
        class="modal-content"
        data-testid="connect-form"
        @submit.prevent="submitConnection"
      >
        <button
          class="icon-button modal-close"
          type="button"
          aria-label="关闭连接窗口"
          @click="closeLogin"
        >
          <X
            :size="19"
            aria-hidden="true"
          />
        </button>
        <div class="modal-symbol">
          <KeyRound
            :size="25"
            :stroke-width="1.5"
            aria-hidden="true"
          />
        </div>
        <p class="eyebrow">CONNECT YOUR ACCOUNT</p>
        <h2 id="login-title">连接你的客服账号</h2>
        <p class="modal-intro">
          使用后端已配置的本地开发账号，
          <br />
          查看店铺 {{ shopId }} 中属于你的订单。
        </p>
        <div
          v-if="loginError"
          class="inline-error"
          role="alert"
        >
          {{ loginError }}
        </div>
        <label
          class="field-label"
          for="login-username"
        >
          用户名
        </label>
        <input
          id="login-username"
          v-model="loginUsername"
          data-testid="username-input"
          type="text"
          name="username"
          autocomplete="username"
          placeholder="输入开发账号用户名"
          required
          maxlength="128"
          :disabled="connecting"
          autofocus
        />
        <label
          class="field-label"
          for="login-password"
        >
          密码
        </label>
        <input
          id="login-password"
          v-model="loginPassword"
          data-testid="password-input"
          type="password"
          name="password"
          autocomplete="current-password"
          placeholder="输入账号密码"
          required
          :disabled="connecting"
        />
        <p class="connection-security">
          <ShieldCheck
            :size="14"
            aria-hidden="true"
          />
          <span>
            凭证仅保存在内存中，刷新或断开连接后清除。
            <br />
            仅用于本地开发联调，不是生产环境登录方案。
          </span>
        </p>
        <button
          class="button button-primary modal-submit"
          type="submit"
          data-testid="connect-button"
          :disabled="connecting"
        >
          <LoaderCircle
            v-if="connecting"
            :size="16"
            class="spinning"
            aria-hidden="true"
          />
          {{ connecting ? '正在连接…' : '连接账号' }}
          <ArrowRight
            v-if="!connecting"
            :size="16"
            aria-hidden="true"
          />
        </button>
      </form>
    </dialog>

    <dialog
      ref="shopDialog"
      class="modal"
      aria-labelledby="shop-title"
      @click="closeOnBackdrop($event, shopDialog)"
    >
      <form
        class="modal-content"
        @submit.prevent="submitShop"
      >
        <button
          class="icon-button modal-close"
          type="button"
          aria-label="关闭店铺切换"
          :disabled="shopChanging"
          @click="shopDialog?.close()"
        >
          <X
            :size="19"
            aria-hidden="true"
          />
        </button>
        <div class="modal-symbol">
          <Store
            :size="25"
            :stroke-width="1.5"
            aria-hidden="true"
          />
        </div>
        <p class="eyebrow">SHOP CONTEXT</p>
        <h2 id="shop-title">进入另一家店铺</h2>
        <p class="modal-intro">
          输入目标店铺 ID，切换本次咨询的店铺。
          <br />
          切换后载入目标店铺的咨询记录，已选订单将取消。
        </p>
        <div
          v-if="shopOptions.length"
          class="shop-options"
          aria-label="选择店铺"
        >
          <button
            v-for="shop in shopOptions"
            :key="shop.shopId"
            type="button"
            :data-testid="`shop-option-${shop.shopId}`"
            :class="['shop-option', { active: String(shop.shopId) === shopId }]"
            :disabled="shopChanging"
            @click="enterShop(shop.shopId)"
          >
            <strong>{{ shop.name }}</strong>
            <span>店铺 {{ shop.shopId }}</span>
          </button>
        </div>
        <p
          v-if="shopDirectoryError"
          class="field-help"
        >
          {{ shopDirectoryError }}
        </p>
        <label
          class="field-label"
          for="shop-id"
        >
          店铺 ID
        </label>
        <input
          id="shop-id"
          v-model="shopDraft"
          data-testid="shop-input"
          type="text"
          inputmode="numeric"
          pattern="[1-9][0-9]*"
          placeholder="例如：100"
          required
          :disabled="shopChanging"
          autofocus
        />
        <p
          v-if="shopError"
          class="inline-error"
          role="alert"
        >
          {{ shopError }}
        </p>
        <p class="field-help">每家店铺保留一个对话窗口，切换回来即可继续聊天。</p>
        <button
          type="submit"
          class="button button-primary modal-submit"
          data-testid="shop-confirm"
          :disabled="shopChanging"
        >
          <LoaderCircle
            v-if="shopChanging"
            class="spinning"
            :size="16"
            aria-hidden="true"
          />
          {{ shopChanging ? '正在切换…' : '进入店铺' }}
          <ArrowRight
            v-if="!shopChanging"
            :size="16"
            aria-hidden="true"
          />
        </button>
      </form>
    </dialog>

    <dialog
      ref="deleteDialog"
      class="modal"
      aria-labelledby="delete-conversation-title"
      @click="closeOnBackdrop($event, deleteDialog)"
      @cancel="deletingConversation && $event.preventDefault()"
    >
      <form
        class="modal-content"
        @submit.prevent="confirmDeleteConversation"
      >
        <h2 id="delete-conversation-title">删除该店铺对话？</h2>
        <p class="modal-intro">
          将删除你在店铺
          {{ shopId }} 的全部聊天记录，包括以前的咨询。其他店铺的聊天、订单和售后申请不受影响。
        </p>
        <p
          v-if="deleteError"
          class="inline-error"
          role="alert"
        >
          {{ deleteError }}
        </p>
        <div class="conversation-delete-actions">
          <button
            type="button"
            class="button"
            :disabled="deletingConversation"
            @click="deleteDialog?.close()"
          >
            取消
          </button>
          <button
            type="submit"
            class="button button-danger"
            data-testid="confirm-delete-conversation"
            :disabled="deletingConversation"
          >
            {{ deletingConversation ? '正在删除…' : '确认删除' }}
          </button>
        </div>
      </form>
    </dialog>

    <dialog
      ref="helpDialog"
      class="modal help-modal"
      aria-labelledby="help-title"
      @click="closeOnBackdrop($event, helpDialog)"
    >
      <div class="modal-content">
        <button
          class="icon-button modal-close"
          type="button"
          aria-label="关闭帮助"
          @click="helpDialog?.close()"
        >
          <X
            :size="19"
            aria-hidden="true"
          />
        </button>
        <div class="modal-symbol">
          <CircleHelp
            :size="25"
            :stroke-width="1.5"
            aria-hidden="true"
          />
        </div>
        <p class="eyebrow">A LITTLE GUIDE</p>
        <h2 id="help-title">从订单，到答案</h2>
        <ol class="help-steps">
          <li>
            <span>01</span>
            <div>
              <strong>连接账号</strong>
              <p>使用后端配置的本地开发账号。密码不会保存到浏览器存储。</p>
            </div>
          </li>
          <li>
            <span>02</span>
            <div>
              <strong>确认当前店铺</strong>
              <p>点击「订单」查看你在这家店铺的订单，再点击「会话」返回聊天。</p>
            </div>
          </li>
          <li>
            <span>03</span>
            <div>
              <strong>选择订单，开始咨询</strong>
              <p>
                直接说商品名称和问题，或点击「咨询此订单」。收到商品后可确认收货；申请退款需选择原因并确认提交。
              </p>
            </div>
          </li>
        </ol>
        <p class="help-footnote">
          咨询记录按账号和店铺保存。刷新后需重新连接账号；查看历史不会重新提交退款或修改订单。新会话不会删除旧记录。
        </p>
        <button
          class="button button-primary modal-submit"
          type="button"
          @click="helpDialog?.close()"
        >
          知道了
          <Check
            :size="16"
            aria-hidden="true"
          />
        </button>
      </div>
    </dialog>
  </div>
  <ReceiptConfirmationDialog
    v-if="receiptOpen && receiptOrder && connected"
    :order="receiptOrder"
    :submitting="receiptSubmitting"
    :error="receiptError"
    :result="receiptResult"
    @close="closeReceiptConfirmation"
    @confirm="confirmReceipt"
  />
  <RefundDialog
    v-if="refundOpen && connected"
    v-model:reason="refundReason"
    :orders="orders"
    :order="refundOrder"
    :eligibility="refundEligibility"
    :loading="refundLoading"
    :submitting="refundSubmitting"
    :error="refundError"
    :more="nextAfterId !== null"
    :orders-loading="ordersBusy"
    @close="closeRefundForm"
    @choose="chooseRefundOrder"
    @submit="submitRefund"
    @more="loadOrders(true)"
  />
  <AfterSalesPanel
    v-if="showAfterSales && connected"
    :shop-id="shopId"
    :tickets="ownedAfterSales"
    :loading="afterSalesLoading"
    :error="afterSalesError"
    @close="showAfterSales = false"
    @refresh="loadOwnedAfterSales"
  />
</template>
