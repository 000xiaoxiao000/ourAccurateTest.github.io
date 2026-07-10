<template>
  <section class="ai-page" :style="{ '--ai-accent': context?.mascot?.mascotPrimary || '#0f766e' }">
    <div class="page-header">
      <div>
        <div class="eyebrow">AI Workspace</div>
        <h1>{{ context?.projectName || 'AI 工作台' }}</h1>
        <p class="subtext">{{ context?.projectSummary || '正在加载项目 AI 上下文...' }}</p>
      </div>
      <div class="header-actions">
        <button class="secondary-button" type="button" @click="clearMemory">清空记忆</button>
        <button class="action-button" type="button" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="status-card">正在加载 AI 工作台...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="context">
      <ProjectAiHero :context="context" :project-id="projectId" :mood="asking ? 'thinking' : error ? 'error' : 'happy'" @use-question="useQuestion" />

      <div class="page-grid">
        <ProjectAiSidebar
          v-model:session-search="sessionSearch"
          v-model:session-sort="sessionSort"
          v-model:anchor-filter-mode="anchorFilterMode"
          v-model:anchor-search="anchorSearch"
          v-model:preview-anchor-id="previewAnchorId"
          :context="context"
          :visible-sessions="visibleSessions"
          :active-session-id="activeSessionId"
          :merged-quick-links="mergedQuickLinks"
          :feedback-stats="feedbackStats"
          :learning-report="learningReport"
          :learning-loading="learningLoading"
          :learning-error="learningError"
          :question-anchors="questionAnchors"
          :visible-question-anchors="visibleQuestionAnchors"
          :active-anchor-id="activeAnchorId"
          :expanded-anchor-ids="expandedAnchorIds"
          :timeline-items="timelineItems"
          @new-session="newSession"
          @switch-session="switchSession"
          @toggle-pin-session="togglePinSession"
          @rename-session="renameSession"
          @delete-session="deleteSession"
          @use-question="useQuestion"
          @open-link="openLink"
          @refresh-learning="refreshLearningPanel"
          @clear-learning-suggestions="clearLearningSuggestions"
          @scroll-to-anchor="scrollToAnchor"
          @toggle-anchor-text="toggleAnchorText"
          @copy-anchor-link="copyAnchorLink"
        />

        <div class="content-stack">
          <ProjectAiAskWorkspace
            ref="askWorkspaceRef"
            v-model:question="question"
            v-model:preview-anchor-id="previewAnchorId"
            :asking="asking"
            :image-data="imageData"
            :recording="recording"
            :active-messages="activeMessages"
            :message-sections="messageSections"
            :question-anchors="questionAnchors"
            :floating-anchor-dots="floatingAnchorDots"
            :floating-track-height="floatingTrackHeight"
            :active-anchor-id="activeAnchorId"
            :target-anchor-id="targetAnchorId"
            :copied-message-id="copiedMessageId"
            :feedback-submitting="feedbackSubmitting"
            :message-feedbacks="messageFeedbacks"
            @scroll-to-anchor="scrollToAnchor"
            @copy-message="copyMessage"
            @submit-message-feedback="submitMessageFeedback"
            @submit-ask="submitAsk"
            @ask-enter="handleAskEnter"
            @image-change="handleImageChange"
            @select-image="selectImage"
            @clear-image="clearImage"
            @toggle-voice-input="toggleVoiceInput"
            @stop-ask="stopAsk"
            @save-session="saveSession"
          />

          <ProjectAiReplyPanel :reply="reply" :reply-meta-entries="replyMetaEntries" @use-question="useQuestion" @open-link="openLink" @execute-action="executeAction" />

        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearAiLearningSuggestions, fetchAiFeedbackStats, fetchAiLearningReport, submitAiFeedback } from '@/api/bootstrap'
import { backendApiUrl } from '@/api/http'
import { useDialog } from '@/composables/useDialog'
import ProjectAiAskWorkspace from '@/features/ai/components/ProjectAiAskWorkspace.vue'
import ProjectAiHero from '@/features/ai/components/ProjectAiHero.vue'
import ProjectAiReplyPanel from '@/features/ai/components/ProjectAiReplyPanel.vue'
import ProjectAiSidebar from '@/features/ai/components/ProjectAiSidebar.vue'
import { useProjectAiAnchors } from '@/features/ai/composables/useProjectAiAnchors'
import { useProjectAiSessions } from '@/features/ai/composables/useProjectAiSessions'
import type { AiSessionMessage } from '@/features/ai/types'
import { useProjectStore } from '@/stores/project'
import { useAuthStore } from '@/stores/auth'
import type { AIAction, AIFeedbackPayload, AIFeedbackStats, AILearningReport, AIQuickLink } from '@/api/types'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const authStore = useAuthStore()
const dialog = useDialog()
const projectId = computed(() => String(route.params.projectId || ''))
const context = computed(() => projectStore.aiContextByProjectId[projectId.value])
const reply = computed(() => projectStore.aiLastReplyByProjectId[projectId.value])
type SpeechRecognitionLike = {
  lang: string
  continuous: boolean
  interimResults: boolean
  start: () => void
  stop: () => void
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null
  onend: (() => void) | null
  onerror: (() => void) | null
}
type SpeechRecognitionConstructor = new () => SpeechRecognitionLike

const loading = ref(false)
const asking = ref(false)
const error = ref('')
const question = ref('')
const askWorkspaceRef = ref<InstanceType<typeof ProjectAiAskWorkspace> | null>(null)
const imageData = ref('')
const recording = ref(false)
let recognition: SpeechRecognitionLike | null = null
const anchorFilterMode = ref<'all' | 'pending'>('all')
const anchorSearch = ref('')
const copiedMessageId = ref('')
const feedbackSubmitting = ref(false)
const feedbackMessage = ref('')
const messageFeedbacks = ref<Record<string, string>>({})
const learningLoading = ref(false)
const learningError = ref('')
const feedbackStats = ref<AIFeedbackStats | null>(null)
const learningReport = ref<AILearningReport | null>(null)
let askAbortController: AbortController | null = null
let copiedMessageTimer: number | undefined

const activeMessages = computed(() => activeSession.value?.messages || [])
const {
  activeAnchorId,
  previewAnchorId,
  targetAnchorId,
  expandedAnchorIds,
  messageSections,
  questionAnchors,
  visibleQuestionAnchors,
  timelineItems,
  floatingTrackHeight,
  floatingAnchorDots,
  resetAnchorState,
  scrollToAnchor,
  toggleAnchorText,
  copyAnchorLink,
  updateActiveAnchorFromScroll,
  clearAnchorTimers,
} = useProjectAiAnchors(activeMessages, anchorFilterMode, anchorSearch)

const {
  activeSessionId,
  sessionSearch,
  sessionSort,
  activeSession,
  visibleSessions,
  createMessageId,
  touchSession,
  hydrateSessions,
  buildSessionState,
  syncSessionState,
  ensureActiveSession,
  switchSession: switchAiSession,
  newSession,
  renameSession,
  deleteSession,
  togglePinSession,
} = useProjectAiSessions({
  projectId,
  persist: (id, state) => projectStore.persistAiSessionState(id, state),
  onError: (message) => {
    error.value = message
  },
  onSessionSwitched: () => {
    resetAnchorState()
    nextTick(updateActiveAnchorFromScroll)
  },
  promptTitle: (currentTitle) => dialog.prompt({
    title: '重命名会话',
    message: '请输入新的会话名称，便于在左侧会话列表中快速定位。',
    defaultValue: currentTitle,
    placeholder: '会话名称',
    confirmText: '保存名称',
  }),
  confirmDelete: (title) => dialog.confirm({
    title: '删除 AI 会话',
    message: `确认删除会话「${title}」？该会话中的问题、回答和图片上下文将从当前项目记忆中移除。`,
    confirmText: '确认删除',
    tone: 'danger',
  }),
})

const mergedQuickLinks = computed(() => mergeQuickLinks([...(context.value?.quickLinks || []), ...(reply.value?.quickLinks || [])]))
const lastUserQuestion = computed(() => [...activeMessages.value].reverse().find((item) => item.role === 'user')?.text || reply.value?.question || '')
const lastAssistantAnswer = computed(() => reply.value?.answer || [...activeMessages.value].reverse().find((item) => item.role === 'assistant')?.text || '')
const replyMetaEntries = computed(() => {
  const currentReply = reply.value
  if (!currentReply) return []
  const entries: Array<{ label: string; value: string }> = []
  if (typeof currentReply.confidence === 'number') entries.push({ label: '置信度', value: `${Math.round(currentReply.confidence * 100)}%` })
  if (currentReply.usedTools?.length) entries.push({ label: '使用工具', value: currentReply.usedTools.join('、') })
  if (currentReply.needMoreData !== undefined) entries.push({ label: '需要更多数据', value: currentReply.needMoreData ? '是' : '否' })
  const responseTime = currentReply.metadata?.responseTime
  if (typeof responseTime === 'number') entries.push({ label: '响应耗时', value: `${responseTime}ms` })
  const routeName = currentReply.metadata?.route || currentReply.metadata?.topic
  if (routeName) entries.push({ label: '识别场景', value: String(routeName) })
  return entries
})
watch(sessionSort, () => {
  syncSessionState()
})

async function refreshLearningPanel() {
  learningLoading.value = true
  learningError.value = ''
  try {
    const [stats, report] = await Promise.all([
      fetchAiFeedbackStats(projectId.value),
      fetchAiLearningReport(),
    ])
    feedbackStats.value = stats
    learningReport.value = report
  } catch (err) {
    learningError.value = err instanceof Error ? err.message : '加载学习状态失败'
  } finally {
    learningLoading.value = false
  }
}

async function clearLearningSuggestions() {
  const confirmed = await dialog.confirm({
    title: '清空优化建议',
    message: '确认清空当前 AI 自主学习面板中的优化建议？这不会删除反馈记录和统计数据。',
    confirmText: '确认清空',
    tone: 'warning',
  })
  if (!confirmed) return
  learningLoading.value = true
  learningError.value = ''
  try {
    await clearAiLearningSuggestions()
    const [stats, report] = await Promise.all([
      fetchAiFeedbackStats(projectId.value),
      fetchAiLearningReport(),
    ])
    feedbackStats.value = stats
    learningReport.value = report
  } catch (err) {
    learningError.value = err instanceof Error ? err.message : '清空优化建议失败'
  } finally {
    learningLoading.value = false
  }
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadAiContext(projectId.value)
    await refreshLearningPanel()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载 AI 工作台失败'
  } finally {
    loading.value = false
  }
}

async function useQuestion(text: string) {
  question.value = text
  await focusAskForm()
}

async function focusAskForm() {
  await nextTick()
  askWorkspaceRef.value?.scrollAskFormIntoView()
  askWorkspaceRef.value?.focusAskInput()
}

function mergeQuickLinks(links: AIQuickLink[]) {
  const seen = new Set<string>()
  return links.filter((link) => {
    if (!link?.url || seen.has(link.url)) return false
    seen.add(link.url)
    return true
  })
}

function switchSession(sessionId: string) {
  switchAiSession(sessionId)
}

async function copyMessage(text: string, messageId: string) {
  if (!text) return
  try {
    await navigator.clipboard?.writeText(text)
    copiedMessageId.value = messageId
    if (copiedMessageTimer) window.clearTimeout(copiedMessageTimer)
    copiedMessageTimer = window.setTimeout(() => {
      if (copiedMessageId.value === messageId) copiedMessageId.value = ''
    }, 1400)
  } catch {
    window.prompt('复制消息内容', text)
  }
}

async function submitAsk() {
  if (asking.value) return
  if (!question.value.trim() && !imageData.value) {
    error.value = '请输入问题或上传图片'
    return
  }
  asking.value = true
  error.value = ''
  try {
    ensureActiveSession()
    const currentQuestion = question.value.trim()
    activeSession.value?.messages.push({
      id: createMessageId(),
      role: 'user',
      text: currentQuestion || '[图片提问]',
    })
    touchSession(activeSession.value)
    if (activeSession.value && activeSession.value.title === '新会话') {
      activeSession.value.title = currentQuestion.slice(0, 20)
    }
    const assistantMessage: AiSessionMessage = { id: createMessageId(), role: 'assistant', text: '正在连接 AI 流式响应...' }
    activeSession.value?.messages.push(assistantMessage)
    const startedAt = performance.now()
    await askAiWithFallback(currentQuestion, assistantMessage)
    assistantMessage.responseTime = Math.max(1, Math.round(performance.now() - startedAt))
    await executeAutoAction(reply.value?.actions)
    touchSession(activeSession.value)
    await syncSessionState()
    await nextTick()
    const latestAnchor = questionAnchors.value[questionAnchors.value.length - 1]
    if (latestAnchor) scrollToAnchor(latestAnchor.id)
    question.value = ''
    imageData.value = ''
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      const lastAssistant = [...activeMessages.value].reverse().find((item) => item.role === 'assistant')
      if (lastAssistant) lastAssistant.text = `${lastAssistant.text}\n[已停止生成]`
      error.value = '已停止生成'
      await syncSessionState()
    } else {
      error.value = friendlyAiError(err)
    }
  } finally {
    askAbortController = null
    asking.value = false
  }
}

async function handleAskEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  await submitAsk()
}

function stopAsk() {
  askAbortController?.abort()
}

async function submitFeedback(feedbackType: AIFeedbackPayload['feedbackType'], rating: number, requireComment = false) {
  if (!reply.value && !lastAssistantAnswer.value) return
  let comment = ''
  if (requireComment) {
    comment = (await dialog.prompt({
      title: '补充反馈',
      message: '请简单说明哪里需要改进，便于 AI 后续学习。',
      placeholder: '例如：入口不对、回答不完整...',
      confirmText: '提交反馈',
    }) || '').trim()
    if (!comment) return
  }
  feedbackSubmitting.value = true
  feedbackMessage.value = ''
  try {
    await submitAiFeedback({
      projectId: projectId.value,
      question: lastUserQuestion.value,
      answer: lastAssistantAnswer.value,
      rating,
      feedbackType,
      comment,
      usedTools: reply.value?.usedTools?.join(','),
      responseTime: typeof reply.value?.metadata?.responseTime === 'number' ? reply.value.metadata.responseTime : undefined,
    })
    feedbackMessage.value = '反馈已提交，感谢帮助 AI 改进。'
    await refreshLearningPanel()
  } catch (err) {
    feedbackMessage.value = err instanceof Error ? err.message : '反馈提交失败'
  } finally {
    feedbackSubmitting.value = false
  }
}

function getMessageFeedback(messageId: string): string {
  return messageFeedbacks.value[messageId] || ''
}

async function submitMessageFeedback(
  messageId: string,
  answerText: string,
  feedbackType: AIFeedbackPayload['feedbackType'],
  rating: number,
  requireComment = false,
) {
  if (getMessageFeedback(messageId) === feedbackType) return
  let comment = ''
  if (requireComment) {
    comment = (await dialog.prompt({
      title: '补充反馈',
      message: '请简单说明哪里需要改进，便于 AI 后续学习。',
      placeholder: '例如：回答不准确、信息过时...',
      confirmText: '提交反馈',
    }) || '').trim()
    if (!comment) return
  }
  feedbackSubmitting.value = true
  try {
    const question = activeMessages.value
      .slice(0, activeMessages.value.findIndex((m) => m.id === messageId))
      .reverse()
      .find((m) => m.role === 'user')?.text || lastUserQuestion.value
    await submitAiFeedback({
      projectId: projectId.value,
      question,
      answer: answerText,
      rating,
      feedbackType,
      comment,
      usedTools: reply.value?.usedTools?.join(','),
      responseTime: typeof reply.value?.metadata?.responseTime === 'number' ? reply.value.metadata.responseTime : undefined,
    })
    messageFeedbacks.value = { ...messageFeedbacks.value, [messageId]: feedbackType }
    await refreshLearningPanel()
  } catch (err) {
    feedbackMessage.value = err instanceof Error ? err.message : '反馈提交失败'
  } finally {
    feedbackSubmitting.value = false
  }
}

function normalizeSpaUrl(url?: string) {
  if (!url) return ''
  if (url.startsWith('/api/')) return url
  try {
    const parsed = new URL(url, window.location.origin)
    if (parsed.origin === window.location.origin) {
      return `${parsed.pathname}${parsed.search}${parsed.hash}`
    }
    return url
  } catch {
    return url.startsWith('/') ? url : `/${url}`
  }
}

async function openLink(link: AIQuickLink) {
  const target = normalizeSpaUrl(link.url)
  if (!target) return
  if (/^https?:\/\//.test(target)) {
    window.open(target, '_blank', 'noopener,noreferrer')
    return
  }
  await router.push(target)
}

async function executeAction(action: AIAction) {
  if (action.requireConfirm) {
    const confirmed = await dialog.confirm({
      title: action.title || '执行 AI 建议动作',
      message: action.confirmText || `确认执行${action.title}？`,
      confirmText: '确认执行',
      tone: action.type === 'logout' ? 'danger' : 'warning',
    })
    if (!confirmed) return
  }
  if (action.type === 'logout') {
    await authStore.logout()
    await router.replace('/login')
    return
  }
  const target = normalizeSpaUrl(action.url)
  if (!target) return
  if (/^https?:\/\//.test(target)) {
    window.open(target, '_blank', 'noopener,noreferrer')
    return
  }
  await router.push(target)
}

async function executeAutoAction(actions?: AIAction[]) {
  const action = actions?.find((item) => item.payload?.autoExecute === true && !item.requireConfirm && item.type !== 'logout')
  if (!action) return
  const lastAssistant = [...activeMessages.value].reverse().find((item) => item.role === 'assistant')
  if (lastAssistant) lastAssistant.text = `${lastAssistant.text}\n\n[已按建议执行：${action.title}]`
  await executeAction(action)
}


async function askAiWithFallback(currentQuestion: string, assistantMessage: AiSessionMessage) {
  try {
    await askAiStreaming(currentQuestion, assistantMessage)
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    assistantMessage.text = '流式响应不可用，正在切换普通响应...'
    const reply = await projectStore.askAi(projectId.value, {
      question: currentQuestion,
      pageContext: `route=/p/${projectId.value}/ai`,
      imageData: imageData.value || undefined,
      sessionState: buildSessionState(),
      activeSessionId: activeSessionId.value,
      sessionSortMode: sessionSort.value,
      memoryScope: 'workbench',
    })
    assistantMessage.text = reply.answer || reply.topic || 'AI 已返回结果，但没有可展示的文本。'
  }
}

function friendlyAiError(err: unknown) {
  if (err instanceof TypeError && err.message === 'Failed to fetch') {
    return '无法连接 AI 服务，请确认后端服务已启动，并且前端允许访问后端 API。'
  }
  if (err instanceof Error && err.message === 'Failed to fetch') {
    return '无法连接 AI 服务，请确认后端服务已启动，并且前端允许访问后端 API。'
  }
  if (err instanceof Error && (err.message.includes('INTERNAL_SERVER_ERROR') || err.message.includes('500') || err.message.includes('NullPointerException'))) {
    return 'AI 助手接口发生服务端异常，请稍后重试；后端日志中会记录具体原因。'
  }
  return err instanceof Error ? err.message : 'AI 提问失败'
}

async function askAiStreaming(currentQuestion: string, assistantMessage: AiSessionMessage) {
  askAbortController = new AbortController()
  const body = new URLSearchParams()
  body.set('question', currentQuestion)
  body.set('pageContext', `route=/p/${projectId.value}/ai`)
  body.set('sessionState', buildSessionState())
  body.set('activeSessionId', activeSessionId.value)
  body.set('sessionSortMode', sessionSort.value)
  body.set('memoryScope', 'workbench')
  if (imageData.value) body.set('imageData', imageData.value)
  const response = await fetch(backendApiUrl(`/api/projects/${projectId.value}/ai/ask/stream`), {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'text/event-stream', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body,
    signal: askAbortController.signal,
  })
  if (!response.ok || !response.body) throw new Error(`AI 流式请求失败 (${response.status})`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let answer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() || ''
    for (const chunk of chunks) {
      const event = parseSseChunk(chunk)
      if (!event) continue
      if (event.event === 'content') {
        answer += eventText(event.data.content || event.data.text)
        assistantMessage.text = answer || 'AI 正在生成...'
      } else if (event.event === 'tool_call') {
        assistantMessage.text = `正在调用工具：${event.data.primaryTool || event.data.intent || '分析中'}...`
      } else if (event.event === 'thinking') {
        assistantMessage.text = eventText(event.data.message) || 'AI 正在分析...'
      } else if (event.event === 'complete') {
        assistantMessage.text = answer || eventText(event.data.answer) || eventText(event.data.content) || assistantMessage.text
      } else if (event.event === 'error') {
        throw new Error(eventText(event.data.message) || 'AI 流式响应失败')
      }
    }
  }
}

function parseSseChunk(chunk: string) {
  const lines = chunk.split('\n')
  const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
  const dataText = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
  if (!dataText) return null
  try {
    return { event, data: JSON.parse(dataText) as Record<string, string | number | boolean | undefined> }
  } catch {
    return { event, data: { content: dataText } }
  }
}

function eventText(value: unknown) {
  if (value === undefined || value === null || value === false) return ''
  return typeof value === 'string' ? value : String(value)
}

async function saveSession() {
  try {
    await projectStore.persistAiSessionState(projectId.value, buildSessionState())
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存会话状态失败'
  }
}

async function clearMemory() {
  const confirmed = await dialog.confirm({
    title: '清空 AI 记忆',
    message: '确认清空当前项目 AI 工作台的会话记忆？清空后会重新创建一个空会话。',
    confirmText: '确认清空',
    tone: 'danger',
  })
  if (!confirmed) return
  try {
    await projectStore.resetAiSessionState(projectId.value)
    hydrateSessions('')
  } catch (err) {
    error.value = err instanceof Error ? err.message : '清空 AI 记忆失败'
  }
}


function selectImage() {
  askWorkspaceRef.value?.selectImageFile()
}

function handleImageChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    error.value = '仅支持图片文件'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    error.value = '图片不能超过 10MB'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    imageData.value = String(reader.result || '')
  }
  reader.readAsDataURL(file)
  input.value = ''
}

function clearImage() {
  imageData.value = ''
}

function toggleVoiceInput() {
  const api = (window as unknown as { SpeechRecognition?: SpeechRecognitionConstructor; webkitSpeechRecognition?: SpeechRecognitionConstructor })
  const SpeechRecognition = api.SpeechRecognition || api.webkitSpeechRecognition
  if (!SpeechRecognition) {
    error.value = '当前浏览器不支持语音输入'
    return
  }
  if (recording.value) {
    recognition?.stop()
    recording.value = false
    return
  }
  recognition = new SpeechRecognition()
  recognition.lang = 'zh-CN'
  recognition.continuous = false
  recognition.interimResults = false
  recognition.onresult = (event) => {
    const transcript = event.results[0]?.[0]?.transcript || ''
    question.value = `${question.value}${question.value ? ' ' : ''}${transcript}`
  }
  recognition.onend = () => {
    recording.value = false
  }
  recognition.onerror = () => {
    error.value = '语音识别失败'
    recording.value = false
  }
  recording.value = true
  recognition.start()
}

onMounted(async () => {
  await load()
  hydrateSessions(context.value?.sessionState)
  await nextTick()
  window.addEventListener('scroll', updateActiveAnchorFromScroll, { passive: true })
  window.addEventListener('resize', updateActiveAnchorFromScroll, { passive: true })
  if (window.location.hash.startsWith('#ai-question-anchor-')) {
    scrollToAnchor(window.location.hash.slice(1))
  } else {
    updateActiveAnchorFromScroll()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateActiveAnchorFromScroll)
  window.removeEventListener('resize', updateActiveAnchorFromScroll)
  clearAnchorTimers()
  if (copiedMessageTimer) window.clearTimeout(copiedMessageTimer)
})
</script>

<style scoped src="@/features/ai/styles/project-ai-page.css"></style>
