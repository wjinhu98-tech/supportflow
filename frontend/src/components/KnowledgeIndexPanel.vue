<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeIndex } from '../types'

const props = defineProps<{
  index: KnowledgeIndex | null
  loading: boolean
  preparing: boolean
  blocked: boolean
  error: string | null
}>()
const emit = defineEmits<{ refresh: []; prepare: [] }>()
const description = computed(() => {
  if (props.preparing) return '正在准备当前店铺的检索资料，请稍候。'
  if (props.loading) return '正在读取准备状态…'
  if (!props.index) return '尚未读取检索准备状态。'
  return {
    DISABLED: '增强检索尚未启用，仍可使用基础检索。',
    EMPTY: '当前没有可准备的已发布资料。',
    PARTIAL: '部分资料尚未准备，可继续准备剩余内容。',
    READY: '当前发布资料已准备完成。',
    UNAVAILABLE: '暂时无法准备，仍可使用基础检索，请稍后重试。',
    BUSY: '已有准备任务正在处理，请稍后刷新状态。',
  }[props.index.status]
})
</script>

<template>
  <section
    class="knowledge-index"
    data-testid="knowledge-index-panel"
    aria-label="知识检索准备"
  >
    <div>
      <strong>知识检索准备</strong>
      <p role="status">{{ description }}</p>
      <small v-if="index && index.expectedChunks > 0">
        已准备 {{ index.indexedChunks }} / {{ index.expectedChunks }} 段资料
      </small>
      <p
        v-if="error"
        role="alert"
      >
        {{ error }}
      </p>
    </div>
    <div class="index-actions">
      <button
        type="button"
        data-testid="knowledge-index-refresh"
        :disabled="blocked || loading || preparing"
        @click="emit('refresh')"
      >
        刷新状态
      </button>
      <button
        type="button"
        data-testid="knowledge-index-prepare"
        :disabled="
          blocked ||
          loading ||
          preparing ||
          !index ||
          ['DISABLED', 'EMPTY', 'READY'].includes(index.status)
        "
        @click="emit('prepare')"
      >
        {{ preparing ? '准备中…' : '准备检索索引' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.knowledge-index {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 22px;
  margin-bottom: 18px;
  border: 1px solid #e6deee;
  border-radius: 16px;
  background: #fff;
  color: #51435d;
}
p {
  margin: 7px 0;
  font-size: 14px;
  line-height: 1.6;
}
small {
  color: #776c83;
}
[role='alert'] {
  color: #a43f53;
}
.index-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  flex-shrink: 0;
}
button {
  border: 1px solid #d7c9e6;
  border-radius: 9px;
  padding: 9px 13px;
  background: #faf7ff;
  color: #694f91;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
@media (max-width: 600px) {
  .knowledge-index {
    align-items: flex-start;
    flex-direction: column;
    padding: 16px;
    gap: 10px;
  }
}
</style>
