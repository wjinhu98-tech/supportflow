<script setup lang="ts">
import type { ConversationContextRef } from '../types'

const props = defineProps<{
  context: ConversationContextRef | null
  pendingReset: boolean
  expired: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ clear: [] }>()
function clear() {
  if (!props.disabled && !props.pendingReset) emit('clear')
}
</script>

<template>
  <aside
    v-if="pendingReset || (context && context.kind !== 'NONE')"
    class="conversation-context-notice"
    data-testid="conversation-context"
    role="status"
    aria-live="polite"
  >
    <div class="conversation-context-copy">
      <template v-if="pendingReset">
        <strong data-testid="context-kind">话题清除待发送</strong>
        <p>下一条消息不沿用上次话题。发送成功后生效；发送前刷新或切换会话不会保存此设置。</p>
      </template>
      <template v-else-if="expired">
        <strong data-testid="context-kind">上次话题已过期</strong>
        <p>请明确本次要咨询的订单或知识主题，历史信息不能作为当前结果。</p>
      </template>
      <template v-else-if="context?.kind === 'ORDER'">
        <strong data-testid="context-kind">订单话题：{{ context.orderNo }}</strong>
        <p>仅用于衔接咨询，每次仍会重新核实订单归属和最新状态。</p>
      </template>
      <template v-else>
        <strong data-testid="context-kind">
          知识话题 · {{ context?.sourceIds.length }} 个来源
        </strong>
        <p>后续追问会核对当前发布资料；历史引用不代表内容仍有效。</p>
      </template>
    </div>
    <button
      v-if="!pendingReset"
      type="button"
      class="text-button conversation-context-clear"
      data-testid="context-clear"
      :disabled="disabled"
      @click="clear"
    >
      清除话题
    </button>
  </aside>
</template>

<style scoped>
.conversation-context-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px 16px;
  margin: 0 24px 10px;
  padding: 10px 12px;
  border: 1px solid #e9e2f0;
  border-radius: 9px;
  background: #faf8fd;
  color: #6b567c;
  font-size: 12px;
  line-height: 1.65;
  min-width: 0;
}
.conversation-context-copy {
  flex: 1 1 180px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.conversation-context-copy strong {
  font-weight: 600;
}
.conversation-context-copy p {
  margin: 3px 0 0;
  color: #7c7187;
}
.conversation-context-clear {
  min-height: 28px;
  flex-shrink: 0;
}
@media (max-width: 680px) {
  .conversation-context-notice {
    margin-inline: 12px;
  }
}
</style>
