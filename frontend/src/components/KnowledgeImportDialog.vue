<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { KnowledgeImportPreview } from '../types'
const props = defineProps<{
  preview: KnowledgeImportPreview | null
  title: string
  keywords: string
  loading: boolean
  submitting: boolean
  error: string | null
  needsVerification: boolean
}>()
const emit = defineEmits<{
  close: []
  file: [file: File | null]
  confirm: []
  'update:title': [value: string]
  'update:keywords': [value: string]
}>()
const dialog = ref<HTMLDialogElement | null>(null)
function close() {
  if (!props.submitting) emit('close')
}
function choose(event: Event) {
  emit('file', (event.target as HTMLInputElement).files?.[0] ?? null)
}
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>
<template>
  <dialog
    ref="dialog"
    class="knowledge-import-dialog"
    aria-labelledby="knowledge-import-title"
    data-testid="knowledge-import-dialog"
    @cancel.prevent="close"
  >
    <h2 id="knowledge-import-title">导入知识文件</h2>
    <p>支持 UTF-8 编码的 TXT、Markdown，文件不超过64 KiB，正文不超过4000字。资料在本地处理。</p>
    <label>
      选择文件
      <input
        data-testid="knowledge-import-file"
        type="file"
        accept=".txt,.md,.markdown"
        :disabled="submitting"
        @change="choose"
      />
    </label>
    <p
      v-if="loading"
      role="status"
    >
      正在解析预览，尚未保存草稿…
    </p>
    <template v-if="preview">
      <label>
        标题
        <input
          :value="title"
          maxlength="120"
          data-testid="knowledge-import-title"
          :disabled="submitting"
          @input="emit('update:title', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        关键词（逗号分隔）
        <input
          :value="keywords"
          data-testid="knowledge-import-keywords"
          :disabled="submitting"
          @input="emit('update:keywords', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <p>正文预览 · {{ preview.characterCount }} 字符</p>
      <pre data-testid="knowledge-import-preview">{{ preview.content }}</pre>
      <p>确认后仅保存为草稿。发布前不会用于买家咨询；重复导入不会覆盖原文档。</p>
    </template>
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
        取消导入
      </button>
      <button
        type="button"
        data-testid="knowledge-import-confirm"
        :disabled="!preview || loading || submitting || needsVerification"
        @click="emit('confirm')"
      >
        {{ submitting ? '正在保存…' : '确认保存为草稿' }}
      </button>
    </footer>
  </dialog>
</template>
<style scoped>
.knowledge-import-dialog {
  box-sizing: border-box;
  width: min(680px, calc(100vw - 32px));
  max-height: 90dvh;
  overflow: auto;
  border: 1px solid #e5dced;
  border-radius: 16px;
  padding: 24px;
  color: #42374f;
}
.knowledge-import-dialog::backdrop {
  background: #211b3655;
}
h2 {
  margin-top: 0;
}
p {
  line-height: 1.7;
}
label {
  display: grid;
  gap: 8px;
  margin: 16px 0;
}
input {
  box-sizing: border-box;
  width: 100%;
  padding: 10px;
  border: 1px solid #d9d0e5;
  border-radius: 8px;
  font: inherit;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 240px;
  overflow: auto;
  background: #f7f4fb;
  padding: 16px;
  border-radius: 8px;
  font: inherit;
  line-height: 1.7;
}
footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
button {
  padding: 10px 14px;
  border: 1px solid #d9d0e5;
  border-radius: 8px;
  background: #f1ebfa;
  color: #593e80;
  font: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
[role='alert'] {
  color: #a52d45;
}
</style>
