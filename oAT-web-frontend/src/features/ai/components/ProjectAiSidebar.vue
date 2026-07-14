<template>
  <aside class="side-stack">
    <section class="panel ask-panel">
      <div class="card-title">
        <h2>会话</h2>
        <button class="ghost-button small" type="button" @click="$emit('new-session')">新会话</button>
      </div>
      <div class="session-tools">
        <input v-model.trim="sessionSearchModel" class="text-input small-input" type="search" placeholder="搜索会话标题或内容" aria-label="搜索 AI 会话" />
        <select v-model="sessionSortModel" class="text-input small-input">
          <option value="recent">最近更新</option>
          <option value="oldest">最早更新</option>
          <option value="name">标题排序</option>
        </select>
      </div>
      <div class="session-list">
        <article
          v-for="session in visibleSessions"
          :key="session.id"
          class="session-card"
          :class="{ active: session.id === activeSessionId, pinned: session.pinned }"
        >
          <button class="session-main" type="button" @click="$emit('switch-session', session.id)">
            <strong>{{ session.pinned ? '★ ' : '' }}{{ session.title }}</strong>
            <span>{{ session.messages.length }} 条消息 · {{ formatSessionTime(session.updatedAt) }}</span>
          </button>
          <div class="session-actions">
            <button type="button" title="置顶/取消置顶" @click="$emit('toggle-pin-session', session.id)">{{ session.pinned ? '取消置顶' : '置顶' }}</button>
            <button type="button" title="重命名" @click="$emit('rename-session', session.id)">重命名</button>
            <button type="button" title="删除" @click="$emit('delete-session', session.id)">删除</button>
          </div>
        </article>
      </div>
    </section>

    <section class="panel ability-panel">
      <div class="card-title">
        <h2>能力卡片</h2>
      </div>
      <div class="ability-list">
        <article v-for="card in context.abilityCards" :key="`${card.title}-${card.value}`" class="ability-card">
          <strong>{{ card.title }}</strong>
          <span class="ability-value">{{ card.value }}</span>
          <p>{{ card.description }}</p>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="card-title">
        <h2>快速问题</h2>
      </div>
      <div class="question-list">
        <button
          v-for="question in context.starterQuestions"
          :key="question"
          class="ghost-button"
          type="button"
          @click="$emit('use-question', question)"
        >
          {{ question }}
        </button>
      </div>
    </section>

    <section class="panel">
      <div class="card-title">
        <h2>快捷入口</h2>
        <span class="muted">{{ mergedQuickLinks.length }} 个</span>
      </div>
      <div class="link-list compact-links">
        <button v-for="link in mergedQuickLinks" :key="link.title + link.url" class="link-card link-button" type="button" @click="$emit('open-link', link)">
          <strong>{{ link.title }}</strong>
          <span>{{ link.description }}</span>
        </button>
        <div v-if="!mergedQuickLinks.length" class="empty-card compact">暂无快捷入口</div>
      </div>
    </section>

    <section class="panel">
      <div class="card-title">
        <h2>项目上下文</h2>
        <span class="muted">{{ context.appNames.length }} 个源码工程</span>
      </div>
      <div class="context-list">
        <div class="context-row">
          <span>AI 形象</span>
          <strong>{{ context.mascot?.mascotName || 'AI' }} · {{ context.mascot?.mascotRole || '助手' }}</strong>
        </div>
        <div class="context-row">
          <span>验证重点</span>
          <strong>需求 · 用例 · 源码</strong>
        </div>
        <div class="app-chip-list">
          <span v-for="appName in context.appNames" :key="appName" class="app-chip">{{ appName }}</span>
          <span v-if="!context.appNames.length" class="app-chip muted-chip">暂无源码工程</span>
        </div>
      </div>
    </section>

    <section class="panel learning-panel">
      <div class="card-title">
        <h2>AI 自主学习</h2>
        <button class="ghost-button small" type="button" :disabled="learningLoading" @click="$emit('refresh-learning')">
          {{ learningLoading ? '刷新中...' : '刷新报告' }}
        </button>
      </div>
      <div v-if="learningError" class="learning-error">{{ learningError }}</div>
      <div v-else class="learning-grid">
        <article class="meta-card">
          <span>反馈总数</span>
          <strong>{{ feedbackStats?.total ?? 0 }}</strong>
        </article>
        <article class="meta-card">
          <span>满意度</span>
          <strong>{{ feedbackStats?.satisfactionRate || '0%' }}</strong>
        </article>
        <article class="meta-card">
          <span>知识库条目</span>
          <strong>{{ selfLearningStatus?.knowledgeBaseSize ?? 0 }}</strong>
        </article>
        <article class="meta-card">
          <span>跟踪主题</span>
          <strong>{{ selfLearningStatus?.trackedTopics ?? 0 }}</strong>
        </article>
      </div>
      <div v-if="learningReport?.suggestions?.length" class="learning-suggestions">
        <div class="learning-suggestions-header">
          <h3>优化建议</h3>
          <div class="learning-suggestions-actions">
            <span class="muted">{{ learningReport.suggestions.length }} 条</span>
            <button class="ghost-button small" type="button" :disabled="learningLoading" @click="$emit('clear-learning-suggestions')">清空</button>
          </div>
        </div>
        <div class="learning-suggestions-list">
          <article v-for="item in learningReport.suggestions" :key="item.id" class="learning-suggestion" :class="item.priority.toLowerCase()">
            <strong>{{ item.title }}</strong>
            <p>{{ item.description }}</p>
          </article>
        </div>
      </div>
      <p v-else class="learning-empty">提交回答反馈后，系统会自动积累知识并生成优化建议。</p>
    </section>

    <section class="panel">
      <div class="card-title">
        <h2>提问锚点</h2>
        <span class="muted">{{ visibleQuestionAnchors.length }}/{{ questionAnchors.length }} 个</span>
      </div>
      <div class="anchor-tools">
        <div class="anchor-filter" role="group" aria-label="锚点筛选">
          <button class="anchor-filter-button" :class="{ active: anchorFilterMode === 'all' }" type="button" @click="$emit('update:anchorFilterMode', 'all')">全部</button>
          <button class="anchor-filter-button" :class="{ active: anchorFilterMode === 'pending' }" type="button" @click="$emit('update:anchorFilterMode', 'pending')">仅看未回复</button>
        </div>
        <div class="anchor-search-row">
          <input v-model.trim="anchorSearchModel" class="text-input small-input" type="search" placeholder="搜索问题关键词" aria-label="搜索提问锚点" />
          <button v-if="anchorSearch" class="anchor-clear" type="button" title="清空搜索" @click="$emit('update:anchorSearch', '')">×</button>
        </div>
        <p class="anchor-tip">点击可快速定位到对应问答</p>
      </div>
      <div class="anchor-list">
        <button
          v-for="anchor in visibleQuestionAnchors"
          :key="anchor.id"
          class="anchor-item"
          :class="{ active: activeAnchorId === anchor.id, pending: !anchor.answered, answered: anchor.answered, expanded: expandedAnchorIds.has(anchor.id) }"
          type="button"
          @mouseenter="$emit('update:previewAnchorId', anchor.id)"
          @mouseleave="$emit('update:previewAnchorId', '')"
          @click="$emit('scroll-to-anchor', anchor.id)"
        >
          <span class="anchor-top">
            <strong>{{ anchor.label }}</strong>
            <span class="anchor-status" :class="anchor.answered ? 'answered' : 'pending'">
              <i></i>{{ anchor.answered ? '已回复' : '待回复' }}
            </span>
          </span>
          <span class="anchor-question">{{ anchor.question }}</span>
          <span class="anchor-meta">{{ anchor.responseTimeText || (anchor.answered ? '已生成回答' : '等待回复中') }}</span>
          <span class="anchor-actions" @click.stop>
            <button class="anchor-link-button" type="button" @click="$emit('toggle-anchor-text', anchor.id)">{{ expandedAnchorIds.has(anchor.id) ? '收起' : '展开' }}</button>
            <button class="anchor-link-button" type="button" @click="$emit('copy-anchor-link', anchor)">复制链接</button>
          </span>
        </button>
        <div v-if="!visibleQuestionAnchors.length" class="empty-card compact">暂无匹配的提问锚点</div>
      </div>
    </section>

    <section class="panel">
      <div class="card-title">
        <h2>会话时间线</h2>
      </div>
      <div class="timeline-list">
        <button
          v-for="item in timelineItems"
          :key="item.id"
          class="timeline-item"
          :class="{ active: activeAnchorId === item.anchorId }"
          type="button"
          @click="$emit('scroll-to-anchor', item.anchorId)"
        >
          <span>{{ item.label }}</span>
          <strong>问答</strong>
          <small>{{ item.question }}</small>
        </button>
        <div v-if="!timelineItems.length" class="empty-card compact">暂无会话节点</div>
      </div>
    </section>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { AIFeedbackStats, AIInteractivePagePayload, AILearningReport, AIQuickLink } from '@/api/types'
import type { AiChatSession, AiQuestionAnchor, AiTimelineItem } from '@/features/ai/types'

const props = defineProps<{
  context: AIInteractivePagePayload
  visibleSessions: AiChatSession[]
  activeSessionId: string
  sessionSearch: string
  sessionSort: 'recent' | 'oldest' | 'name'
  mergedQuickLinks: AIQuickLink[]
  feedbackStats: AIFeedbackStats | null
  learningReport: AILearningReport | null
  learningLoading: boolean
  learningError: string
  questionAnchors: AiQuestionAnchor[]
  visibleQuestionAnchors: AiQuestionAnchor[]
  anchorFilterMode: 'all' | 'pending'
  anchorSearch: string
  activeAnchorId: string
  expandedAnchorIds: Set<string>
  timelineItems: AiTimelineItem[]
}>()

const emit = defineEmits<{
  'update:sessionSearch': [value: string]
  'update:sessionSort': [value: 'recent' | 'oldest' | 'name']
  'update:anchorFilterMode': [value: 'all' | 'pending']
  'update:anchorSearch': [value: string]
  'update:previewAnchorId': [value: string]
  'new-session': []
  'switch-session': [sessionId: string]
  'toggle-pin-session': [sessionId: string]
  'rename-session': [sessionId: string]
  'delete-session': [sessionId: string]
  'use-question': [question: string]
  'open-link': [link: AIQuickLink]
  'refresh-learning': []
  'clear-learning-suggestions': []
  'scroll-to-anchor': [anchorId: string]
  'toggle-anchor-text': [anchorId: string]
  'copy-anchor-link': [anchor: AiQuestionAnchor]
}>()

const sessionSearchModel = computed({
  get: () => props.sessionSearch,
  set: (value: string) => emit('update:sessionSearch', value),
})
const sessionSortModel = computed({
  get: () => props.sessionSort,
  set: (value: 'recent' | 'oldest' | 'name') => emit('update:sessionSort', value),
})
const anchorSearchModel = computed({
  get: () => props.anchorSearch,
  set: (value: string) => emit('update:anchorSearch', value),
})
const selfLearningStatus = computed(() => props.feedbackStats?.selfLearning || null)

function formatSessionTime(value: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}
</script>

<style scoped>
.side-stack {
  display: grid;
  gap: 16px;
  height: 100%;
  max-height: 100%;
  overflow: auto;
  padding-right: 6px;
  scrollbar-gutter: stable;
}

.side-stack::-webkit-scrollbar,
.session-list::-webkit-scrollbar,
.ability-list::-webkit-scrollbar,
.anchor-list::-webkit-scrollbar {
  width: 8px;
}

.side-stack::-webkit-scrollbar-thumb,
.session-list::-webkit-scrollbar-thumb,
.ability-list::-webkit-scrollbar-thumb,
.anchor-list::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 24%, #cbd5e1);
  background-clip: content-box;
}

.panel {
  padding: 18px;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 24px;
  background: rgba(255, 255, 255, .96);
  box-shadow: 0 18px 46px rgba(15, 23, 42, .07);
}

.card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.card-title h2 {
  margin: 0;
  color: #172033;
  font-size: 18px;
  font-weight: 850;
}

.muted {
  color: #64748b;
  font-size: 12px;
}

.ghost-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 18%, transparent);
  border-radius: 999px;
  padding: 0 14px;
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 6%, white);
  color: var(--ai-accent, #0f766e);
  font: inherit;
  font-weight: 850;
  cursor: pointer;
}

.ghost-button.small {
  min-height: 34px;
  padding: 0 12px;
  font-size: 12px;
}

.ghost-button:hover {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 34%, transparent);
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 11%, white);
}

.session-tools {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 128px;
  gap: 8px;
  margin-bottom: 12px;
}

.text-input {
  width: 100%;
  min-height: 40px;
  border: 1px solid rgba(15, 23, 42, .1);
  border-radius: 999px;
  padding: 0 14px;
  background: #fff;
  color: #172033;
  font: inherit;
  font-size: 13px;
  outline: none;
  transition: border-color .16s ease, box-shadow .16s ease;
}

.text-input:focus {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 38%, transparent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--ai-accent, #0f766e) 10%, transparent);
}

select.text-input {
  appearance: none;
  background-image:
    linear-gradient(45deg, transparent 50%, #64748b 50%),
    linear-gradient(135deg, #64748b 50%, transparent 50%);
  background-position:
    calc(100% - 18px) 50%,
    calc(100% - 13px) 50%;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
  padding-right: 34px;
}

.session-list,
.ability-list,
.question-list,
.link-list,
.context-list,
.anchor-list,
.timeline-list {
  display: grid;
  gap: 10px;
}

.session-list {
  max-height: 300px;
  overflow: auto;
}

.session-card {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  background: linear-gradient(180deg, #fff, #f8fafc);
}

.session-card.active {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 26%, transparent);
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 8%, white);
  box-shadow: inset 3px 0 0 var(--ai-accent, #0f766e);
}

.session-card.pinned {
  box-shadow: inset 3px 0 0 #f59e0b;
}

.session-main {
  display: grid;
  gap: 4px;
  width: 100%;
  border: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.session-main strong {
  color: #172033;
  font-size: 14px;
  line-height: 1.35;
}

.session-main span {
  color: #64748b;
  font-size: 12px;
}

.session-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.session-actions button,
.anchor-filter-button,
.anchor-link-button {
  min-height: 28px;
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 16%, transparent);
  border-radius: 999px;
  padding: 0 10px;
  background: rgba(255, 255, 255, .84);
  color: var(--ai-accent, #0f766e);
  font: inherit;
  font-size: 12px;
  font-weight: 850;
  cursor: pointer;
}

.ability-card,
.link-card,
.meta-card,
.learning-suggestion {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 18px;
  background: linear-gradient(180deg, #fff, #f8fafc);
}

.ability-card strong,
.link-card strong,
.meta-card strong,
.learning-suggestion strong {
  color: #172033;
}

.ability-card p,
.link-card span,
.learning-suggestion p,
.learning-empty,
.learning-error {
  margin: 0;
  color: #64748b;
  line-height: 1.5;
}

.ability-value {
  color: var(--ai-accent, #0f766e);
  font-size: 20px;
  font-weight: 900;
}

.question-list .ghost-button {
  justify-content: flex-start;
  width: 100%;
  min-height: 42px;
  border-radius: 14px;
  text-align: left;
  white-space: normal;
  line-height: 1.35;
}

.link-button {
  width: 100%;
  border: 1px solid rgba(15, 23, 42, .07);
  text-align: left;
  cursor: pointer;
}

.context-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(15, 23, 42, .06);
}

.context-row span {
  color: #64748b;
}

.context-row strong {
  color: #172033;
  text-align: right;
}

.app-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.app-chip {
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 18%, transparent);
  border-radius: 999px;
  padding: 6px 10px;
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 6%, white);
  color: var(--ai-accent, #0f766e);
  font-size: 12px;
  font-weight: 850;
}

.learning-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.learning-suggestions {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.learning-suggestions-header,
.learning-suggestions-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.learning-suggestions-header h3 {
  margin: 0;
  color: #172033;
  font-size: 14px;
}

.anchor-tools {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.anchor-filter {
  display: flex;
  gap: 8px;
}

.anchor-filter-button.active {
  background: var(--ai-accent, #0f766e);
  color: #fff;
}

.anchor-search-row {
  position: relative;
}

.anchor-clear {
  position: absolute;
  top: 50%;
  right: 8px;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: rgba(15, 23, 42, .08);
  color: #64748b;
  cursor: pointer;
  transform: translateY(-50%);
}

.anchor-tip {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
}

.anchor-list {
  max-height: 320px;
  overflow: auto;
}

.anchor-item,
.timeline-item {
  display: grid;
  gap: 7px;
  width: 100%;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  padding: 11px;
  background: rgba(255, 255, 255, .92);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.anchor-item.active,
.timeline-item.active {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 28%, transparent);
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 8%, white);
}

.anchor-top,
.anchor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.anchor-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 900;
}

.anchor-status i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
}

.anchor-status.answered {
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 12%, white);
  color: var(--ai-accent, #0f766e);
}

.anchor-status.answered i {
  background: var(--ai-accent, #0f766e);
}

.anchor-status.pending {
  background: rgba(245, 158, 11, .16);
  color: #92400e;
}

.anchor-status.pending i {
  background: #f59e0b;
}

.anchor-question,
.anchor-meta,
.timeline-item small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.empty-card.compact {
  padding: 12px;
  border-radius: 14px;
  background: #f8fafc;
  color: #94a3b8;
}

@media (max-width: 980px) {
  .side-stack {
    height: auto;
    max-height: none;
    overflow: visible;
    padding-right: 0;
  }

  .session-tools {
    grid-template-columns: 1fr;
  }
}
</style>
