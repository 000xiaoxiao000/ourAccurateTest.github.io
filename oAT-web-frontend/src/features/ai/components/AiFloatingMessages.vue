<template>
  <div v-show="!collapsed" class="message-list">
    <article v-for="item in messages" :key="item.id" class="message" :class="item.role">
      <div class="message-avatar">{{ item.role === 'user' ? '我' : 'AI' }}</div>
      <div class="message-body">
        <div class="message-name">{{ item.role === 'user' ? '你' : 'AI 助手' }}</div>
        <div class="message-card">
          <button
            class="copy-button"
            type="button"
            :class="{ copied: copiedMessageId === item.id }"
            :aria-label="item.role === 'assistant' ? '复制回复内容' : '复制提问内容'"
            @pointerdown.stop
            @click="$emit('copy', item.text, item.id)"
          >
            {{ copiedMessageId === item.id ? '已复制' : '复制' }}
          </button>
          <div v-if="item.role === 'assistant'" class="message-text markdown-message" v-html="renderMarkdown(item.text)"></div>
          <div v-else class="message-text">{{ item.text }}</div>

          <div v-if="item.role === 'assistant'" class="feedback-actions">
            <button
              class="feedback-btn"
              type="button"
              :disabled="feedbackSubmitting"
              :class="{ active: getMessageFeedback(item.id) === 'helpful' }"
              :title="getMessageFeedback(item.id) === 'helpful' ? '已标记有帮助' : '有帮助'"
              @click.stop="$emit('feedback', item.id, item.text, 'helpful', 5)"
            >
              <span class="feedback-icon">👍</span>
            </button>
            <button
              class="feedback-btn"
              type="button"
              :disabled="feedbackSubmitting"
              :class="{ active: getMessageFeedback(item.id) === 'not_helpful' }"
              :title="getMessageFeedback(item.id) === 'not_helpful' ? '已标记没帮助' : '没帮助'"
              @click.stop="$emit('feedback', item.id, item.text, 'not_helpful', 1)"
            >
              <span class="feedback-icon">👎</span>
            </button>
            <button
              class="feedback-btn"
              type="button"
              :disabled="feedbackSubmitting"
              title="标记为不正确"
              @click.stop="$emit('feedback', item.id, item.text, 'incorrect', 1, true)"
            >
              <span class="feedback-icon">⚠️</span>
            </button>
          </div>

          <div v-if="item.suggestions?.length" class="message-actions">
            <button v-for="suggestion in item.suggestions" :key="suggestion" class="message-action" type="button" @click="$emit('preset', suggestion)">{{ suggestion }}</button>
          </div>
          <div v-if="item.actions?.length" class="message-actions">
            <button v-for="action in item.actions" :key="action.title + action.type" class="message-action exec-action" type="button" @click="$emit('execute', action)">{{ action.title || '执行操作' }}</button>
          </div>
        </div>
      </div>
    </article>
    <article v-if="!messages.length" class="message assistant">
      <div class="message-avatar">AI</div>
      <div class="message-body">
        <div class="message-name">AI 助手</div>
        <div class="message-card">
          <div class="message-text markdown-message" v-html="renderMarkdown(emptyMessage)"></div>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import type { AIAction } from '@/api/types'
import { renderMarkdown } from '@/utils/markdown'
import type { AiFeedbackType, AiFloatingMessage } from '@/features/ai/types'

defineProps<{
  messages: AiFloatingMessage[]
  collapsed: boolean
  copiedMessageId: string
  feedbackSubmitting: boolean
  emptyMessage: string
  getMessageFeedback: (messageId: string) => string
}>()

defineEmits<{
  copy: [text: string, messageId: string]
  feedback: [messageId: string, answerText: string, feedbackType: AiFeedbackType, rating: number, requireComment?: boolean]
  preset: [text: string]
  execute: [action: AIAction]
}>()
</script>
