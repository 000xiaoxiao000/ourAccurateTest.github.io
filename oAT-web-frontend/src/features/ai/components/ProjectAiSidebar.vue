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
