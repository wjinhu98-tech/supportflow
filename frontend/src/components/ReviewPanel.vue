<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Check,
  ChevronDown,
  ClipboardCheck,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  X,
} from '@lucide/vue'
import type { AfterSaleTicket } from '../types'
import ExecutionProgress from './ExecutionProgress.vue'
import { formatDate } from './format'

const props = defineProps<{
  tickets: AfterSaleTicket[]
  historyTickets: AfterSaleTicket[]
  view: 'pending' | 'history'
  loading: boolean
  submitting: boolean
  error: string | null
}>()

const emit = defineEmits<{
  refresh: [view: 'pending' | 'history']
  retry: [id: string]
  close: []
  decide: [payload: { id: string; decision: 'APPROVE' | 'REJECT'; note: string }]
}>()

const dialog = ref<HTMLDialogElement | null>(null)
const notes = reactive<Record<string, string>>({})
const validationErrors = reactive<Record<string, string>>({})
const visibleTickets = computed(() =>
  (props.view === 'pending' ? props.tickets : props.historyTickets).slice(0, 50),
)
const disabled = computed(() => props.loading || props.submitting)
let closing = false

function requestClose() {
  if (closing || props.submitting) return
  closing = true
  dialog.value?.close()
  emit('close')
}

function closeOnBackdrop(event: MouseEvent) {
  if (event.target === dialog.value) requestClose()
}

function submitDecision(
  ticket: AfterSaleTicket,
  decision: 'APPROVE' | 'REJECT',
  event: MouseEvent,
) {
  if (disabled.value || ticket.status !== 'PENDING_REVIEW') return
  const form = (event.currentTarget as HTMLButtonElement).form
  if (!form?.reportValidity()) return
  const note = (notes[ticket.id] ?? '').trim()
  if (!note || note.length > 500) {
    validationErrors[ticket.id] = '请填写 1–500 字的有效审核备注，不能只输入空格。'
    form.querySelector<HTMLTextAreaElement>('textarea')?.focus()
    return
  }
  validationErrors[ticket.id] = ''
  emit('decide', { id: ticket.id, decision, note })
}

function statusLabel(status: AfterSaleTicket['status']) {
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
    class="review-dialog"
    data-testid="review-panel"
    aria-labelledby="review-title"
    aria-describedby="review-scope"
    @cancel.prevent="requestClose"
    @close="requestClose"
    @click="closeOnBackdrop"
  >
    <header class="review-header">
      <div class="review-heading">
        <span class="review-symbol">
          <ClipboardCheck
            :size="23"
            aria-hidden="true"
          />
        </span>
        <div>
          <p class="review-eyebrow">HUMAN IN THE LOOP</p>
          <h2 id="review-title">售后人工审批与处理</h2>
        </div>
      </div>
      <button
        type="button"
        class="review-close"
        aria-label="关闭人工审批"
        :disabled="submitting"
        @click="requestClose"
      >
        <X
          :size="21"
          aria-hidden="true"
        />
      </button>
    </header>

    <div class="review-body">
      <div
        id="review-scope"
        class="review-scope"
      >
        <ShieldCheck
          :size="20"
          aria-hidden="true"
        />
        <p>
          <strong>全平台管理员队列，不限当前店铺。</strong>
          <br />
          需要 AFTER_SALE_REVIEW 权限；订单归属、审批权限和禁止申请人自审，均由 Java 服务端验证。
        </p>
      </div>

      <div
        class="review-tabs"
        role="group"
        aria-label="售后管理视图"
      >
        <button
          type="button"
          data-testid="review-tab-pending"
          :aria-pressed="view === 'pending'"
          :disabled="disabled"
          @click="emit('refresh', 'pending')"
        >
          待审批
        </button>
        <button
          type="button"
          data-testid="review-tab-history"
          :aria-pressed="view === 'history'"
          :disabled="disabled"
          @click="emit('refresh', 'history')"
        >
          处理进度
        </button>
      </div>
      <div class="review-toolbar">
        <p>
          {{ view === 'pending' ? '待处理申请' : '最近审批与执行记录' }}
          <span v-if="!loading">{{ visibleTickets.length }} 条</span>
        </p>
        <button
          type="button"
          class="review-refresh"
          data-testid="refresh-reviews"
          :disabled="disabled"
          @click="emit('refresh', view)"
        >
          <LoaderCircle
            v-if="loading"
            :size="16"
            class="review-spinning"
            aria-hidden="true"
          />
          <RefreshCw
            v-else
            :size="16"
            aria-hidden="true"
          />
          {{ loading ? '正在刷新' : view === 'pending' ? '刷新队列' : '刷新进度' }}
        </button>
      </div>

      <p
        v-if="error"
        class="review-error"
        role="alert"
      >
        {{ error }}
      </p>
      <div
        v-if="loading && !visibleTickets.length"
        class="review-empty"
        role="status"
      >
        <LoaderCircle
          :size="25"
          class="review-spinning"
          aria-hidden="true"
        />
        <h3>{{ view === 'pending' ? '正在读取待审申请' : '正在读取处理进度' }}</h3>
        <p>请稍候，不会自动批准任何申请。</p>
      </div>
      <div
        v-else-if="!visibleTickets.length && !error"
        class="review-empty"
      >
        <span class="review-empty-symbol">
          <Check
            :size="28"
            aria-hidden="true"
          />
        </span>
        <h3>{{ view === 'pending' ? '暂无待审申请' : '暂无处理记录' }}</h3>
        <p>
          {{
            view === 'pending'
              ? '新的售后申请会在这里等待人工处理。'
              : '审批与模拟退款结果会在这里展示。'
          }}
        </p>
      </div>

      <div
        v-if="visibleTickets.length"
        class="review-list"
        :aria-busy="loading || submitting"
      >
        <details
          v-for="(ticket, index) in visibleTickets"
          :key="ticket.id"
          class="review-card"
          data-testid="review-card"
          :open="index === 0"
        >
          <summary class="review-summary">
            <div class="review-ticket-heading">
              <span class="review-order-number">{{ ticket.orderNo }}</span>
              <span
                class="review-status"
                :data-status="ticket.status"
              >
                {{ statusLabel(ticket.status) }}
              </span>
              <span class="review-shop">店铺 {{ ticket.shopId }}</span>
            </div>
            <span class="review-expand">
              <ChevronDown
                :size="18"
                aria-hidden="true"
              />
            </span>
          </summary>
          <div class="review-card-content">
            <dl class="review-facts">
              <div>
                <dt>售后单号</dt>
                <dd>{{ ticket.id }}</dd>
              </div>
              <div>
                <dt>申请时间</dt>
                <dd>{{ formatDate(ticket.createdAt, true) }}</dd>
              </div>
            </dl>
            <section
              class="review-reason"
              :aria-labelledby="`review-reason-${ticket.id}`"
            >
              <h3 :id="`review-reason-${ticket.id}`">申请原因</h3>
              <p>{{ ticket.reason }}</p>
              <p>
                {{
                  ticket.eligibility?.status === 'ELIGIBLE'
                    ? '提交申请时在售后期限内；请按申请时的核验记录审核。'
                    : '收货时间与售后期限需人工核实，请在审核意见中记录核实结果。'
                }}
              </p>
              <p v-if="ticket.eligibility?.checkedAt">
                申请时核验时间：{{ formatDate(ticket.eligibility.checkedAt, true) }}
              </p>
              <p v-if="ticket.eligibility?.status === 'ELIGIBLE' && ticket.eligibility.deadlineAt">
                申请截止：{{ formatDate(ticket.eligibility.deadlineAt, true) }}
              </p>
            </section>

            <form
              v-if="ticket.status === 'PENDING_REVIEW'"
              class="review-form"
              @submit.prevent
            >
              <label :for="`review-note-${ticket.id}`">
                审核备注
                <span>必填 · 1–500 字</span>
              </label>
              <textarea
                :id="`review-note-${ticket.id}`"
                v-model="notes[ticket.id]"
                data-testid="review-note"
                name="note"
                rows="3"
                required
                maxlength="500"
                :disabled="disabled"
                :aria-invalid="Boolean(validationErrors[ticket.id])"
                :aria-describedby="
                  validationErrors[ticket.id] ? `review-note-error-${ticket.id}` : undefined
                "
                placeholder="请说明批准或拒绝的依据，备注将作为审核记录保存。"
                @input="validationErrors[ticket.id] = ''"
              ></textarea>
              <p
                v-if="validationErrors[ticket.id]"
                :id="`review-note-error-${ticket.id}`"
                class="review-field-error"
                role="alert"
              >
                {{ validationErrors[ticket.id] }}
              </p>
              <div class="review-decision-row">
                <span class="review-note-count">{{ (notes[ticket.id] ?? '').length }} / 500</span>
                <div class="review-decision-buttons">
                  <button
                    type="button"
                    class="review-reject"
                    data-testid="reject-review"
                    :disabled="disabled"
                    @click="submitDecision(ticket, 'REJECT', $event)"
                  >
                    <X
                      :size="16"
                      aria-hidden="true"
                    />
                    拒绝
                  </button>
                  <button
                    type="button"
                    class="review-approve"
                    data-testid="approve-review"
                    :disabled="disabled"
                    @click="submitDecision(ticket, 'APPROVE', $event)"
                  >
                    <LoaderCircle
                      v-if="submitting"
                      :size="16"
                      class="review-spinning"
                      aria-hidden="true"
                    />
                    <Check
                      v-else
                      :size="16"
                      aria-hidden="true"
                    />
                    {{ submitting ? '正在提交' : '确认批准' }}
                  </button>
                </div>
              </div>
            </form>
            <section
              v-else
              class="review-result"
              aria-label="已完成的审核"
            >
              <p>此申请已完成审核，不再重复处理。</p>
              <dl class="review-facts">
                <div>
                  <dt>审核人</dt>
                  <dd>{{ ticket.reviewer || '未提供' }}</dd>
                </div>
                <div>
                  <dt>审核时间</dt>
                  <dd>{{ formatDate(ticket.updatedAt, true) }}</dd>
                </div>
              </dl>
              <p class="review-final-note">{{ ticket.reviewNote || '未提供审核备注' }}</p>
              <ExecutionProgress
                v-if="ticket.status === 'APPROVED'"
                :execution="ticket.execution"
                can-retry
                :busy="disabled"
                @retry="emit('retry', ticket.id)"
              />
            </section>
          </div>
        </details>
      </div>
      <p
        v-if="visibleTickets.length >= 50"
        class="review-limit"
      >
        本次最多展示 50 条申请，请刷新查看最新状态。
      </p>
    </div>

    <footer class="review-footer">
      <ShieldCheck
        :size="17"
        aria-hidden="true"
      />
      <p>批准后创建持久化任务并执行学习版模拟退款；模拟处理结果不代表真实资金到账。</p>
    </footer>
  </dialog>
</template>

<style scoped>
.review-dialog {
  width: min(900px, 90vw);
  max-width: 90vw;
  max-height: 90dvh;
  padding: 0;
  border: 1px solid #e3d9ee;
  border-radius: 17px;
  background: #fff;
  color: #42374f;
  box-shadow: 0 24px 90px #28173238;
  font-size: 14px;
  overflow: hidden;
}
.review-dialog[open] {
  display: flex;
  flex-direction: column;
}
.review-dialog::backdrop {
  background: #29233370;
  backdrop-filter: blur(5px);
}
.review-header {
  padding: 24px 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #eee7f4;
  flex-shrink: 0;
}
.review-heading {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
}
.review-symbol {
  width: 47px;
  height: 47px;
  background: #f0e9f8;
  color: #8969ad;
  border-radius: 14px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.review-eyebrow {
  margin: 0 0 4px;
  font-size: 14px;
  color: #857091;
  letter-spacing: 1px;
}
.review-heading h2 {
  margin: 0;
  font-size: 24px;
  line-height: 1.4;
  font-weight: 600;
}
.review-close {
  width: 38px;
  height: 38px;
  padding: 8px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #8c789c;
  flex-shrink: 0;
}
.review-close:hover:not(:disabled) {
  background: #f3edf9;
  color: #6e4e91;
}
.review-body {
  overflow-y: auto;
  padding: 22px 28px 26px;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: #dcd1e7 transparent;
}
.review-scope {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #e9def2;
  border-radius: 9px;
  background: #faf7fd;
  color: #7a658d;
}
.review-scope > svg {
  flex-shrink: 0;
  margin-top: 3px;
}
.review-scope p {
  margin: 0;
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.review-scope strong {
  color: #705386;
  font-weight: 600;
}
.review-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin: 22px 0 15px;
}
.review-tabs {
  display: flex;
  gap: 9px;
  margin-top: 20px;
}
.review-tabs button {
  border: 1px solid #e4d9ef;
  border-radius: 7px;
  padding: 10px 16px;
  min-height: 40px;
  background: #fff;
  color: #7d5d99;
  font: inherit;
  cursor: pointer;
}
.review-tabs button[aria-pressed='true'] {
  background: #f0e9f8;
  border-color: #cbb5dc;
  color: #644884;
  font-weight: 600;
}
.review-toolbar p {
  margin: 0;
  font-size: 15px;
  font-weight: 550;
  color: #62506f;
}
.review-toolbar p span {
  margin-left: 8px;
  font-size: 14px;
  color: #85718f;
  font-weight: 400;
}
.review-refresh,
.review-reject,
.review-approve {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 40px;
  padding: 9px 14px;
  border: 1px solid #e4d9ef;
  border-radius: 7px;
  background: #fff;
  color: #7d5d99;
  font-size: 14px;
  cursor: pointer;
}
.review-refresh:hover:not(:disabled),
.review-reject:hover:not(:disabled) {
  background: #f7f1fc;
  border-color: #c9b3dd;
}
.review-approve {
  background: #7660bc;
  color: #fff;
  border-color: #7660bc;
}
.review-approve:hover:not(:disabled) {
  background: #644eaa;
}
.review-dialog button:disabled,
.review-dialog textarea:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.review-dialog button:focus-visible,
.review-dialog summary:focus-visible,
.review-dialog textarea:focus-visible {
  outline: 3px solid #ab90cf;
  outline-offset: 3px;
}
.review-error,
.review-field-error {
  color: #9c594d;
  background: #fff7f3;
  border: 1px solid #edd3ca;
  padding: 12px 14px;
  border-radius: 7px;
  margin: 0 0 15px;
  font-size: 14px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}
.review-list {
  display: flex;
  flex-direction: column;
  gap: 13px;
}
.review-card {
  min-width: 0;
  border: 1px solid #e6dced;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.review-summary {
  cursor: pointer;
  list-style: none;
  padding: 17px 19px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fcfafe;
  min-height: 62px;
}
.review-summary::-webkit-details-marker {
  display: none;
}
.review-ticket-heading {
  display: flex;
  align-items: center;
  gap: 11px;
  flex-wrap: wrap;
  min-width: 0;
}
.review-order-number {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: #6f4f88;
  overflow-wrap: anywhere;
}
.review-status {
  font-size: 14px;
  color: #8b6c4f;
  background: #faf0df;
  border-radius: 5px;
  padding: 4px 7px;
  white-space: nowrap;
}
.review-status[data-status='APPROVED'] {
  color: #456e58;
  background: #edf6ef;
}
.review-status[data-status='REJECTED'] {
  color: #9c5e68;
  background: #faedf0;
}
.review-shop {
  font-size: 14px;
  color: #887393;
}
.review-expand {
  color: #9c86b0;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  transition: transform 0.18s;
}
.review-card[open] .review-expand {
  transform: rotate(180deg);
}
.review-card-content {
  padding: 19px;
  border-top: 1px solid #f0e9f5;
}
.review-facts {
  display: flex;
  flex-wrap: wrap;
  column-gap: 28px;
  row-gap: 9px;
  margin: 0 0 18px;
  font-size: 14px;
}
.review-facts > div {
  display: flex;
  gap: 9px;
  min-width: 0;
}
.review-facts dt {
  color: #8a7797;
  white-space: nowrap;
}
.review-facts dd {
  margin: 0;
  color: #655171;
  overflow-wrap: anywhere;
}
.review-reason {
  margin-bottom: 20px;
}
.review-reason h3 {
  margin: 0 0 9px;
  font-size: 14px;
  font-weight: 550;
  color: #7b628c;
}
.review-reason p {
  margin: 0;
  background: #faf8fc;
  border-radius: 7px;
  padding: 12px 14px;
  color: #71617e;
  font-size: 14px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.review-form label {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  color: #735780;
  font-size: 14px;
  font-weight: 550;
  margin-bottom: 9px;
}
.review-form label span {
  color: #8f799d;
  font-size: 14px;
  font-weight: 400;
}
.review-form textarea {
  box-sizing: border-box;
  display: block;
  width: 100%;
  min-height: 102px;
  resize: vertical;
  border: 1px solid #ded1e9;
  border-radius: 7px;
  padding: 11px 12px;
  background: #fff;
  color: #5c456d;
  font: inherit;
  font-size: 14px;
  line-height: 1.85;
}
.review-form textarea::placeholder {
  color: #9783a4;
}
.review-form textarea[aria-invalid='true'] {
  border-color: #c68c7b;
}
.review-field-error {
  margin-top: 10px;
}
.review-decision-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 13px;
}
.review-note-count {
  color: #897496;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.review-decision-buttons {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}
.review-result > p {
  color: #7c658b;
  font-size: 14px;
  line-height: 1.8;
  margin: 0 0 12px;
}
.review-result .review-facts {
  margin-bottom: 12px;
}
.review-result .review-final-note {
  color: #6e577e;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.review-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 40px 15px 46px;
  color: #9b84ad;
}
.review-empty-symbol {
  height: 58px;
  width: 58px;
  border-radius: 17px;
  background: #f2ebf9;
  display: grid;
  place-items: center;
}
.review-empty h3 {
  margin: 17px 0 10px;
  font-size: 18px;
  color: #7c638d;
  font-weight: 500;
}
.review-empty p {
  color: #8b7699;
  font-size: 14px;
  line-height: 1.8;
  margin: 0;
}
.review-limit {
  color: #8a7597;
  font-size: 14px;
  line-height: 1.8;
  margin: 18px 0 0;
}
.review-footer {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 16px 28px;
  border-top: 1px solid #eee7f4;
  background: #fcfafd;
  flex-shrink: 0;
  color: #8a739b;
}
.review-footer > svg {
  flex-shrink: 0;
  margin-top: 3px;
}
.review-footer p {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
}
.review-spinning {
  animation: review-spin 1s linear infinite;
}
@keyframes review-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 600px) {
  .review-header {
    padding: 19px 18px;
  }
  .review-heading {
    gap: 10px;
  }
  .review-heading h2 {
    font-size: 21px;
  }
  .review-eyebrow {
    letter-spacing: 0.4px;
  }
  .review-symbol {
    width: 42px;
    height: 42px;
    border-radius: 12px;
  }
  .review-body {
    padding: 18px 16px 21px;
  }
  .review-scope {
    padding: 12px;
  }
  .review-scope > svg {
    display: none;
  }
  .review-summary {
    padding: 14px;
  }
  .review-ticket-heading {
    gap: 8px;
  }
  .review-card-content {
    padding: 14px;
  }
  .review-form textarea {
    font-size: 16px;
  }
  .review-facts > div {
    flex-wrap: wrap;
    gap: 5px;
  }
  .review-facts {
    column-gap: 18px;
  }
  .review-facts dd {
    min-width: 0;
  }
  .review-decision-buttons {
    gap: 8px;
  }
  .review-reject,
  .review-approve {
    padding: 9px 12px;
  }
  .review-footer {
    padding: 14px 18px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .review-spinning {
    animation: none;
  }
  .review-expand {
    transition: none;
  }
}
</style>
