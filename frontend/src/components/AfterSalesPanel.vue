<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RefreshCw, X } from '@lucide/vue'
import type { AfterSaleTicket } from '../types'
import ExecutionProgress from './ExecutionProgress.vue'
import { formatDate } from './format'

defineProps<{
  shopId: string
  tickets: AfterSaleTicket[]
  loading: boolean
  error: string | null
}>()
const emit = defineEmits<{ close: []; refresh: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
let closing = false
function close() {
  if (closing) return
  closing = true
  dialog.value?.close()
  emit('close')
}
function onBackdrop(event: MouseEvent) {
  if (event.target === dialog.value) close()
}
function label(status: AfterSaleTicket['status']) {
  return { PENDING_REVIEW: '待人工审核', APPROVED: '已批准', REJECTED: '已拒绝' }[status]
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => {
  closing = true
  dialog.value?.close()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="after-sales-dialog"
    data-testid="after-sales-panel"
    aria-labelledby="after-sales-title"
    @cancel.prevent="close"
    @close="close"
    @click="onBackdrop"
  >
    <header>
      <div>
        <h2 id="after-sales-title">我的售后进度</h2>
        <p>店铺 {{ shopId }} · 本人最近 50 条申请</p>
      </div>
      <button
        type="button"
        aria-label="关闭售后进度"
        @click="close"
      >
        <X
          :size="21"
          aria-hidden="true"
        />
      </button>
    </header>
    <div class="after-sales-body">
      <div class="after-sales-toolbar">
        <p>审批结果与模拟退款执行进度分别展示。</p>
        <button
          type="button"
          data-testid="refresh-after-sales"
          :disabled="loading"
          @click="emit('refresh')"
        >
          <RefreshCw
            :size="15"
            aria-hidden="true"
          />
          {{ loading ? '正在刷新' : '刷新进度' }}
        </button>
      </div>
      <p
        v-if="error"
        class="after-sales-error"
        role="alert"
      >
        {{ error }}
      </p>
      <p
        v-if="loading && !tickets.length"
        class="after-sales-empty"
        role="status"
      >
        正在读取售后申请…
      </p>
      <p
        v-else-if="!tickets.length && !error"
        class="after-sales-empty"
      >
        当前店铺暂无售后申请。选中订单后，可在对话中发送“申请售后”和具体原因。
      </p>
      <div
        class="after-sales-list"
        :aria-busy="loading"
      >
        <details
          v-for="(ticket, index) in tickets"
          :key="ticket.id"
          class="after-sales-card"
          data-testid="after-sale-card"
          :open="index === 0"
        >
          <summary>
            <strong>{{ ticket.orderNo }}</strong>
            <span>{{ label(ticket.status) }}</span>
          </summary>
          <div class="after-sales-content">
            <dl>
              <div>
                <dt>售后单号</dt>
                <dd>{{ ticket.id }}</dd>
              </div>
              <div>
                <dt>申请时间</dt>
                <dd>{{ formatDate(ticket.createdAt, true) }}</dd>
              </div>
              <div>
                <dt>申请原因</dt>
                <dd>{{ ticket.reason }}</dd>
              </div>
              <div v-if="ticket.reviewNote">
                <dt>审核意见</dt>
                <dd>{{ ticket.reviewNote }}</dd>
              </div>
            </dl>
            <ExecutionProgress
              v-if="ticket.status === 'APPROVED'"
              :execution="ticket.execution"
            />
            <p
              v-else
              class="after-sales-notice"
            >
              {{
                ticket.status === 'PENDING_REVIEW'
                  ? '正在等待客服或管理员审核，审核通过后才能执行模拟退款。'
                  : '申请已拒绝，不会执行模拟退款。'
              }}
            </p>
          </div>
        </details>
      </div>
    </div>
    <footer>学习版使用模拟退款，页面结果不代表真实资金到账。</footer>
  </dialog>
</template>

<style scoped>
.after-sales-dialog {
  width: min(850px, 90vw);
  max-width: 90vw;
  max-height: 90dvh;
  padding: 0;
  border: 1px solid #e3d9ee;
  border-radius: 17px;
  background: #fff;
  color: #62506f;
  box-shadow: 0 24px 90px #28173238;
  overflow: hidden;
  font-size: 14px;
}
.after-sales-dialog[open] {
  display: flex;
  flex-direction: column;
}
.after-sales-dialog::backdrop {
  background: #29233370;
  backdrop-filter: blur(5px);
}
header {
  display: flex;
  justify-content: space-between;
  gap: 15px;
  padding: 22px 26px;
  border-bottom: 1px solid #eee7f4;
}
header h2 {
  margin: 0;
  font-size: 23px;
  color: #604475;
}
header p {
  margin: 7px 0 0;
  color: #8c789c;
}
button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px solid #e2d7ed;
  border-radius: 7px;
  color: #7d5d99;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
header button {
  align-self: flex-start;
  padding: 8px;
  border: 0;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
button:focus-visible,
summary:focus-visible {
  outline: 3px solid #ab90cf;
  outline-offset: 3px;
}
.after-sales-body {
  padding: 20px 26px 25px;
  min-height: 0;
  overflow-y: auto;
}
.after-sales-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;
}
.after-sales-toolbar p {
  margin: 0;
  line-height: 1.8;
}
.after-sales-list {
  display: grid;
  gap: 14px;
}
.after-sales-card {
  border: 1px solid #e6dced;
  border-radius: 9px;
  overflow: hidden;
}
summary {
  padding: 17px;
  background: #fcfafe;
  cursor: pointer;
  overflow-wrap: anywhere;
}
summary strong {
  margin-right: 14px;
  color: #6f4f88;
  font-size: 16px;
}
summary span {
  color: #8b6c4f;
}
.after-sales-content {
  padding: 17px;
}
dl {
  display: grid;
  gap: 11px;
  margin: 0;
}
dl > div {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 9px;
}
dt {
  color: #8c789c;
}
dd {
  margin: 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.after-sales-empty {
  padding: 30px 10px;
  text-align: center;
  line-height: 1.9;
}
.after-sales-error {
  padding: 12px;
  color: #9c594d;
  background: #fff7f3;
  border-radius: 7px;
  overflow-wrap: anywhere;
}
.after-sales-notice {
  margin: 18px 0 0;
  padding: 13px;
  border-radius: 7px;
  background: #faf7fd;
  line-height: 1.8;
}
footer {
  padding: 16px 26px;
  border-top: 1px solid #eee7f4;
  background: #fcfafd;
  color: #8a739b;
  line-height: 1.8;
}
@media (max-width: 600px) {
  header {
    padding: 18px;
  }
  .after-sales-body {
    padding: 18px 16px;
  }
  footer {
    padding: 14px 18px;
  }
}
</style>
