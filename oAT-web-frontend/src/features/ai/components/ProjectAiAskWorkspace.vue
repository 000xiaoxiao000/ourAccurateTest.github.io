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

<style scoped>
.ask-workspace-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 20px;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 28px;
  background:
    radial-gradient(circle at 12% 0%, color-mix(in srgb, var(--ai-accent, #0f766e) 8%, transparent), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, .98), rgba(248, 250, 252, .94));
  box-shadow: 0 18px 46px rgba(15, 23, 42, .07);
}

.card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title h2 {
  margin: 0;
  color: #172033;
  font-size: 18px;
  font-weight: 850;
}

.message-history {
  display: grid;
  gap: 12px;
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.message-history::-webkit-scrollbar {
  width: 8px;
}

.message-history::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 24%, #cbd5e1);
  background-clip: content-box;
}

.message-card {
  position: relative;
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 14px 86px 14px 48px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(15, 23, 42, .05);
}

.message-card.user {
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 6%, white);
}

.message-card.assistant {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 18%, transparent);
  background: linear-gradient(180deg, #fff, color-mix(in srgb, var(--ai-accent, #0f766e) 4%, white));
}

.message-card::before {
  position: absolute;
  top: 14px;
  left: 14px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  background: var(--ai-accent, #0f766e);
  color: #fff;
  content: 'AI';
  font-size: 10px;
  font-weight: 900;
}

.message-card.user::before {
  background: #0f172a;
  content: '我';
}

.message-copy-button {
  position: absolute;
  top: 12px;
  right: 12px;
  min-height: 28px;
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 18%, transparent);
  border-radius: 999px;
  padding: 0 10px;
  background: rgba(255, 255, 255, .88);
  color: var(--ai-accent, #0f766e);
  font: inherit;
  font-size: 12px;
  font-weight: 850;
  cursor: pointer;
}

.message-role {
  color: var(--ai-accent, #0f766e);
  font-size: 12px;
  font-weight: 850;
}

.message-text {
  color: #334155;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.markdown-message {
  white-space: normal;
}

.message-actions {
  display: flex;
  gap: 4px;
  padding-top: 8px;
  border-top: 1px solid rgba(15, 23, 42, .06);
}

.message-action-btn {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 10px;
  background: rgba(15, 23, 42, .04);
  cursor: pointer;
}

.message-action-btn.active,
.message-action-btn:hover {
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 12%, white);
}

.ask-form {
  display: grid;
  gap: 14px;
  margin-top: auto;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 14%, transparent);
  border-radius: 24px;
  background: rgba(255, 255, 255, .96);
  box-shadow: 0 16px 36px rgba(15, 23, 42, .08);
}

.text-area {
  width: 100%;
  min-height: 150px;
  max-height: 320px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  padding: 16px;
  background: #f8fafc;
  color: #172033;
  font: inherit;
  font-size: 15px;
  line-height: 1.65;
  resize: vertical;
  outline: none;
  transition: border-color .16s ease, box-shadow .16s ease, background .16s ease;
}

.text-area:focus {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 38%, transparent);
  background: #fff;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--ai-accent, #0f766e) 10%, transparent);
}

.hidden-input {
  display: none;
}

.form-actions,
.ask-tools,
.ask-submit-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.form-actions {
  justify-content: space-between;
}

.ask-submit-actions {
  justify-content: flex-end;
  margin-left: auto;
}

.ghost-button,
.primary-button,
.danger-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 42px;
  border-radius: 999px;
  padding: 0 16px;
  font: inherit;
  font-weight: 850;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease, border-color .16s ease;
}

.ghost-button {
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 20%, transparent);
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 6%, white);
  color: var(--ai-accent, #0f766e);
}

.ghost-button.active {
  border-color: color-mix(in srgb, var(--ai-accent, #0f766e) 38%, transparent);
  background: color-mix(in srgb, var(--ai-accent, #0f766e) 14%, white);
}

.primary-button {
  border: 0;
  background: linear-gradient(135deg, var(--ai-accent, #0f766e), color-mix(in srgb, var(--ai-accent, #0f766e) 76%, #0f172a));
  color: #fff;
  box-shadow: 0 14px 28px color-mix(in srgb, var(--ai-accent, #0f766e) 20%, transparent);
}

.danger-button {
  border: 0;
  background: linear-gradient(135deg, #ef4444, #dc2626);
  color: #fff;
}

.ghost-button:hover,
.primary-button:hover,
.danger-button:hover {
  transform: translateY(-1px);
}

.ghost-button:disabled,
.primary-button:disabled,
.danger-button:disabled {
  cursor: not-allowed;
  opacity: .62;
  transform: none;
}

.floating-anchors {
  position: absolute;
  top: 28px;
  right: 14px;
  display: none;
}

@media (max-width: 900px) {
  .ask-workspace-panel {
    min-height: 640px;
  }

  .message-card {
    padding-right: 14px;
  }

  .message-copy-button {
    position: static;
    justify-self: end;
  }

  .form-actions,
  .ask-submit-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .ask-submit-actions {
    width: 100%;
    margin-left: 0;
  }

  .ghost-button,
  .primary-button,
  .danger-button {
    width: 100%;
  }
}
</style>
