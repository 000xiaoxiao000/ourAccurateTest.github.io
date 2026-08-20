<template>
  <Teleport to="body">
    <div v-if="visible" class="ai-draft-mask" @click.self="close">
      <section class="ai-draft-card" role="dialog" aria-modal="true">
        <header class="ai-draft-header">
          <div>
            <p class="ai-draft-badge">AI 生成</p>
            <h3>{{ prettyTitle }}</h3>
          </div>
          <button class="ai-draft-close" type="button" @click="close">关闭</button>
        </header>

        <div class="ai-draft-status" :class="statusBannerClass" role="status" aria-live="polite">
          <span class="ai-draft-status-icon" aria-hidden="true">{{ statusIcon }}</span>
          <div class="ai-draft-status-text">
            <strong>{{ statusHeading }}</strong>
            <span class="ai-draft-status-sub">{{ statusHint }}</span>
          </div>
        </div>

        <textarea
          v-model="draftText"
          class="ai-draft-textarea"
          :class="{ 'ai-draft-textarea-locked': !isTerminal }"
          spellcheck="false"
          :readonly="!isTerminal"
          :placeholder="isTerminal ? '' : 'AI 正在生成草稿，完成后可在此编辑'"
        ></textarea>

        <footer class="ai-draft-actions">
          <button class="ai-draft-button secondary" type="button" :disabled="!canAct" @click="reject">拒绝</button>
          <button class="ai-draft-button primary" type="button" :disabled="!canAct" @click="confirm">确认落库</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { AiDraftResponse } from '@/api/ai'

const props = defineProps<{
  visible: boolean
  title: string
  draft: AiDraftResponse | null
}>()

const emit = defineEmits<{
  close: []
  confirm: [payload: string]
  reject: []
}>()

/** 与后端 ovanth AiDraftStatus 真实枚举对齐（PENDING / RUNNING / DONE / FAILED / CONFIRMED / REJECTED）。 */
const TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '执行中',
  DONE: '已完成',
  FAILED: '已失败',
  CONFIRMED: '已通过',
  REJECTED: '已驳回',
}

/** 终态：可落库、可拒绝、可编辑。PENDING/RUNNING 视为进行中，FAILED 视为异常终态。 */
const TERMINAL_STATUSES = new Set(['DONE', 'CONFIRMED', 'REJECTED'])
const PENDING_STATUSES = new Set(['PENDING', 'RUNNING'])

const draftText = ref('')

/**
 * 反显策略：
 * - PENDING/RUNNING：固定占位文案（payload 还没生成时 oAT 后端给的是空 Map.of()）
 * - FAILED：若 payload 非空则展示，否则展示 error 文案
 * - 终态：按 JSON 美化展示，文本类 payload 也安全反显
 */
function renderDraftText(draft: AiDraftResponse | null) {
  if (!draft) {
    draftText.value = ''
    return
  }
  const status = draft.status
  if (PENDING_STATUSES.has(status)) {
    draftText.value = '⏳ AI 正在生成草稿，完成后会自动出现在此处…'
    return
  }
  if (status === 'FAILED') {
    draftText.value = draft.error ? `❌ 任务失败：${draft.error}\n（payload 为空，可联系管理员排查）` : '❌ 任务失败，payload 为空。'
    return
  }
  const payload = draft.payload as { text?: unknown } | null | undefined
  if (payload && typeof payload === 'object' && typeof payload.text === 'string') {
    try {
      const parsed = JSON.parse(payload.text)
      draftText.value = JSON.stringify(parsed, null, 2)
    } catch {
      draftText.value = payload.text
    }
  } else if (payload && typeof payload === 'object' && Object.keys(payload).length > 0) {
    draftText.value = JSON.stringify(payload, null, 2)
  } else {
    draftText.value = '（草稿内容为空）'
  }
}

watch(
  () => props.draft,
  (draft) => renderDraftText(draft),
  { immediate: true },
)

const prettyTitle = computed(() => props.title || 'AI 草稿')

const statusLabel = computed(() => {
  const status = props.draft?.status
  if (!status) return '未知'
  return TASK_STATUS_LABELS[status] ?? status
})

const isTerminal = computed(() => {
  const status = props.draft?.status
  return status ? TERMINAL_STATUSES.has(status) : false
})

const isPending = computed(() => {
  const status = props.draft?.status
  return status ? PENDING_STATUSES.has(status) : false
})

const isFailed = computed(() => props.draft?.status === 'FAILED')

const errorMessage = computed(() => {
  const draft = props.draft
  if (!draft?.error) return ''
  return draft.error
})

/**
 * 状态展示从一行小字升级为 banner：图标 + 主标题 + 副标题，告诉用户现在到哪一步、下一步该干什么。
 * PENDING / RUNNING 视为进行中，DONE 可落库/拒绝，FAILED/REJECTED/CONFIRMED 视为终态。
 */
const STATUS_BANNER: Record<string, { icon: string; heading: string; hint: string; tone: 'pending' | 'success' | 'error' | 'neutral' }> = {
  PENDING:   { icon: '⏳', heading: 'AI 正在准备生成',         hint: '即将开始生成草稿，无需手动刷新。',         tone: 'pending' },
  RUNNING:   { icon: '✨', heading: 'AI 正在生成草稿',           hint: '预计需要 10~30 秒，完成后可在下方编辑或落库。', tone: 'pending' },
  DONE:      { icon: '✅', heading: 'AI 已完成草稿',             hint: '请在下方查看并编辑，确认无误后点击「确认落库」，或点击「拒绝」驳回。', tone: 'success' },
  CONFIRMED: { icon: '👍', heading: '草稿已通过',                hint: '本次 AI 草稿已被接受，可查看历史记录。', tone: 'neutral' },
  REJECTED:  { icon: '🚫', heading: '草稿已驳回',                hint: '本次 AI 草稿已驳回，可重新发起或人工补充。', tone: 'neutral' },
  FAILED:    { icon: '⚠️', heading: 'AI 生成失败',               hint: '请稍后重试，或联系管理员排查错误。',     tone: 'error' },
}

const statusBanner = computed(() => {
  const status = props.draft?.status
  return status ? STATUS_BANNER[status] : null
})

const statusIcon = computed(() => statusBanner.value?.icon ?? '•')
const statusHeading = computed(() => statusBanner.value?.heading ?? '等待 AI 响应')
const statusHint = computed(() => {
  const fallback = statusBanner.value?.hint ?? '请稍候…'
  const err = errorMessage.value
  return statusBanner.value?.tone === 'error' && err ? `${fallback}（${err}）` : fallback
})
const statusBannerClass = computed(() => statusBanner.value?.tone ?? 'pending')

/** 只有终态且 payload 非空才能落库或拒绝，避免 PENDING 时误操作。 */
const canAct = computed(() => {
  const status = props.draft?.status
  if (!status) return false
  if (!TERMINAL_STATUSES.has(status)) return false
  if (status === 'DONE') {
    // 终态是 DONE 才允许落库/拒绝；CONFIRMED/REJECTED 已处理过
    return Boolean(draftText.value && draftText.value !== '（草稿内容为空）')
  }
  return false
})

function close() {
  emit('close')
}

function confirm() {
  emit('confirm', draftText.value)
}

function reject() {
  emit('reject')
}
</script>

<style scoped>
.ai-draft-mask {
  position: fixed;
  inset: 0;
  /* inline style 同时把 z-index 强写到 9999，确保覆盖任何宿主页面 modal。 */
  z-index: 9999;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .55);
  backdrop-filter: blur(2px);
}

.ai-draft-card {
  width: min(820px, 100%);
  max-height: min(88vh, 920px);
  display: grid;
  gap: 12px;
  padding: 22px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 32px 80px rgba(15, 23, 42, .28);
}

.ai-draft-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}

.ai-draft-badge {
  display: inline-flex;
  align-items: center;
  margin: 0 0 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(15, 118, 110, .10);
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
}

.ai-draft-header h3 {
  margin: 0;
  color: #172033;
  font-size: 20px;
}

.ai-draft-close,
.ai-draft-button {
  border-radius: 8px;
  border: 1px solid rgba(15, 118, 110, .18);
  cursor: pointer;
  font-weight: 700;
}

.ai-draft-close {
  padding: 8px 12px;
  background: #fff;
  color: #0f766e;
}

.ai-draft-meta {
  margin: 0;
  color: #475569;
  font-size: 13px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.ai-draft-meta-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #0f766e;
  font-weight: 600;
}

.ai-draft-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgba(15, 118, 110, .25);
  border-top-color: #0f766e;
  animation: ai-draft-spin .8s linear infinite;
}

@keyframes ai-draft-spin {
  to { transform: rotate(360deg); }
}

.ai-draft-meta-error {
  color: #b91c1c;
  font-weight: 600;
}

.ai-draft-status {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid transparent;
  font-size: 13px;
  line-height: 1.5;
}
.ai-draft-status-icon { font-size: 22px; line-height: 1; }
.ai-draft-status-text { display: flex; flex-direction: column; gap: 2px; }
.ai-draft-status-text strong { font-size: 14px; font-weight: 700; }
.ai-draft-status-text span { color: inherit; opacity: .85; }
.ai-draft-status.pending {
  background: rgba(14, 165, 233, .08);
  border-color: rgba(14, 165, 233, .25);
  color: #0c4a6e;
}
.ai-draft-status.success {
  background: rgba(16, 185, 129, .10);
  border-color: rgba(16, 185, 129, .28);
  color: #065f46;
}
.ai-draft-status.error {
  background: rgba(239, 68, 68, .08);
  border-color: rgba(239, 68, 68, .28);
  color: #991b1b;
}
.ai-draft-status.neutral {
  background: rgba(100, 116, 139, .08);
  border-color: rgba(100, 116, 139, .22);
  color: #334155;
}

.ai-draft-textarea {
  width: 100%;
  min-height: 360px;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 10px;
  padding: 12px;
  background: #f8fafc;
  color: #172033;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.ai-draft-textarea-locked {
  background: #f1f5f9;
  color: #64748b;
  cursor: not-allowed;
}

.ai-draft-hint {
  margin: -4px 0 0;
  font-size: 12px;
  color: #0f766e;
}

.ai-draft-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.ai-draft-button {
  min-width: 100px;
  padding: 10px 14px;
}

.ai-draft-button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.ai-draft-button.secondary {
  background: #fff;
  color: #0f766e;
}

.ai-draft-button.primary {
  border-color: transparent;
  background: #0f766e;
  color: #fff;
}
</style>
