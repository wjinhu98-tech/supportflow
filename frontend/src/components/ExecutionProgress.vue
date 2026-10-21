<script setup lang="ts">
import { computed } from 'vue'
import { RefreshCw } from '@lucide/vue'
import type { AfterSaleExecution } from '../types'
import { formatAmount, formatDate } from './format'

const props = defineProps<{
  execution: AfterSaleExecution | null
  canRetry?: boolean
  busy?: boolean
}>()
const emit = defineEmits<{ retry: [] }>()
const statusText = computed(() =>
  props.execution
    ? {
        PENDING: '等待执行',
        PROCESSING: '处理中',
        SUCCEEDED: '模拟退款成功',
        FAILED: '执行失败',
      }[props.execution.status]
    : '暂无执行任务',
)
</script>

<template>
  <section
    class="execution-progress"
    data-testid="execution-progress"
    :data-status="execution?.status ?? 'NONE'"
    aria-label="模拟退款处理进度"
  >
    <div class="execution-heading">
      <h3>模拟退款处理</h3>
      <strong class="execution-status">{{ statusText }}</strong>
    </div>
    <template v-if="execution">
      <dl class="execution-facts">
        <div>
          <dt>处理金额</dt>
          <dd>{{ formatAmount(execution.amount, execution.currency) }}</dd>
        </div>
        <div>
          <dt>执行次数</dt>
          <dd>{{ execution.attemptCount }} / {{ execution.maxAttempts }}</dd>
        </div>
        <div v-if="execution.nextAttemptAt">
          <dt>下次执行</dt>
          <dd>{{ formatDate(execution.nextAttemptAt, true) }}</dd>
        </div>
        <div v-if="execution.completedAt">
          <dt>完成时间</dt>
          <dd>{{ formatDate(execution.completedAt, true) }}</dd>
        </div>
        <div v-if="execution.reference">
          <dt>模拟凭证</dt>
          <dd data-testid="execution-reference">{{ execution.reference }}</dd>
        </div>
      </dl>
      <p
        v-if="execution.status === 'PENDING'"
        class="execution-note"
      >
        任务已保存，后台会继续处理。可刷新查看最新进度。
      </p>
      <p
        v-else-if="execution.status === 'PROCESSING'"
        class="execution-note"
      >
        后台正在执行，请稍后刷新查看结果。
      </p>
      <p
        v-if="execution.lastError"
        class="execution-error"
        data-testid="execution-error"
      >
        失败原因：{{ execution.lastError }}
      </p>
      <p
        v-if="execution.status === 'FAILED' && execution.retryable && execution.nextAttemptAt"
        class="execution-note"
      >
        后台将按下次执行时间自动重试，管理员也可提前安排重试。
      </p>
      <p
        v-if="execution.status === 'FAILED' && !execution.retryable"
        class="execution-note"
      >
        {{
          execution.attemptCount >= execution.maxAttempts
            ? '已达到执行次数上限，请联系管理员核查记录。'
            : '当前任务不可重试，请管理员核查订单或审批信息。'
        }}
      </p>
      <button
        v-if="canRetry && execution.status === 'FAILED' && execution.retryable"
        type="button"
        class="execution-retry"
        data-testid="retry-execution"
        :disabled="busy"
        @click="emit('retry')"
      >
        <RefreshCw
          :size="15"
          aria-hidden="true"
        />
        {{ busy ? '正在提交' : '重试模拟退款' }}
      </button>
    </template>
    <p
      v-else
      class="execution-note"
    >
      该审批记录未创建处理任务，历史审批不会自动补执行。
    </p>
    <p class="execution-disclaimer">学习版模拟处理，凭证仅供演示，不代表真实资金到账。</p>
  </section>
</template>

<style scoped>
.execution-progress {
  margin-top: 18px;
  padding: 16px;
  border: 1px solid #e2d7ed;
  border-radius: 9px;
  background: #faf7fd;
  color: #695377;
}
.execution-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
}
.execution-heading h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.execution-status {
  font-size: 14px;
  font-weight: 500;
  color: #7c5e9c;
}
[data-status='SUCCEEDED'] .execution-status {
  color: #426e53;
}
[data-status='FAILED'] .execution-status {
  color: #a4554c;
}
.execution-facts {
  margin: 14px 0;
  display: grid;
  gap: 10px;
  font-size: 14px;
}
.execution-facts > div {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 10px;
}
.execution-facts dt {
  color: #8b769b;
}
.execution-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.execution-note,
.execution-error,
.execution-disclaimer {
  margin: 10px 0 0;
  line-height: 1.8;
  font-size: 14px;
  overflow-wrap: anywhere;
}
.execution-error {
  color: #a4554c;
  white-space: pre-wrap;
}
.execution-disclaimer {
  color: #897596;
}
.execution-retry {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-top: 13px;
  min-height: 40px;
  padding: 9px 13px;
  border: 1px solid #cbb5dc;
  border-radius: 7px;
  color: #765590;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
.execution-retry:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.execution-retry:focus-visible {
  outline: 3px solid #ab90cf;
  outline-offset: 3px;
}
</style>
