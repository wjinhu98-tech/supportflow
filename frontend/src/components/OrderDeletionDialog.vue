<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { Order } from '../types'

const props = defineProps<{ order: Order; submitting: boolean; error: string | null }>()
const emit = defineEmits<{ close: []; confirm: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
function close() {
  if (!props.submitting) emit('close')
}
function confirm() {
  if (!props.submitting) emit('confirm')
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog
    ref="dialog"
    class="order-deletion-dialog"
    aria-labelledby="order-deletion-title"
    data-testid="order-deletion-dialog"
    @cancel.prevent="close"
  >
    <h2 id="order-deletion-title">删除这笔订单？</h2>
    <p class="order-reference">订单 {{ order.orderNo }} · 店铺 {{ order.shopId }}</p>
    <p>删除后，这笔订单将从你的订单列表和查单结果中隐藏。</p>
    <p>历史聊天快照、商家记录及售后凭证会保留。有待处理售后的订单暂不能删除。</p>
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
        暂不删除
      </button>
      <button
        type="button"
        class="confirm-delete"
        :disabled="submitting"
        data-testid="confirm-order-deletion"
        @click="confirm"
      >
        {{ submitting ? '正在删除…' : '确认删除订单' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.order-deletion-dialog {
  box-sizing: border-box;
  width: min(500px, calc(100vw - 32px));
  max-height: 90dvh;
  overflow-y: auto;
  border: 1px solid #e4ddf0;
  border-radius: 20px;
  padding: 24px;
  color: #39324c;
}
.order-deletion-dialog::backdrop {
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
.confirm-delete {
  border-color: #e9c6ce;
  background: #fcf0f2;
  color: #a52d45;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
[role='alert'] {
  color: #a52d45;
}
</style>
