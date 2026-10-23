<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { Order, ReceiptConfirmation } from '../types'
import { formatDate } from './format'

const props = defineProps<{
  order: Order
  submitting: boolean
  error: string | null
  result: ReceiptConfirmation | null
}>()
const emit = defineEmits<{ close: []; confirm: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
function close() {
  if (!props.submitting) emit('close')
}
function confirm() {
  if (!props.submitting && !props.result) emit('confirm')
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog
    ref="dialog"
    class="receipt-dialog"
    aria-labelledby="receipt-title"
    data-testid="receipt-confirmation-dialog"
    @cancel.prevent="close"
  >
    <h2 id="receipt-title">{{ result ? '收货确认结果' : '确认收货' }}</h2>
    <p class="order-reference">订单 {{ order.orderNo }} · 店铺 {{ order.shopId }}</p>
    <template v-if="!result">
      <p>
        请确认你已经收到这笔订单的商品。确认后订单将变为已完成，并从后台记录的确认收货时间开始计算售后申请期限。
      </p>
      <p>如果尚未收到商品，请选择“暂不确认”。</p>
    </template>
    <section
      v-else
      role="status"
      data-testid="receipt-confirmation-result"
    >
      <p>
        {{ result.alreadyConfirmed ? '这笔订单已经确认收货，保留原确认时间。' : '已确认收货。' }}
      </p>
      <dl>
        <div>
          <dt>确认收货时间</dt>
          <dd>{{ formatDate(result.order.receivedAt, true) }}</dd>
        </div>
        <div v-if="result.eligibility.deadlineAt">
          <dt>售后申请截止</dt>
          <dd>{{ formatDate(result.eligibility.deadlineAt, true) }}</dd>
        </div>
      </dl>
      <p>{{ result.eligibility.message }}</p>
    </section>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <footer>
      <button
        type="button"
        :disabled="submitting"
        @click="close"
      >
        {{ result ? '完成' : '暂不确认' }}
      </button>
      <button
        v-if="!result"
        type="button"
        :disabled="submitting"
        data-testid="confirm-receipt-submit"
        @click="confirm"
      >
        {{ submitting ? '正在确认…' : '我已收到商品，确认收货' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.receipt-dialog {
  box-sizing: border-box;
  width: min(520px, calc(100vw - 32px));
  max-height: 90dvh;
  overflow-y: auto;
  border: 1px solid #e4ddf0;
  border-radius: 20px;
  padding: 24px;
  color: #39324c;
}
.receipt-dialog::backdrop {
  background: #211b3655;
}
h2 {
  margin: 0 0 12px;
  font-size: 20px;
}
p {
  line-height: 1.7;
}
.order-reference {
  color: #716380;
  overflow-wrap: anywhere;
}
dl {
  background: #f5f2fa;
  border-radius: 10px;
  padding: 12px;
}
dl div {
  margin-bottom: 10px;
}
dt {
  font-size: 13px;
  color: #716380;
}
dd {
  margin: 5px 0 0;
}
footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
button {
  border: 1px solid #d9d0e5;
  background: #f1ebfa;
  color: #593e80;
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  font: inherit;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
[role='alert'] {
  color: #a52d45;
}
</style>
