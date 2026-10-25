<script setup lang="ts">
import { ref, useId, watch } from 'vue'
import { ChevronDown, BookOpen } from '@lucide/vue'
import type { ChatKnowledge } from '../types'

const props = defineProps<{ knowledge: ChatKnowledge; historical?: boolean }>()
const contentId = useId()
const expanded = ref(new Set<string>())

watch(
  () => [props.knowledge, props.historical],
  () => {
    expanded.value = new Set()
  },
)

function toggle(sourceId: string) {
  const next = new Set(expanded.value)
  if (next.has(sourceId)) next.delete(sourceId)
  else next.add(sourceId)
  expanded.value = next
}
</script>

<template>
  <section
    v-if="knowledge.status === 'ANSWERED' && knowledge.sources.length"
    class="chat-knowledge-sources"
    aria-label="本条回答的参考来源"
    data-testid="knowledge-sources"
  >
    <p class="chat-knowledge-heading">
      <BookOpen
        :size="13"
        aria-hidden="true"
      />
      参考来源
    </p>
    <p
      v-if="historical"
      class="chat-knowledge-note"
      data-testid="knowledge-historical-snapshot"
    >
      历史引用快照 · 保留当时版本，不代表资料当前仍有效
    </p>
    <article
      v-for="(source, index) in knowledge.sources"
      :key="source.sourceId"
      class="chat-knowledge-source"
      data-testid="knowledge-source"
    >
      <button
        type="button"
        class="chat-knowledge-toggle"
        :aria-expanded="expanded.has(source.sourceId)"
        :aria-controls="`${contentId}-${index}`"
        data-testid="knowledge-source-toggle"
        @click="toggle(source.sourceId)"
      >
        <span class="chat-knowledge-title">{{ source.title }}</span>
        <span class="chat-knowledge-version">版本 {{ source.version }}</span>
        <ChevronDown
          :size="15"
          :class="{ 'is-expanded': expanded.has(source.sourceId) }"
          aria-hidden="true"
        />
      </button>
      <div
        v-show="expanded.has(source.sourceId)"
        :id="`${contentId}-${index}`"
        class="chat-knowledge-content"
        data-testid="knowledge-source-content"
      >
        <p>{{ source.content }}</p>
        <span class="chat-knowledge-source-id">来源编号：{{ source.sourceId }}</span>
      </div>
    </article>
  </section>
</template>

<style scoped>
.chat-knowledge-sources {
  min-width: 0;
  max-width: 100%;
  margin-top: 10px;
}
.chat-knowledge-heading {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 6px;
  color: #756184;
  font-size: 12px;
}
.chat-knowledge-note {
  margin: 0 0 7px;
  color: #756c80;
  font-size: 11px;
  line-height: 1.6;
}
.chat-knowledge-source {
  min-width: 0;
  max-width: 100%;
  margin-top: 6px;
  border: 1px solid #e9e2f0;
  border-radius: 9px;
  background: #fff;
}
.chat-knowledge-toggle {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
  padding: 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #665374;
  text-align: left;
  cursor: pointer;
}
.chat-knowledge-toggle:focus-visible {
  outline: 2px solid #8f66b8;
  outline-offset: 2px;
}
.chat-knowledge-title {
  min-width: 0;
  flex: 1 1 120px;
  font-size: 12px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.chat-knowledge-version {
  min-width: 0;
  max-width: 100%;
  color: #887694;
  font-size: 11px;
  overflow-wrap: anywhere;
}
.chat-knowledge-toggle svg {
  flex-shrink: 0;
  transition: transform 0.15s;
}
.chat-knowledge-toggle svg.is-expanded {
  transform: rotate(180deg);
}
.chat-knowledge-content {
  min-width: 0;
  padding: 0 10px 10px;
  border-top: 1px solid #f2edf6;
}
.chat-knowledge-content p {
  margin: 9px 0;
  color: #6b5f75;
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.chat-knowledge-source-id {
  color: #887694;
  font-size: 10px;
  overflow-wrap: anywhere;
}
</style>
