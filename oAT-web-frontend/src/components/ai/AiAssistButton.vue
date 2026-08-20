<template>
  <button class="ai-assist-button" type="button" :disabled="disabled || loading" @click="submit">
    <span class="ai-assist-icon" aria-hidden="true">AI</span>
    <span>{{ loading ? loadingText : label }}</span>
  </button>
</template>

<script setup lang="ts">
import { ref } from 'vue'

import { generateAiDraft, getAiDraft, submitAssetAiTask, type AiDraftResponse } from '@/api/ai'

const SESSION_TTL_MS = 12 * 60 * 60 * 1000

/**
 * 生成/读取"AI 会话"：同 projectId + intent 复用同一个 sessionId，让 ovanth 会话历史能查到
 * 一连串的 AI 调用。sessionStorage 里带 createdAt，过期自动重新开新会话。
 */
function resolveSessionId(projectId: string, intent: string): string {
  const key = `oAT.ai.session.${projectId}.${intent}`
  try {
    const raw = window.sessionStorage.getItem(key)
    if (raw) {
      const parsed = JSON.parse(raw) as { id: string; createdAt: number }
      if (parsed?.id && Date.now() - parsed.createdAt < SESSION_TTL_MS) {
        return parsed.id
      }
    }
  } catch {
    // sessionStorage 不可用时降级到一次性 sessionId，仍能让本轮 UI 看得到历史
  }
  const id = (crypto.randomUUID && crypto.randomUUID().replace(/-/g, '')) ||
    `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
  try {
    window.sessionStorage.setItem(key, JSON.stringify({ id, createdAt: Date.now() }))
  } catch {
    /* noop */
  }
  return id
}

const props = withDefaults(
  defineProps<{
    projectId: string
    intent: string
    label?: string
    loadingText?: string
    disabled?: boolean
    context?: Record<string, unknown>
    assetDomain?: 'requirements' | 'testcases' | 'defects' | 'coverage' | 'sources' | 'git' | 'versions'
    assetId?: string
    assetAction?: 'parse' | 'analyze'
    sourceText?: string
    emptyMessage?: string
    pollIntervalMs?: number
    maxPollCount?: number
  }>(),
  {
    label: 'AI 辅助',
    loadingText: '生成中',
    disabled: false,
    context: () => ({}),
    assetDomain: undefined,
    assetId: '',
    assetAction: 'analyze',
    sourceText: '',
    emptyMessage: '请先粘贴 AI 输入原文',
    pollIntervalMs: 2000,
    maxPollCount: 60,
  },
)

const emit = defineEmits<{
  draft: [draft: AiDraftResponse]
  error: [error: unknown]
}>()

const loading = ref(false)

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    const sessionId = resolveSessionId(props.projectId, props.intent)
    const submission = props.assetDomain && props.assetId
        ? await submitAssetAiTask(props.projectId, props.assetDomain, props.assetId, props.assetAction, sessionId)
        : props.sourceText?.trim()
          ? await generateAiDraft(props.projectId, props.intent, props.sourceText, sessionId)
        : (() => {
            throw new Error(props.emptyMessage)
          })()
    const draft = await pollDraft(submission.taskId)
    emit('draft', draft)
  } catch (error) {
    emit('error', error)
  } finally {
    loading.value = false
  }
}

async function pollDraft(taskId: string): Promise<AiDraftResponse> {
  for (let i = 0; i < props.maxPollCount; i += 1) {
    const draft = await getAiDraft(props.projectId, taskId)
    const status = draft.status
    if (status !== 'PENDING' && status !== 'RUNNING') {
      return draft
    }
    await new Promise((resolve) => window.setTimeout(resolve, props.pollIntervalMs))
  }
  throw new Error('AI 任务执行超时，请稍后刷新草稿状态')
}
</script>

<style scoped>
.ai-assist-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 36px;
  padding: 8px 12px;
  border: 1px solid rgba(15, 118, 110, .18);
  border-radius: 8px;
  background: #f0fdfa;
  color: #0f766e;
  cursor: pointer;
  font-weight: 700;
}

.ai-assist-button:disabled {
  cursor: not-allowed;
  opacity: .62;
}

.ai-assist-icon {
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: #0f766e;
  color: #fff;
  font-size: 11px;
  line-height: 1;
}
</style>
