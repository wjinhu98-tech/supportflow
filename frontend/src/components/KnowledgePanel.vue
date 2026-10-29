<script setup lang="ts">
import { BookOpen, Plus, RefreshCw, LoaderCircle, FileText, History } from '@lucide/vue'
import { KNOWLEDGE_LIMITS } from '../api/knowledge'
import { formatDate } from './format'
import type { KnowledgeArticle, KnowledgeAudit } from '../types'

defineProps<{
  shopName: string
  articles: KnowledgeArticle[]
  selected: KnowledgeArticle | null
  editing: boolean
  title: string
  keywords: string
  content: string
  loading: boolean
  detailLoading: boolean
  auditLoading: boolean
  submitting: boolean
  error: string | null
  notice: string | null
  needsVerification: boolean
  dirty: boolean
  hasUnpublishedChanges: boolean
  more: boolean
  audit: KnowledgeAudit[]
  auditMore: boolean
}>()
const emit = defineEmits<{
  refresh: []
  more: []
  open: [id: string]
  create: []
  import: []
  cancel: []
  reload: []
  save: []
  publish: []
  offline: []
  auditMore: []
  'update:title': [value: string]
  'update:keywords': [value: string]
  'update:content': [value: string]
}>()
function stateLabel(status: KnowledgeArticle['status']) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' }[status]
}
function actionLabel(action: KnowledgeAudit['action']) {
  return { CREATED: '创建草稿', SAVED: '保存草稿', PUBLISHED: '发布', OFFLINE: '下线' }[action]
}
</script>

<template>
  <section
    class="knowledge-panel"
    data-testid="knowledge-panel"
    aria-label="店铺知识库管理"
  >
    <header class="knowledge-heading">
      <div>
        <span class="knowledge-symbol"><BookOpen :size="24" /></span>
        <div>
          <h2>{{ shopName }} · 知识库</h2>
          <p>纯文本内容只供本店客服检索，不会修改订单或退款规则。</p>
        </div>
      </div>
      <div class="knowledge-actions">
        <button
          type="button"
          data-testid="knowledge-import"
          :disabled="submitting || dirty"
          @click="emit('import')"
        >
          导入文件
        </button>
        <button
          type="button"
          data-testid="knowledge-refresh"
          :disabled="loading || submitting"
          @click="emit('refresh')"
        >
          <RefreshCw :size="16" />
          刷新列表
        </button>
        <button
          type="button"
          class="knowledge-primary"
          data-testid="knowledge-new"
          :disabled="submitting || dirty"
          @click="emit('create')"
        >
          <Plus :size="16" />
          新建文档
        </button>
      </div>
    </header>
    <p
      v-if="error"
      class="knowledge-error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      class="knowledge-notice"
      role="status"
    >
      {{ notice }}
    </p>
    <p
      v-if="dirty"
      class="knowledge-local-note"
    >
      有未保存的本地编辑。请先保存或取消，再切换店铺、模块或退出。
    </p>
    <div class="knowledge-layout">
      <aside
        class="knowledge-list"
        :aria-busy="loading"
      >
        <h3>
          文档列表
          <span>{{ articles.length }} 篇</span>
        </h3>
        <p
          v-if="loading && !articles.length"
          role="status"
        >
          <LoaderCircle
            :size="17"
            class="knowledge-spin"
          />
          正在读取文档…
        </p>
        <p
          v-else-if="!articles.length"
          class="knowledge-empty"
        >
          本店暂无文档。可以先创建草稿，核对后再发布。
        </p>
        <article
          v-for="article in articles"
          :key="article.id"
          class="knowledge-card"
          data-testid="knowledge-card"
          :data-article-id="article.id"
          :data-active="selected?.id === article.id"
        >
          <button
            type="button"
            data-testid="knowledge-open"
            :disabled="submitting || (dirty && !(needsVerification && selected === null))"
            @click="emit('open', article.id)"
          >
            <FileText :size="17" />
            <strong>{{ article.title }}</strong>
          </button>
          <div>
            <span :data-status="article.status">{{ stateLabel(article.status) }}</span>
            <span>编辑版本 {{ article.version }}</span>
          </div>
          <p>{{ article.keywords.join(' · ') }}</p>
          <small>{{ formatDate(article.updatedAt, true) }}</small>
        </article>
        <button
          v-if="more"
          type="button"
          class="knowledge-more"
          data-testid="knowledge-load-more"
          :disabled="loading || submitting"
          @click="emit('more')"
        >
          {{ loading ? '正在读取…' : '加载更多文档' }}
        </button>
      </aside>
      <div class="knowledge-detail">
        <p
          v-if="detailLoading"
          role="status"
          class="knowledge-reading"
        >
          <LoaderCircle
            :size="17"
            class="knowledge-spin"
          />
          正在读取最新版本…
        </p>
        <form
          v-if="editing"
          class="knowledge-editor"
          data-testid="knowledge-editor"
          @submit.prevent="emit('save')"
        >
          <header>
            <h3>{{ selected ? '编辑文档' : '新建草稿' }}</h3>
            <div>
              <span data-testid="knowledge-status">
                {{ selected ? stateLabel(selected.status) : '未保存' }}
              </span>
              <span
                v-if="selected"
                data-testid="knowledge-version"
              >
                编辑版本 {{ selected.version }}
              </span>
            </div>
          </header>
          <label for="knowledge-title">
            标题
            <span>{{ title.length }}/{{ KNOWLEDGE_LIMITS.title }}</span>
          </label>
          <input
            id="knowledge-title"
            data-testid="knowledge-title"
            :value="title"
            required
            :maxlength="KNOWLEDGE_LIMITS.title"
            :disabled="submitting || detailLoading"
            @input="emit('update:title', ($event.target as HTMLInputElement).value)"
          />
          <label for="knowledge-keywords">
            关键词
            <span>1–12 个，每个最多 40 字；以逗号分隔</span>
          </label>
          <input
            id="knowledge-keywords"
            data-testid="knowledge-keywords"
            :value="keywords"
            required
            maxlength="504"
            :disabled="submitting || detailLoading"
            placeholder="例如：发货时间, 配送范围"
            @input="emit('update:keywords', ($event.target as HTMLInputElement).value)"
          />
          <label for="knowledge-content">
            正文
            <span>{{ content.length }}/{{ KNOWLEDGE_LIMITS.content }}</span>
          </label>
          <textarea
            id="knowledge-content"
            data-testid="knowledge-content"
            :value="content"
            required
            :maxlength="KNOWLEDGE_LIMITS.content"
            rows="12"
            :disabled="submitting || detailLoading"
            placeholder="填写经过核实的本店说明。只支持纯文本，不要填写密码或客户个人信息。"
            @input="emit('update:content', ($event.target as HTMLTextAreaElement).value)"
          ></textarea>
          <p
            class="knowledge-publication-note"
            data-testid="knowledge-publication-note"
          >
            {{
              selected?.status === 'PUBLISHED'
                ? hasUnpublishedChanges
                  ? '草稿与线上发布快照不同。买家仍检索旧快照，重新发布后才会更新。'
                  : '保存只更新草稿，不会覆盖线上发布快照；修改后需重新发布才会生效。'
                : '草稿和下线文档不参与买家咨询；只有明确发布的快照会被本店检索。'
            }}
          </p>
          <div class="knowledge-editor-actions">
            <button
              type="button"
              data-testid="knowledge-cancel"
              :disabled="submitting"
              @click="emit('cancel')"
            >
              取消编辑
            </button>
            <button
              type="button"
              data-testid="knowledge-reload"
              :disabled="submitting || detailLoading || loading"
              @click="emit('reload')"
            >
              重新读取核实
            </button>
            <button
              type="submit"
              class="knowledge-primary"
              data-testid="knowledge-save"
              :disabled="
                submitting || detailLoading || needsVerification || (selected !== null && !dirty)
              "
            >
              {{ submitting ? '正在提交…' : '保存草稿' }}
            </button>
            <button
              v-if="selected"
              type="button"
              class="knowledge-publish"
              data-testid="knowledge-publish"
              :disabled="
                submitting ||
                detailLoading ||
                needsVerification ||
                dirty ||
                (selected.status === 'PUBLISHED' && !hasUnpublishedChanges)
              "
              @click="emit('publish')"
            >
              {{ selected.status === 'PUBLISHED' ? '重新发布' : '发布到店铺' }}
            </button>
            <button
              v-if="selected?.status === 'PUBLISHED'"
              type="button"
              data-testid="knowledge-offline"
              :disabled="submitting || detailLoading || needsVerification || dirty"
              @click="emit('offline')"
            >
              下线文档
            </button>
          </div>
        </form>
        <div
          v-else-if="!detailLoading"
          class="knowledge-empty-detail"
        >
          <BookOpen :size="38" />
          <h3>选择一篇文档，或从草稿开始</h3>
          <p>发布前可检查内容。保存草稿不会立即改变买家看到的回答。</p>
        </div>
        <section
          v-if="selected?.publishedSnapshot"
          class="knowledge-snapshot"
          data-testid="knowledge-published-snapshot"
        >
          <h3>当前线上快照 · 发布修订 {{ selected.publishedSnapshot.revision }}</h3>
          <p>{{ formatDate(selected.publishedSnapshot.publishedAt, true) }}</p>
          <h4>{{ selected.publishedSnapshot.title }}</h4>
          <small>{{ selected.publishedSnapshot.keywords.join(' · ') }}</small>
          <pre>{{ selected.publishedSnapshot.content }}</pre>
        </section>
        <section
          v-if="selected"
          class="knowledge-audit"
          data-testid="knowledge-audit"
          :aria-busy="auditLoading"
        >
          <h3>
            <History :size="18" />
            操作记录
          </h3>
          <p
            v-if="auditLoading && !audit.length"
            role="status"
          >
            正在读取操作记录…
          </p>
          <p v-else-if="!audit.length">暂无可展示的记录。</p>
          <details
            v-for="entry in audit"
            :key="entry.id"
            data-testid="knowledge-audit-entry"
          >
            <summary>
              {{ actionLabel(entry.action) }} · {{ entry.actor }} · 编辑版本 {{ entry.version }}
              <time>{{ formatDate(entry.createdAt, true) }}</time>
            </summary>
            <p>当时标题：{{ entry.title }}</p>
            <p>关键词：{{ entry.keywords.join(' · ') }}</p>
            <pre>{{ entry.content }}</pre>
          </details>
          <button
            v-if="auditMore"
            type="button"
            data-testid="knowledge-load-more-audit"
            :disabled="auditLoading || submitting"
            @click="emit('auditMore')"
          >
            {{ auditLoading ? '正在读取…' : '加载更多操作记录' }}
          </button>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.knowledge-panel {
  border: 1px solid #e3d9ee;
  border-radius: 16px;
  background: #fff;
  padding: 25px;
  color: #4b3c58;
  box-shadow: 0 8px 32px #2817320a;
}
.knowledge-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding-bottom: 24px;
  border-bottom: 1px solid #eee6f3;
}
.knowledge-heading > div:first-child {
  display: flex;
  align-items: center;
  gap: 13px;
}
.knowledge-heading h2 {
  font-size: 23px;
  font-weight: 600;
  margin: 0 0 7px;
}
.knowledge-heading p {
  font-size: 13px;
  color: #927d9e;
  line-height: 1.8;
}
.knowledge-symbol {
  width: 47px;
  height: 47px;
  display: grid;
  place-items: center;
  border-radius: 13px;
  background: #f1eaf9;
  color: #8463a9;
  flex-shrink: 0;
}
.knowledge-actions,
.knowledge-editor-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 13px;
  border: 1px solid #e0d4ea;
  background: #fff;
  color: #7c5d94;
  border-radius: 7px;
  font: inherit;
  font-size: 13px;
  min-height: 38px;
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.knowledge-primary {
  background: #7660bc;
  border-color: #7660bc;
  color: #fff;
}
.knowledge-publish {
  background: #eef7f0;
  color: #527c61;
  border-color: #cde4d3;
}
.knowledge-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 27px;
  padding-top: 25px;
  align-items: start;
}
.knowledge-list {
  border-right: 1px solid #eee7f4;
  padding-right: 22px;
}
h3 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 15px;
}
.knowledge-list h3 span {
  font-size: 12px;
  margin-left: 7px;
  color: #9c88a9;
  font-weight: 400;
}
.knowledge-card {
  border: 1px solid #e9e0f1;
  border-radius: 9px;
  padding: 13px;
  margin-bottom: 11px;
}
.knowledge-card[data-active='true'] {
  border-color: #b39bcf;
  background: #fcf9ff;
}
.knowledge-card > button {
  width: 100%;
  justify-content: flex-start;
  text-align: left;
  border: 0;
  padding: 0;
  min-height: 30px;
  background: transparent;
  word-break: break-word;
}
.knowledge-card strong {
  font-size: 14px;
  font-weight: 550;
}
.knowledge-card > div {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: #9a84aa;
  margin: 8px 0;
}
.knowledge-card [data-status] {
  background: #f2edf7;
  padding: 3px 6px;
  border-radius: 4px;
}
.knowledge-card [data-status='PUBLISHED'] {
  background: #edf7f0;
  color: #568167;
}
.knowledge-card p,
.knowledge-card small {
  color: #927e9f;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.knowledge-card small {
  font-size: 11px;
}
.knowledge-detail {
  min-width: 0;
}
.knowledge-editor header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.knowledge-editor header div {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #997eab;
}
.knowledge-editor label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 14px;
  color: #775c89;
  margin: 17px 0 9px;
}
.knowledge-editor label span {
  font-size: 12px;
  font-weight: 400;
  color: #9d8aa9;
}
.knowledge-editor input,
.knowledge-editor textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #ddcfe8;
  border-radius: 7px;
  padding: 11px;
  color: #5d476d;
  font: inherit;
  line-height: 1.8;
  background: #fff;
}
.knowledge-editor textarea {
  resize: vertical;
  min-height: 210px;
}
.knowledge-editor textarea::placeholder,
.knowledge-editor input::placeholder {
  color: #ad9db6;
}
.knowledge-publication-note,
.knowledge-local-note,
.knowledge-notice {
  color: #7d6690;
  font-size: 13px;
  line-height: 1.9;
  padding: 13px;
  background: #f8f4fc;
  border: 1px solid #e9ddf3;
  border-radius: 8px;
  margin: 15px 0;
}
.knowledge-error {
  color: #9b5a4f;
  background: #fff6f1;
  border: 1px solid #eed4c8;
  border-radius: 8px;
  padding: 12px;
  line-height: 1.8;
  font-size: 14px;
  margin-top: 18px;
}
.knowledge-local-note {
  background: #fffbf1;
  border-color: #eee0c4;
  color: #9a8050;
}
.knowledge-empty,
.knowledge-list > p,
.knowledge-audit > p,
.knowledge-reading {
  color: #9a84aa;
  line-height: 1.9;
  font-size: 13px;
}
.knowledge-empty-detail {
  padding: 70px 20px;
  text-align: center;
  color: #9c83af;
}
.knowledge-empty-detail h3 {
  margin: 20px 0 10px;
}
.knowledge-empty-detail p {
  font-size: 13px;
  line-height: 1.9;
}
.knowledge-more {
  width: 100%;
}
.knowledge-snapshot,
.knowledge-audit {
  margin-top: 26px;
  padding-top: 22px;
  border-top: 1px solid #eee6f4;
}
.knowledge-snapshot h3 {
  color: #5b8069;
}
.knowledge-snapshot h4 {
  margin: 15px 0 8px;
  font-size: 15px;
}
.knowledge-snapshot p,
.knowledge-snapshot small {
  color: #8f7c9d;
  font-size: 12px;
}
pre {
  font: inherit;
  font-size: 13px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  padding: 13px;
  margin: 12px 0 0;
  border-radius: 7px;
  background: #faf8fc;
  color: #7f6b8d;
}
.knowledge-audit h3 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.knowledge-audit details {
  border: 1px solid #eee5f4;
  border-radius: 7px;
  padding: 12px;
  margin-bottom: 10px;
  font-size: 13px;
  color: #826a95;
}
.knowledge-audit summary {
  cursor: pointer;
  line-height: 1.9;
}
.knowledge-audit time {
  display: block;
  color: #ab99b7;
  font-size: 11px;
  margin-left: 15px;
}
.knowledge-audit details > p {
  margin-top: 10px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.knowledge-reading,
.knowledge-list > p {
  display: flex;
  align-items: center;
  gap: 7px;
}
.knowledge-spin {
  animation: knowledge-spin 1s linear infinite;
}
@keyframes knowledge-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 850px) {
  .knowledge-layout {
    grid-template-columns: 1fr;
  }
  .knowledge-list {
    border-right: 0;
    border-bottom: 1px solid #eee7f4;
    padding-right: 0;
    padding-bottom: 16px;
  }
  .knowledge-panel {
    padding: 18px;
  }
  .knowledge-card {
    display: inline-block;
    width: 100%;
  }
  .knowledge-heading h2 {
    font-size: 20px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .knowledge-spin {
    animation: none;
  }
}
</style>
