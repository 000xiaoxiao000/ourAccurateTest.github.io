import { computed, ref, type Ref } from 'vue'

import type { AiMessageSection, AiQuestionAnchor, AiSessionMessage } from '@/features/ai/types'

export function useProjectAiAnchors(
  activeMessages: Ref<AiSessionMessage[]> | { value: AiSessionMessage[] },
  anchorFilterMode: Ref<'all' | 'pending'>,
  anchorSearch: Ref<string>,
) {
  const activeAnchorId = ref('')
  const previewAnchorId = ref('')
  const targetAnchorId = ref('')
  const expandedAnchorIds = ref(new Set<string>())
  let targetAnchorTimer: number | undefined

  const messageSections = computed<AiMessageSection[]>(() => {
    const sections: AiMessageSection[] = []
    let questionIndex = 0
    let currentAnchorId = ''
    activeMessages.value.forEach((message, index) => {
      const startsQuestion = message.role === 'user'
      if (startsQuestion) {
        questionIndex += 1
        currentAnchorId = questionAnchorId(questionIndex)
      }
      const anchorId = currentAnchorId || `ai-message-${message.id}`
      const nextMessage = activeMessages.value[index + 1]
      sections.push({
        id: message.id,
        anchorId,
        message,
        startsQuestion,
        endsAnswer: message.role === 'assistant' && (!nextMessage || nextMessage.role === 'user'),
      })
    })
    return sections
  })

  const questionAnchors = computed<AiQuestionAnchor[]>(() => {
    const anchors: AiQuestionAnchor[] = []
    let pendingAnchor: AiQuestionAnchor | null = null
    activeMessages.value.forEach((message) => {
      if (message.role === 'user') {
        pendingAnchor = createQuestionAnchor(message, anchors.length)
        anchors.push(pendingAnchor)
        return
      }
      if (message.role === 'assistant' && pendingAnchor) {
        pendingAnchor.answered = true
        pendingAnchor.responseTime = message.responseTime || pendingAnchor.responseTime
        pendingAnchor.responseTimeText = formatResponseTime(pendingAnchor.responseTime)
        pendingAnchor = null
      }
    })
    return anchors
  })

  const visibleQuestionAnchors = computed(() => {
    const keyword = anchorSearch.value.toLowerCase()
    return questionAnchors.value.filter((anchor) => {
      if (anchorFilterMode.value === 'pending' && anchor.answered) return false
      if (!keyword) return true
      return `${anchor.label} ${anchor.question}`.toLowerCase().includes(keyword)
    })
  })

  const timelineItems = computed(() => questionAnchors.value.map((anchor) => ({
    id: `timeline-${anchor.id}`,
    anchorId: anchor.id,
    label: anchor.label,
    question: anchor.question,
  })))

  const floatingTrackHeight = computed(() => {
    if (typeof window === 'undefined') return 260
    return Math.max(180, Math.min(window.innerHeight - 360, 360))
  })

  const floatingAnchorDots = computed(() => {
    const anchors = questionAnchors.value
    const maxTop = Math.max(0, floatingTrackHeight.value - 16)
    let lastTop = -18
    const dots = anchors.map((anchor, index) => {
      let top = Math.round(maxTop * (anchors.length <= 1 ? 0 : index / (anchors.length - 1)))
      if (top - lastTop < 18) top = Math.min(maxTop, lastTop + 18)
      lastTop = top
      return { ...anchor, top }
    })
    for (let index = dots.length - 2; index >= 0; index -= 1) {
      if (dots[index + 1].top - dots[index].top < 18) {
        dots[index].top = Math.max(0, dots[index + 1].top - 18)
      }
    }
    return dots
  })

  function resetAnchorState() {
    activeAnchorId.value = ''
    previewAnchorId.value = ''
    targetAnchorId.value = ''
  }

  function scrollToAnchor(anchorId: string) {
    const target = document.getElementById(anchorId)
    if (!target) return
    activeAnchorId.value = anchorId
    targetAnchorId.value = anchorId
    target.scrollIntoView({ behavior: 'smooth', block: 'center' })
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${anchorId}`)
    if (targetAnchorTimer) window.clearTimeout(targetAnchorTimer)
    targetAnchorTimer = window.setTimeout(() => {
      if (targetAnchorId.value === anchorId) targetAnchorId.value = ''
    }, 1600)
  }

  function toggleAnchorText(anchorId: string) {
    const next = new Set(expandedAnchorIds.value)
    if (next.has(anchorId)) next.delete(anchorId)
    else next.add(anchorId)
    expandedAnchorIds.value = next
  }

  async function copyAnchorLink(anchor: AiQuestionAnchor) {
    const url = `${window.location.origin}${anchor.shareUrl}`
    try {
      await navigator.clipboard?.writeText(url)
    } catch {
      window.prompt('复制问答锚点链接', url)
    }
  }

  function updateActiveAnchorFromScroll() {
    const anchors = questionAnchors.value
    if (!anchors.length) {
      activeAnchorId.value = ''
      return
    }
    const activationLine = Math.max(120, Math.round(window.innerHeight * 0.24))
    let nextActive = anchors[0].id
    for (const anchor of anchors) {
      const element = document.getElementById(anchor.id)
      if (!element) continue
      const rect = element.getBoundingClientRect()
      if (rect.top <= activationLine) nextActive = anchor.id
      else break
    }
    activeAnchorId.value = nextActive
  }

  function clearAnchorTimers() {
    if (targetAnchorTimer) window.clearTimeout(targetAnchorTimer)
  }

  return {
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
  }
}

function questionAnchorId(index: number) {
  return `ai-question-anchor-${index}`
}

function formatResponseTime(ms: number) {
  if (!ms || ms <= 0) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(ms >= 10000 ? 0 : 1)}s`
}

function createQuestionAnchor(message: AiSessionMessage, index: number): AiQuestionAnchor {
  const anchorIndex = index + 1
  return {
    id: questionAnchorId(anchorIndex),
    label: `Q${anchorIndex}`,
    question: message.text || '未命名提问',
    answered: false,
    responseTime: message.responseTime || 0,
    responseTimeText: formatResponseTime(message.responseTime || 0),
    shareUrl: `${window.location.pathname}#${questionAnchorId(anchorIndex)}`,
    messageId: message.id,
  }
}
