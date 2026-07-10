<template>
  <section class="panel ask-workspace-panel">
    <div v-if="questionAnchors.length" class="floating-anchors" aria-label="右侧问答锚点导航">
      <div class="floating-anchor-head">问答</div>
      <div class="floating-anchor-track" :style="{ height: `${floatingTrackHeight}px` }">
        <button
          v-for="dot in floatingAnchorDots"
          :key="dot.id"
          class="floating-anchor-dot"
          :class="{ active: activeAnchorId === dot.id, pending: !dot.answered, answered: dot.answered }"
          type="button"
          :style="{ top: `${dot.top}px` }"
          :aria-label="`${dot.label} ${dot.question}`"
          @mouseenter="$emit('update:previewAnchorId', dot.id)"
          @mouseleave="$emit('update:previewAnchorId', '')"
          @click="$emit('scroll-to-anchor', dot.id)"
        >
          <span class="floating-anchor-label">{{ dot.label }}</span>
          <span class="floating-anchor-tooltip">
            <strong>{{ dot.label }} · {{ dot.answered ? '已回复' : '待回复' }}</strong>
            <em>{{ dot.question }}</em>
            <small>{{ dot.responseTimeText || (dot.answered ? '已生成回答' : '等待回复中') }}</small>
          </span>
        </button>
      </div>
    </div>
    <div class="card-title">
      <h2>提问</h2>
    </div>
    <div v-if="activeMessages.length" class="message-history">
      <article
        v-for="section in messageSections"
        :id="section.startsQuestion ? section.anchorId : `ai-message-${section.id}`"
        :key="section.id"
        class="message-card"
        :class="[
          section.message.role,
          {
            'anchor-section': section.startsQuestion,
            'qa-group-start': section.startsQuestion,
            'qa-group-end': section.endsAnswer,
            'is-active': activeAnchorId === section.anchorId,
            'is-preview': previewAnchorId === section.anchorId,
            'is-target': targetAnchorId === section.anchorId,
          },
        ]"
        :data-anchor-id="section.anchorId"
      >
        <button class="message-copy-button" type="button" :title="section.message.role === 'assistant' ? '复制回复内容' : '复制提问内容'" @click.stop="$emit('copy-message', section.message.text, section.id)">
          {{ copiedMessageId === section.id ? '已复制' : '复制' }}
        </button>
        <div class="message-role">{{ section.message.role === 'user' ? '你' : 'AI' }}</div>
        <div v-if="section.message.role === 'assistant'" class="message-text markdown-message" v-html="formatAssistantMessage(section.message.text)"></div>
        <div v-else class="message-text">{{ section.message.text }}</div>

        <div v-if="section.message.role === 'assistant' && section.endsAnswer" class="message-actions">
          <button
            class="message-action-btn"
            type="button"
            :disabled="feedbackSubmitting"
            :class="{ active: getMessageFeedback(section.id) === 'helpful' }"
            :title="getMessageFeedback(section.id) === 'helpful' ? '已标记有帮助' : '有帮助'"
            @click.stop="$emit('submit-message-feedback', section.id, section.message.text, 'helpful', 5)"
          >
            <span class="action-icon">👍</span>
          </button>
          <button
            class="message-action-btn"
            type="button"
            :disabled="feedbackSubmitting"
            :class="{ active: getMessageFeedback(section.id) === 'not_helpful' }"
            :title="getMessageFeedback(section.id) === 'not_helpful' ? '已标记没帮助' : '没帮助'"
            @click.stop="$emit('submit-message-feedback', section.id, section.message.text, 'not_helpful', 1)"
          >
            <span class="action-icon">👎</span>
          </button>
          <button
            class="message-action-btn"
            type="button"
            :disabled="feedbackSubmitting"
            title="标记为不正确"
            @click.stop="$emit('submit-message-feedback', section.id, section.message.text, 'incorrect', 1, true)"
          >
            <span class="action-icon">⚠️</span>
          </button>
        </div>
      </article>
    </div>
    <form ref="askFormRef" class="ask-form" @submit.prevent="$emit('submit-ask')">
      <textarea
        ref="askInputRef"
        v-model="questionModel"
        class="text-area"
        rows="6"
        placeholder="例如：帮我总结当前项目的测试覆盖盲区，优先按风险排序。"
        @keydown.enter.exact="$emit('ask-enter', $event)"
      ></textarea>
      <input ref="imageInput" class="hidden-input" type="file" accept="image/*" @change="$emit('image-change', $event)" />
      <div class="form-actions">
        <div class="ask-tools">
          <button class="ghost-button" :class="{ active: Boolean(imageData) }" type="button" @click="$emit('select-image')">
            {{ imageData ? '已附图片' : '上传图片' }}
          </button>
          <button v-if="imageData" class="ghost-button" type="button" @click="$emit('clear-image')">移除图片</button>
          <button class="ghost-button" :class="{ active: recording }" type="button" @click="$emit('toggle-voice-input')">语音输入</button>
        </div>
        <div class="ask-submit-actions">
          <button v-if="asking" class="danger-button control-button" type="button" @click="$emit('stop-ask')">
            <span class="button-icon stop-icon"></span>
            停止生成
          </button>
          <button class="primary-button control-button send-button" type="submit" :class="{ loading: asking }" :disabled="asking">
            <span v-if="asking" class="button-spinner" aria-hidden="true"></span>
            <span v-else class="button-icon send-icon" aria-hidden="true"></span>
            {{ asking ? '生成中...' : '发送问题' }}
          </button>
          <button class="ghost-button control-button save-button" type="button" :disabled="asking" @click="$emit('save-session')">
            <span class="button-icon save-icon" aria-hidden="true"></span>
            保存会话状态
          </button>
        </div>
      </div>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { AIFeedbackPayload } from '@/api/types'
import type { AiMessageSection, AiQuestionAnchor, AiSessionMessage } from '@/features/ai/types'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  question: string
  asking: boolean
  imageData: string
  recording: boolean
  activeMessages: AiSessionMessage[]
  messageSections: AiMessageSection[]
  questionAnchors: AiQuestionAnchor[]
  floatingAnchorDots: Array<AiQuestionAnchor & { top: number }>
  floatingTrackHeight: number
  activeAnchorId: string
  previewAnchorId: string
  targetAnchorId: string
  copiedMessageId: string
  feedbackSubmitting: boolean
  messageFeedbacks: Record<string, string>
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'update:previewAnchorId': [value: string]
  'scroll-to-anchor': [anchorId: string]
  'copy-message': [text: string, messageId: string]
  'submit-message-feedback': [messageId: string, answerText: string, feedbackType: AIFeedbackPayload['feedbackType'], rating: number, requireComment?: boolean]
  'submit-ask': []
  'ask-enter': [event: KeyboardEvent]
  'image-change': [event: Event]
  'select-image': []
  'clear-image': []
  'toggle-voice-input': []
  'stop-ask': []
  'save-session': []
}>()

const askFormRef = ref<HTMLFormElement | null>(null)
const askInputRef = ref<HTMLTextAreaElement | null>(null)
const imageInput = ref<HTMLInputElement | null>(null)

const questionModel = computed({
  get: () => props.question,
  set: (value: string) => emit('update:question', value),
})

function formatAssistantMessage(text: string) {
  return renderMarkdown(text)
}

function getMessageFeedback(messageId: string): string {
  return props.messageFeedbacks[messageId] || ''
}

function focusAskInput() {
  askInputRef.value?.focus({ preventScroll: true })
}

function scrollAskFormIntoView() {
  askFormRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

function selectImageFile() {
  imageInput.value?.click()
}

defineExpose({
  focusAskInput,
  scrollAskFormIntoView,
  selectImageFile,
})
</script>
