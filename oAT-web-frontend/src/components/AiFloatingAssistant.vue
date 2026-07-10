<template>
  <div ref="rootRef" class="ai-floating" :class="{ open: panelOpen, hidden: mascotHidden }" :style="floatingStyle">
    <button v-if="mascotHidden" class="restore-button" type="button" @pointerdown="startDrag" @click="showMascot">显示 AI 助手</button>

    <button v-else class="launcher" type="button" title="打开 AI 助手" data-tooltip="打开 AI 助手" @pointerdown="startDrag" @click="togglePanel">
      <MascotCanvas :size="88" :color="mascotColor" :seed="projectId" :mood="asking ? 'thinking' : mood" :interactive="true" />
      <span v-if="!panelOpen" class="launcher-bubble">{{ launcherHint }}</span>
    </button>

    <section
      v-if="!mascotHidden && panelOpen"
      ref="panelRef"
      class="assistant-panel"
      :class="{ 'layout-locked': layoutLocked, 'custom-layout': Object.keys(panelLayout).length }"
      :style="panelStyle"
      @dblclick="resetPanelSizeFromDoubleClick"
    >
      <div class="resize-handle" title="拖拽放大" @pointerdown.stop.prevent="startResize"></div>
      <header class="panel-header" @pointerdown="startDrag">
        <div>
          <div class="eyebrow">AI Interactive</div>
          <h2>项目悬浮助手</h2>
        </div>
        <div class="panel-tools">
          <button type="button" title="恢复默认内部布局" @click="resetLayout">↺</button>
          <button type="button" title="撤销上一步内部布局调整" @click="undoLayout">↶</button>
          <button type="button" :title="layoutLocked ? '解锁内部布局拖动' : '锁定内部布局'" @click="toggleLayoutLock">{{ layoutLocked ? '🔒' : '🔓' }}</button>
          <button type="button" class="danger-tool" title="清空当前助手对话" @click="clearConversation">清</button>
          <RouterLink :to="`/p/${projectId}/ai`">工作台</RouterLink>
          <button type="button" title="隐藏小人" @click="hideMascot">-</button>
          <button type="button" title="收起助手" @click="setPanelOpenWithAnchor(false)">x</button>
        </div>
      </header>

      <div
        class="section-box messages-box layout-item"
        :class="{ collapsed: isSectionCollapsed('messages') }"
        :style="layoutItemStyle('messages')"
        @pointerdown="startLayoutDrag($event, 'messages')"
      >
        <span
          v-for="direction in layoutResizeDirections"
          :key="direction"
          :class="['layout-resizer', direction]"
          title="拖拽调整区域大小"
          @pointerdown.stop.prevent="startLayoutResize($event, 'messages', direction)"
        ></span>
        <button class="section-title section-toggle" type="button" @click="toggleSection('messages')">
          <span>对话</span>
          <span>{{ isSectionCollapsed('messages') ? '展开' : '收起' }}</span>
        </button>
        <AiFloatingMessages
          :messages="messages"
          :collapsed="isSectionCollapsed('messages')"
          :copied-message-id="copiedMessageId"
          :feedback-submitting="feedbackSubmitting"
          :empty-message="assistantContext.mascotHint || assistantContext.welcomeMessage"
          :get-message-feedback="getMessageFeedback"
          @copy="copyMessage"
          @feedback="submitMessageFeedback"
          @preset="sendPresetQuestion"
          @execute="executeAction"
        />
      </div>

      <div
        v-if="contextChips.length"
        class="context-status layout-item"
        :style="layoutItemStyle('context')"
        @pointerdown="startLayoutDrag($event, 'context')"
      >
        <span
          v-for="direction in layoutResizeDirections"
          :key="direction"
          :class="['layout-resizer', direction]"
          title="拖拽调整区域大小"
          @pointerdown.stop.prevent="startLayoutResize($event, 'context', direction)"
        ></span>
        <AiFloatingContextChips :chips="contextChips" @remove="removeContextChip" />
      </div>

      <div
        class="section-box layout-item"
        :class="{ collapsed: isSectionCollapsed('links') }"
        :style="layoutItemStyle('links')"
        @pointerdown="startLayoutDrag($event, 'links')"
      >
        <span
          v-for="direction in layoutResizeDirections"
          :key="direction"
          :class="['layout-resizer', direction]"
          title="拖拽调整区域大小"
          @pointerdown.stop.prevent="startLayoutResize($event, 'links', direction)"
        ></span>
        <button class="section-title section-toggle" type="button" @click="toggleSection('links')">
          <span>快捷入口</span>
          <span>{{ isSectionCollapsed('links') ? '展开' : '收起' }}</span>
        </button>
        <AiFloatingQuickLinks :links="normalizedQuickLinks" :collapsed="isSectionCollapsed('links')" @open="openQuickLink" />
      </div>

      <div
        class="section-box layout-item"
        :class="{ collapsed: isSectionCollapsed('starters') }"
        :style="layoutItemStyle('starters')"
        @pointerdown="startLayoutDrag($event, 'starters')"
      >
        <span
          v-for="direction in layoutResizeDirections"
          :key="direction"
          :class="['layout-resizer', direction]"
          title="拖拽调整区域大小"
          @pointerdown.stop.prevent="startLayoutResize($event, 'starters', direction)"
        ></span>
        <button class="section-title section-toggle" type="button" @click="toggleSection('starters')">
          <span>快捷提问</span>
          <span>{{ isSectionCollapsed('starters') ? '展开' : '收起' }}</span>
        </button>
        <AiFloatingStarters :starters="starterQuestions" :collapsed="isSectionCollapsed('starters')" @preset="sendPresetQuestion" />
      </div>

      <AiFloatingComposeForm
        v-model:question="question"
        :image-data="imageData"
        :recording="recording"
        :asking="asking"
        :state-text="stateText"
        :layout-style="layoutItemStyle('compose')"
        :resize-directions="layoutResizeDirections"
        @send="sendQuestion"
        @enter="handleQuestionEnter"
        @image-change="handleImageChange"
        @clear-image="clearImage"
        @toggle-voice="toggleVoiceInput"
        @layout-drag="startLayoutDrag($event, 'compose')"
        @layout-resize="(event, direction) => startLayoutResize(event, 'compose', direction)"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useProjectStore } from '@/stores/project'
import { useAuthStore } from '@/stores/auth'
import { useDialog } from '@/composables/useDialog'
import MascotCanvas from '@/components/MascotCanvas.vue'
import AiFloatingComposeForm from '@/features/ai/components/AiFloatingComposeForm.vue'
import AiFloatingContextChips from '@/features/ai/components/AiFloatingContextChips.vue'
import AiFloatingMessages from '@/features/ai/components/AiFloatingMessages.vue'
import AiFloatingQuickLinks from '@/features/ai/components/AiFloatingQuickLinks.vue'
import AiFloatingStarters from '@/features/ai/components/AiFloatingStarters.vue'
import { useAiFloatingLayout } from '@/features/ai/composables/useAiFloatingLayout'
import { useAiFloatingPageSignals } from '@/features/ai/composables/useAiFloatingPageSignals'
import { useAiFloatingShortcuts } from '@/features/ai/composables/useAiFloatingShortcuts'
import { submitAiFeedback } from '@/api/bootstrap'
import type { AIAction, AIInteractivePagePayload, AIQuickLink } from '@/api/types'
import type { AiFeedbackType, AiFloatingContextChip, AiFloatingContextChipId, AiFloatingMessage } from '@/features/ai/types'

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

const LAYOUT_VERSION = '2026-05-26-compact-internal-content-ai'
const DEFAULT_COLLAPSED_SECTIONS = ['links', 'starters'] as const

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const authStore = useAuthStore()
const dialog = useDialog()
const question = ref('')
const messages = ref<AiFloatingMessage[]>([])
const panelOpen = ref(false)
const mascotHidden = ref(false)
const asking = ref(false)
const error = ref('')
const copiedMessageId = ref('')
const feedbackSubmitting = ref(false)
const messageFeedbacks = ref<Record<string, string>>({})
const rootRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const imageData = ref('')
const recording = ref(false)
const hiddenContextChips = ref<AiFloatingContextChipId[]>([])
let recognition: SpeechRecognitionLike | null = null
let copiedMessageTimer: number | undefined
const {
  liveSignals,
  bindLivePageSignals,
  buildPageContext,
  clearLiveRowState,
  clearSignal,
  collectLiveFilterState,
  unbindLivePageSignals,
} = useAiFloatingPageSignals(rootRef)

const projectId = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : '')
const {
  buildAdaptiveQuickLinks,
  buildAdaptiveStarters,
  normalizeLinks,
  normalizeSpaUrl,
  routeStarters,
} = useAiFloatingShortcuts(projectId, liveSignals)
const context = computed(() => projectId.value ? projectStore.aiContextByProjectId[projectId.value] : undefined)
const fallbackContext = computed<AIInteractivePagePayload>(() => ({
  projectId: projectId.value,
  projectName: '当前项目',
  projectSummary: 'AI 助手正在准备项目上下文',
  welcomeMessage: '我是 AI 助手，可以帮你分析当前页面、跳转常用功能或排查测试风险。',
  mascotHint: '点我打开 AI 助手',
  onlineAppCount: 0,
  appCount: 0,
  appNames: [],
  starterQuestions: [],
  abilityCards: [],
  quickLinks: [],
  mascot: {
    mascotName: 'AI 助手',
    mascotPrimary: '#b6d900',
  },
  aiTimeout: 120,
}))
const assistantContext = computed(() => context.value || fallbackContext.value)
const mascotColor = computed(() => assistantContext.value.mascot?.mascotPrimary || '#b6d900')
const launcherHint = computed(() => {
  if (asking.value) return '分析中...'
  if (error.value) return '需要处理'
  return assistantContext.value.mascot?.mascotName || '点我提问'
})
const mood = computed(() => error.value ? 'error' : 'happy')
const stateText = computed(() => error.value || (asking.value ? '生成中...' : imageData.value ? '已附加图片' : '就绪'))
const storagePrefix = computed(() => projectId.value ? `spa-ai-floating:${projectId.value}` : '')

const normalizedQuickLinks = computed(() => normalizeLinks(buildAdaptiveQuickLinks(dynamicQuickLinks.value.length ? dynamicQuickLinks.value : assistantContext.value.quickLinks || []), route.path))
const lastReply = computed(() => projectId.value ? projectStore.aiAssistantLastReplyByProjectId[projectId.value] : undefined)
const dynamicQuickLinks = ref<AIQuickLink[]>([])
const contextChips = computed(() => buildContextChips().filter((chip) => !hiddenContextChips.value.includes(chip.id)))
const starterQuestions = computed(() => {
  const routeSpecific = routeStarters(route.path)
  return buildAdaptiveStarters([...routeSpecific, ...(assistantContext.value.starterQuestions || [])])
})
const {
  applyDefaultPanelLayout,
  clearStoredLayout,
  dragMoved,
  floatingStyle,
  installResizeObserver,
  isSectionCollapsed,
  layoutItemStyle,
  layoutLocked,
  layoutResizeDirections,
  panelLayout,
  panelStyle,
  preserveCurrentAnchor,
  resetLayout,
  resetPanelSizeFromDoubleClick,
  restoreLayoutState,
  setPanelOpenWithAnchor,
  startDrag,
  startLayoutDrag,
  startLayoutResize,
  startResize,
  teardownResizeObserver,
  toggleLayoutLock,
  toggleSection,
  undoLayout,
} = useAiFloatingLayout(rootRef, panelRef, panelOpen, storagePrefix, () => ({
  contextCount: contextChips.value.length,
  quickLinkCount: normalizedQuickLinks.value.length,
  starterCount: starterQuestions.value.length,
}))

watch(projectId, async (value) => {
  if (!value) return
  await loadContext(value)
  restoreState()
}, { immediate: true })

watch(messages, () => saveHistory(), { deep: true })
watch(panelOpen, (value) => {
  if (storagePrefix.value) sessionStorage.setItem(`${storagePrefix.value}:panel`, value ? '1' : '0')
  if (!value) {
    teardownResizeObserver()
    return
  }
  if (value) nextTick(() => {
    installResizeObserver()
    applyDefaultPanelLayout()
  })
})

watch(() => route.fullPath, () => {
  clearLiveRowState()
  nextTick(collectLiveFilterState)
})

async function loadContext(value: string) {
  try {
    await projectStore.loadAiContext(value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载 AI 助手失败'
  }
}

function uid() {
  return `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

function restoreState() {
  if (!storagePrefix.value) return
  const layoutVersionKey = `${storagePrefix.value}:layout-version`
  const isCurrentLayout = localStorage.getItem(layoutVersionKey) === LAYOUT_VERSION
  if (!isCurrentLayout) {
    clearStoredLayout()
    localStorage.setItem(layoutVersionKey, LAYOUT_VERSION)
  }
  try {
    messages.value = sanitizeMessages(JSON.parse(localStorage.getItem(`${storagePrefix.value}:history`) || '[]'))
  } catch {
    messages.value = []
  }
  mascotHidden.value = false
  localStorage.removeItem(`${storagePrefix.value}:hidden`)
  panelOpen.value = sessionStorage.getItem(`${storagePrefix.value}:panel`) === '1'
  restoreLayoutState(isCurrentLayout, [...DEFAULT_COLLAPSED_SECTIONS])
  hiddenContextChips.value = readJson<AiFloatingContextChipId[]>(`${storagePrefix.value}:hidden-context-chips`, [])
  dynamicQuickLinks.value = readJson<AIQuickLink[]>(`${storagePrefix.value}:reply-links`, [])
}

function sanitizeMessages(value: unknown): AiFloatingMessage[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is AiFloatingMessage => Boolean(item && typeof item === 'object' && 'role' in item && 'text' in item))
    .map((item) => ({
      id: typeof item.id === 'string' && item.id ? item.id : uid(),
      role: (item.role === 'user' ? 'user' : 'assistant') as AiFloatingMessage['role'],
      text: sanitizeMessageText(item.text),
      suggestions: Array.isArray(item.suggestions) ? item.suggestions.filter((entry): entry is string => typeof entry === 'string' && Boolean(entry.trim())) : undefined,
      actions: Array.isArray(item.actions) ? item.actions : undefined,
    }))
    .filter((item) => item.text)
}

function sanitizeMessageText(value: unknown) {
  const text = typeof value === 'string' ? value.trim() : String(value || '').trim()
  if (!text) return ''
  if (text.includes('INTERNAL_SERVER_ERROR') || text.includes('NullPointerException')) {
    return 'AI 助手接口发生服务端异常，请稍后重试；后端日志中会记录具体原因。'
  }
  return text
}

function saveHistory() {
  if (!storagePrefix.value) return
  localStorage.setItem(`${storagePrefix.value}:history`, JSON.stringify(sanitizeMessages(messages.value).slice(-30)))
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) as T : fallback
  } catch {
    return fallback
  }
}

function buildContextChips(): AiFloatingContextChip[] {
  const chips: AiFloatingContextChip[] = [
    { id: 'route', label: '当前页面', value: route.fullPath },
    { id: 'apps', label: '应用', value: `${assistantContext.value.appCount} 个` },
    { id: 'online', label: '在线', value: `${assistantContext.value.onlineAppCount} 个` },
  ]
  if (liveSignals.value.filters.length) chips.push({ id: 'filter', label: '当前筛选', value: liveSignals.value.filters.join('、') })
  if (liveSignals.value.tableHover) chips.push({ id: 'hover', label: '当前悬停', value: liveSignals.value.tableHover })
  if (liveSignals.value.tableSelection) chips.push({ id: 'selection', label: '当前选中', value: liveSignals.value.tableSelection })
  if (lastReply.value?.topic) chips.push({ id: 'topic', label: '主题', value: lastReply.value.topic })
  if (imageData.value) chips.push({ id: 'image', label: '图片', value: '已附加' })
  return chips
}

function removeContextChip(id: AiFloatingContextChipId) {
  if (id === 'filter' || id === 'hover' || id === 'selection') {
    clearSignal(id)
    return
  }
  hiddenContextChips.value = Array.from(new Set([...hiddenContextChips.value, id]))
  if (storagePrefix.value) localStorage.setItem(`${storagePrefix.value}:hidden-context-chips`, JSON.stringify(hiddenContextChips.value))
}

function togglePanel() {
  if (dragMoved.value) {
    dragMoved.value = false
    return
  }
  setPanelOpenWithAnchor(!panelOpen.value)
}

function hideMascot() {
  preserveCurrentAnchor(() => {
    mascotHidden.value = true
    panelOpen.value = false
  })
}

function showMascot() {
  if (dragMoved.value) {
    dragMoved.value = false
    return
  }
  preserveCurrentAnchor(() => {
    mascotHidden.value = false
    panelOpen.value = true
  })
}

async function clearConversation() {
  const confirmed = await dialog.confirm({
    title: '清空 AI 对话',
    message: '确认清空当前项目的悬浮助手对话和后端 AI 助手记忆？AI 工作台会话不会受影响。',
    confirmText: '确认清空',
    tone: 'danger',
  })
  if (!confirmed) return
  messages.value = []
  error.value = ''
  question.value = ''
  imageData.value = ''
  try {
    await projectStore.resetAiSessionState(projectId.value, 'assistant')
  } catch (err) {
    error.value = friendlyAiError(err)
  }
}

async function sendQuestion() {
  if (asking.value) return
  const text = question.value.trim()
  if (!projectId.value) {
    error.value = '请先进入或选择一个项目后再使用 AI 助手'
    return
  }
  if (!text && !imageData.value) {
    error.value = '请输入问题或上传图片'
    return
  }
  asking.value = true
  error.value = ''
  messages.value.push({ id: uid(), role: 'user', text: text || '[图片提问]' })
  try {
    const result = await projectStore.askAi(projectId.value, {
      question: text,
      pageContext: buildPageContext(text, route.fullPath),
      imageData: imageData.value || undefined,
      sessionState: JSON.stringify({ messages: messages.value.slice(-20) }),
      memoryScope: 'assistant',
    })
    const links = normalizeLinks(result.quickLinks || [], route.path, false)
    if (links.length) {
      dynamicQuickLinks.value = links
      if (storagePrefix.value) localStorage.setItem(`${storagePrefix.value}:reply-links`, JSON.stringify(links))
      if (isSectionCollapsed('links')) toggleSection('links')
    }
    messages.value.push({
      id: uid(),
      role: 'assistant',
      text: sanitizeMessageText(result.answer || '暂无回答'),
      suggestions: result.suggestions || [],
      actions: result.actions || [],
    })
    await executeAutoAction(result.actions)
    question.value = ''
    imageData.value = ''
  } catch (err) {
    error.value = friendlyAiError(err)
    messages.value.push({ id: uid(), role: 'assistant', text: error.value })
  } finally {
    asking.value = false
  }
}

async function handleQuestionEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  await sendQuestion()
}

async function sendPresetQuestion(text: string) {
  question.value = text
  await nextTick()
  await sendQuestion()
}

async function copyMessage(text: string, messageId: string) {
  try {
    await writeClipboardText(getSelectedMessageText() || text)
    copiedMessageId.value = messageId
    if (copiedMessageTimer) window.clearTimeout(copiedMessageTimer)
    copiedMessageTimer = window.setTimeout(() => {
      if (copiedMessageId.value === messageId) copiedMessageId.value = ''
    }, 1400)
    error.value = ''
  } catch {
    error.value = '复制失败，请手动选择文本复制'
  }
}

function getMessageFeedback(messageId: string): string {
  return messageFeedbacks.value[messageId] || ''
}

async function submitMessageFeedback(
  messageId: string,
  answerText: string,
  feedbackType: 'helpful' | 'not_helpful' | 'incorrect' | 'incomplete',
  rating: number,
  requireComment = false,
) {
  if (getMessageFeedback(messageId) === feedbackType) return
  let comment = ''
  if (requireComment) {
    const result = await dialog.prompt({
      title: '补充反馈',
      message: '请简单说明哪里需要改进，便于 AI 后续学习。',
      placeholder: '例如：回答不准确、信息过时...',
      confirmText: '提交反馈',
      maxLength: 200,
    })
    if (result === null || !result.trim()) return
    comment = result
  }
  feedbackSubmitting.value = true
  try {
    const question = messages.value
      .slice(0, messages.value.findIndex((m) => m.id === messageId))
      .reverse()
      .find((m) => m.role === 'user')?.text || ''
    await submitAiFeedback({
      projectId: projectId.value,
      question,
      answer: answerText,
      rating,
      feedbackType,
      comment,
    })
    messageFeedbacks.value = { ...messageFeedbacks.value, [messageId]: feedbackType }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '反馈提交失败'
  } finally {
    feedbackSubmitting.value = false
  }
}

async function writeClipboardText(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!copied) throw new Error('copy failed')
}

function getSelectedMessageText() {
  const selection = window.getSelection()
  const panel = panelRef.value
  if (!selection || !panel || selection.isCollapsed || selection.rangeCount === 0) return ''
  const range = selection.getRangeAt(0)
  if (!isNodeInside(panel, range.startContainer) || !isNodeInside(panel, range.endContainer)) return ''
  return selection.toString().trim()
}

function isNodeInside(container: HTMLElement, node: Node) {
  const element = node.nodeType === Node.ELEMENT_NODE ? node as Element : node.parentElement
  return Boolean(element && container.contains(element))
}

function friendlyAiError(err: unknown) {
  const message = err instanceof Error ? err.message : ''
  if (message === 'Failed to fetch' || message.includes('无法连接后端')) {
    return '无法连接后端 AI 服务，请确认后端已启动，并检查前端代理或跨域配置。'
  }
  if (message.includes('INTERNAL_SERVER_ERROR') || message.includes('500') || message.includes('NullPointerException')) {
    return 'AI 助手接口发生服务端异常，请稍后重试；后端日志中会记录具体原因。'
  }
  return message || 'AI 提问失败'
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

async function openQuickLink(link: AIQuickLink) {
  const target = normalizeSpaUrl(link.url)
  if (!target) return
  if (/^https?:\/\//.test(target)) {
    window.open(target, '_blank', 'noopener,noreferrer')
    return
  }
  await router.push(target)
  setPanelOpenWithAnchor(false)
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
    setPanelOpenWithAnchor(false)
    return
  }
  const target = normalizeSpaUrl(action.url)
  if (!target) return
  if (/^https?:\/\//.test(target)) {
    window.open(target, '_blank', 'noopener,noreferrer')
    return
  }
  await router.push(target)
  setPanelOpenWithAnchor(false)
}

async function executeAutoAction(actions?: AIAction[]) {
  const action = actions?.find((item) => item.payload?.autoExecute === true && !item.requireConfirm && item.type !== 'logout')
  if (!action) return
  messages.value.push({ id: uid(), role: 'assistant', text: `已按建议执行：${action.title}` })
  await executeAction(action)
}


onMounted(() => {
  if (projectId.value && !context.value) loadContext(projectId.value)
  bindLivePageSignals()
  nextTick(() => {
    installResizeObserver()
    applyDefaultPanelLayout()
  })
})

onBeforeUnmount(() => {
  teardownResizeObserver()
  unbindLivePageSignals()
  clearLiveRowState()
  recognition?.stop()
  if (copiedMessageTimer) window.clearTimeout(copiedMessageTimer)
})
</script>

<style scoped src="@/features/ai/styles/ai-floating-assistant.css"></style>
