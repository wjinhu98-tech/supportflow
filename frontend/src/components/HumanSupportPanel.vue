<script setup lang="ts">
import { ref, watch } from 'vue'
import { formatTime } from './format'
import type { SupportMessage, SupportSession } from '../types'

const props = defineProps<{
  sessions: SupportSession[]
  view: SupportSession['status']
  selected: SupportSession | null
  selectedId: string | null
  messages: SupportMessage[]
  loading: boolean
  detailLoading: boolean
  submitting: boolean
  writable: boolean
  more: boolean
  messageMore: boolean
  error: string | null
  detailError: string | null
  uncertainMessage: { id: string; text: string } | null
  uncertainClose: boolean
}>()
const emit = defineEmits<{
  view: [status: SupportSession['status']]
  refresh: []
  more: []
  open: [id: string]
  claim: []
  detailRefresh: []
  messagesMore: []
  send: [text: string]
  retry: []
  close: []
}>()
const draft = ref('')
watch(
  () => props.selectedId,
  () => {
    draft.value = ''
  },
)
function send() {
  if (
    !props.writable ||
    props.submitting ||
    props.uncertainMessage ||
    props.uncertainClose ||
    !draft.value.trim()
  )
    return
  emit('send', draft.value.trim())
  draft.value = ''
}
const labels = { WAITING: '等待接待', ACTIVE: '接待中', CLOSED: '已结束' }
</script>

<template>
  <section
    class="human-desk"
    data-testid="human-support-panel"
    aria-label="人工接待"
  >
    <div class="human-queue">
      <div class="human-tabs">
        <button
          v-for="status in ['WAITING', 'ACTIVE', 'CLOSED'] as const"
          :key="status"
          type="button"
          :aria-pressed="view === status"
          :disabled="loading || submitting"
          @click="emit('view', status)"
        >
          {{ labels[status] }}
        </button>
        <button
          type="button"
          :disabled="loading || submitting"
          @click="emit('refresh')"
        >
          刷新列表
        </button>
      </div>
      <p
        v-if="error"
        role="alert"
      >
        {{ error }}
      </p>
      <p v-if="!sessions.length">{{ loading ? '正在获取接待列表…' : '当前没有会话' }}</p>
      <button
        v-for="(item, index) in sessions"
        :key="item.id"
        class="human-session"
        type="button"
        :aria-pressed="selectedId === item.id"
        :disabled="submitting"
        @click="emit('open', item.id)"
      >
        <strong>咨询 {{ index + 1 }} · {{ labels[item.status] }}</strong>
        <span>{{ formatTime(item.createdAt) }}{{ item.assignedToMe ? ' · 由你接待' : '' }}</span>
      </button>
      <button
        v-if="more"
        type="button"
        :disabled="loading || submitting"
        @click="emit('more')"
      >
        更早的会话
      </button>
    </div>
    <div class="human-conversation">
      <template v-if="selected">
        <header>
          <div>
            <h2>店铺客服 · {{ labels[selected.status] }}</h2>
            <p>仅发送沟通消息。售后申请请在售后审核中处理。</p>
          </div>
          <button
            type="button"
            :disabled="detailLoading || submitting"
            @click="emit('detailRefresh')"
          >
            刷新记录
          </button>
        </header>
        <p
          v-if="detailError"
          role="alert"
        >
          {{ detailError }}
        </p>
        <button
          v-if="messageMore"
          type="button"
          :disabled="detailLoading || submitting"
          @click="emit('messagesMore')"
        >
          更早的消息
        </button>
        <div
          class="human-transcript"
          role="log"
          aria-live="polite"
        >
          <article
            v-for="message in messages"
            :key="message.id"
            :class="{ staff: message.role === 'STAFF' }"
            data-testid="support-message"
          >
            <small>
              {{ message.role === 'STAFF' ? '店铺客服' : '买家' }} ·
              {{ formatTime(message.createdAt) }}
            </small>
            <p>{{ message.text }}</p>
          </article>
          <p v-if="!messages.length">
            {{ selected.status === 'WAITING' ? '接待后即可查看消息并回复买家。' : '暂无消息' }}
          </p>
        </div>
        <button
          v-if="selected.status === 'WAITING'"
          class="human-primary"
          type="button"
          data-testid="support-claim"
          :disabled="submitting || detailLoading"
          @click="emit('claim')"
        >
          接待此咨询
        </button>
        <template v-else-if="writable">
          <p
            v-if="uncertainMessage"
            role="status"
          >
            待核实消息：{{ uncertainMessage.text }}
            <button
              type="button"
              :disabled="submitting"
              @click="emit('retry')"
            >
              重试这条消息
            </button>
          </p>
          <p
            v-if="uncertainClose"
            role="status"
          >
            结束结果待核实，请刷新记录。
          </p>
          <form
            class="human-reply"
            @submit.prevent="send"
          >
            <label for="support-reply">回复买家</label>
            <textarea
              id="support-reply"
              v-model="draft"
              data-testid="support-reply"
              maxlength="2000"
              rows="3"
              :disabled="submitting || !!uncertainMessage || uncertainClose"
              placeholder="输入要发送给买家的消息"
            ></textarea>
            <div>
              <button
                type="button"
                :disabled="submitting"
                data-testid="support-close"
                @click="emit('close')"
              >
                结束人工服务
              </button>
              <button
                class="human-primary"
                type="submit"
                data-testid="support-send"
                :disabled="submitting || !draft.trim() || !!uncertainMessage || uncertainClose"
              >
                发送
              </button>
            </div>
          </form>
        </template>
        <p v-else>
          {{
            selected.status === 'CLOSED' ? '本次服务已结束，记录仅供查看。' : '当前会话仅供查看。'
          }}
        </p>
      </template>
      <p v-else>{{ detailLoading ? '正在读取咨询…' : '选择一条咨询，开始接待' }}</p>
    </div>
  </section>
</template>

<style scoped>
.human-desk {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 22px;
  background: #fff;
  border: 1px solid #e5dced;
  border-radius: 14px;
  padding: 22px;
}
.human-queue {
  border-right: 1px solid #ece6f2;
  padding-right: 20px;
}
.human-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 18px;
}
.human-desk button {
  font: inherit;
  font-size: 13px;
  border: 1px solid #dcd1e7;
  background: #fff;
  color: #725197;
  padding: 9px 12px;
  border-radius: 7px;
  cursor: pointer;
}
.human-desk button:disabled {
  opacity: 0.5;
  cursor: default;
}
.human-desk button[aria-pressed='true'] {
  background: #f1e9f9;
  border-color: #bba4d6;
}
.human-session {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  text-align: left;
  margin: 9px 0;
}
.human-session span,
.human-desk p,
.human-desk small {
  font-size: 13px;
  line-height: 1.8;
  color: #877593;
}
.human-conversation {
  min-width: 0;
}
.human-conversation header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
}
.human-conversation h2 {
  font-size: 18px;
}
.human-transcript {
  min-height: 260px;
  max-height: 480px;
  overflow: auto;
  padding: 15px 0;
}
.human-transcript article {
  max-width: 88%;
  margin-bottom: 18px;
}
.human-transcript article.staff {
  margin-left: auto;
  text-align: right;
}
.human-transcript article p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 12px 16px;
  border-radius: 10px;
  background: #f5f2f8;
  color: #51435d;
  text-align: left;
}
.human-transcript article.staff p {
  background: #eee7f8;
}
.human-reply {
  display: grid;
  gap: 10px;
}
.human-reply label {
  font-size: 13px;
}
.human-reply textarea {
  font: inherit;
  padding: 12px;
  border: 1px solid #dcd1e7;
  border-radius: 8px;
  resize: vertical;
}
.human-reply > div {
  display: flex;
  justify-content: space-between;
}
.human-desk .human-primary {
  background: #7660bc;
  color: #fff;
  border-color: #7660bc;
}
[role='alert'] {
  color: #9b5a4f;
}
@media (max-width: 750px) {
  .human-desk {
    grid-template-columns: 1fr;
  }
  .human-queue {
    border-right: 0;
    border-bottom: 1px solid #ece6f2;
    padding: 0 0 16px;
  }
}
</style>
