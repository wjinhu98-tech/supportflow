<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { Order, AfterSaleEligibility } from '../types'
import { formatDate } from './format'
import {
  REFUND_REASON_GROUPS,
  MAX_OTHER_REASON_LENGTH,
  MAX_REFUND_REASON_LENGTH,
  formatRefundReason,
  type RefundReasonCategory,
} from './refundReasons'

const props = defineProps<{
  orders: Order[]
  order: Order | null
  reason: string
  eligibility: AfterSaleEligibility | null
  loading: boolean
  submitting: boolean
  error: string | null
  more: boolean
  ordersLoading: boolean
}>()
const emit = defineEmits<{
  close: []
  choose: [order: Order]
  submit: []
  more: []
  'update:reason': [value: string]
}>()
const dialog = ref<HTMLDialogElement | null>(null)
const reasonCategory = ref<RefundReasonCategory | ''>('')
const detailCode = ref('')
const otherReason = ref('')
const detailOptions = computed(
  () => REFUND_REASON_GROUPS.find((group) => group.code === reasonCategory.value)?.options ?? [],
)
const formattedReason = computed(() =>
  formatRefundReason(reasonCategory.value, detailCode.value, otherReason.value),
)
const canSubmit = computed(
  () =>
    !!props.order &&
    props.eligibility?.canSubmit &&
    !!formattedReason.value &&
    props.reason === formattedReason.value &&
    props.reason.length <= MAX_REFUND_REASON_LENGTH &&
    !props.loading &&
    !props.submitting,
)

function chooseCategory(category: RefundReasonCategory) {
  if (props.submitting) return
  reasonCategory.value = category
  detailCode.value = ''
  otherReason.value = ''
  emit('update:reason', '')
}

function chooseDetail(event: Event) {
  if (props.submitting) return
  detailCode.value = (event.target as HTMLSelectElement).value
  emit('update:reason', formattedReason.value)
}

function changeOtherReason(event: Event) {
  if (props.submitting) return
  otherReason.value = (event.target as HTMLTextAreaElement).value
  emit('update:reason', formattedReason.value)
}

function resetReason() {
  reasonCategory.value = ''
  detailCode.value = ''
  otherReason.value = ''
  emit('update:reason', '')
}

watch(() => props.order, resetReason)
watch(
  () => props.reason,
  (value) => {
    // A parent context reset must also clear the local selections, without undoing a new category choice.
    if (!value && formattedReason.value) resetReason()
  },
)
function close() {
  if (!props.submitting) emit('close')
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog
    ref="dialog"
    class="refund-dialog"
    aria-labelledby="refund-title"
    @cancel.prevent="close"
  >
    <header>
      <h2 id="refund-title">申请退款</h2>
      <button
        type="button"
        :disabled="submitting"
        @click="close"
      >
        关闭
      </button>
    </header>
    <p>请选择订单和退款原因。提交后由客服审核，当前为演示环境，不会实际返款。</p>
    <label for="refund-order">退款订单</label>
    <select
      id="refund-order"
      :value="order?.orderNo ?? ''"
      :disabled="submitting"
      @change="
        orders.find((item) => item.orderNo === ($event.target as HTMLSelectElement).value) &&
        emit(
          'choose',
          orders.find((item) => item.orderNo === ($event.target as HTMLSelectElement).value)!,
        )
      "
    >
      <option
        disabled
        value=""
      >
        请选择订单
      </option>
      <option
        v-if="order && !orders.some((item) => item.orderNo === order?.orderNo)"
        :value="order.orderNo"
      >
        {{ order.orderNo }}
      </option>
      <option
        v-for="item in orders"
        :key="item.orderNo"
        :value="item.orderNo"
      >
        {{ item.orderNo }} · {{ item.statusDescription }}
      </option>
    </select>
    <button
      v-if="more"
      type="button"
      :disabled="ordersLoading || submitting"
      @click="emit('more')"
    >
      加载更多订单
    </button>
    <p
      v-if="loading"
      role="status"
    >
      正在核实售后期限…
    </p>
    <section
      v-if="eligibility"
      class="eligibility"
      role="status"
    >
      <p>{{ eligibility.message }}</p>
      <p v-if="eligibility.receivedAt">确认收货：{{ formatDate(eligibility.receivedAt, true) }}</p>
      <p v-if="eligibility.deadlineAt && eligibility.status !== 'REQUIRES_REVIEW'">
        申请截止：{{ formatDate(eligibility.deadlineAt, true) }}
      </p>
    </section>
    <form @submit.prevent="canSubmit && emit('submit')">
      <fieldset
        class="reason-categories"
        :disabled="submitting"
      >
        <legend>退款原因分类</legend>
        <div class="category-options">
          <label
            v-for="group in REFUND_REASON_GROUPS"
            :key="group.code"
            class="category-option"
            :class="{ selected: reasonCategory === group.code }"
          >
            <input
              type="radio"
              name="refund-reason-category"
              :value="group.code"
              :checked="reasonCategory === group.code"
              required
              @change="chooseCategory(group.code)"
            />
            {{ group.label }}
          </label>
          <label
            class="category-option"
            :class="{ selected: reasonCategory === 'OTHER' }"
          >
            <input
              type="radio"
              name="refund-reason-category"
              value="OTHER"
              :checked="reasonCategory === 'OTHER'"
              required
              @change="chooseCategory('OTHER')"
            />
            其他
          </label>
        </div>
      </fieldset>
      <template v-if="reasonCategory && reasonCategory !== 'OTHER'">
        <label for="refund-reason-detail">具体原因</label>
        <select
          id="refund-reason-detail"
          :value="detailCode"
          :disabled="submitting"
          required
          @change="chooseDetail"
        >
          <option
            value=""
            disabled
          >
            请选择具体原因
          </option>
          <option
            v-for="option in detailOptions"
            :key="option.code"
            :value="option.code"
          >
            {{ option.label }}
          </option>
        </select>
      </template>
      <template v-if="reasonCategory === 'OTHER'">
        <label for="refund-reason-other">其他原因</label>
        <textarea
          id="refund-reason-other"
          :value="otherReason"
          :maxlength="MAX_OTHER_REASON_LENGTH"
          required
          rows="4"
          :disabled="submitting"
          placeholder="请说明具体退款原因"
          @input="changeOtherReason"
        />
        <small>{{ otherReason.length }}/{{ MAX_OTHER_REASON_LENGTH }} 字</small>
      </template>
      <p class="reason-note">原因由你选择，具体情况将由客服核实。</p>
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
          取消
        </button>
        <button
          type="submit"
          :disabled="!canSubmit"
        >
          {{ submitting ? '正在提交…' : '确认提交退款申请' }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.refund-dialog {
  width: min(540px, calc(100vw - 32px));
  border: 1px solid #e4ddf0;
  border-radius: 20px;
  padding: 24px;
  color: #39324c;
  max-height: calc(100dvh - 32px);
  overflow-y: auto;
  box-sizing: border-box;
}
.refund-dialog::backdrop {
  background: #211b3655;
}
header,
footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
h2 {
  margin: 0;
  font-size: 20px;
}
p {
  line-height: 1.6;
}
label {
  display: block;
  margin: 16px 0 8px;
  font-weight: 600;
}
select,
textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #d9d0e5;
  border-radius: 8px;
  padding: 10px;
  font: inherit;
  background: white;
}
textarea {
  resize: vertical;
}
button {
  border: 1px solid #d9d0e5;
  background: #f1ebfa;
  color: #593e80;
  border-radius: 8px;
  padding: 9px 13px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
footer {
  justify-content: flex-end;
  margin-top: 20px;
}
.eligibility {
  background: #f5f2fa;
  border-radius: 10px;
  padding: 1px 12px;
  margin-top: 14px;
}
[role='alert'] {
  color: #a52d45;
}
small {
  color: #776b86;
}
.reason-categories {
  border: 0;
  padding: 0;
  margin: 20px 0 0;
  min-width: 0;
}
.reason-categories legend {
  font-weight: 600;
  margin-bottom: 8px;
}
.category-options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.category-option {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 10px 6px;
  border: 1px solid #d9d0e5;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
}
.category-option.selected {
  background: #f1ebfa;
  border-color: #a08bc0;
}
.category-option input {
  accent-color: #7955aa;
  margin: 0;
}
.reason-note {
  font-size: 12px;
  color: #776b86;
}
</style>
