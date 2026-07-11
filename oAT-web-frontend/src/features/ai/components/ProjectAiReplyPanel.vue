<template>
  <section v-if="reply" class="panel">
    <div class="card-title">
      <h2>回答</h2>
      <span class="muted">{{ reply.topic || 'general' }}</span>
    </div>
    <div class="answer-block">{{ reply.answer || '暂无回答' }}</div>

    <div v-if="reply.suggestions?.length" class="subsection">
      <h3>追问建议</h3>
      <div class="chip-list">
        <button
          v-for="item in reply.suggestions"
          :key="item"
          class="ghost-button small"
          type="button"
          @click="$emit('use-question', item)"
        >
          {{ item }}
        </button>
      </div>
    </div>

    <div v-if="reply.quickLinks?.length" class="subsection">
      <h3>推荐链接</h3>
      <div class="link-list">
        <button v-for="link in reply.quickLinks" :key="link.title + link.url" class="link-card link-button" type="button" @click="$emit('open-link', link)">
          <strong>{{ link.title }}</strong>
          <span>{{ link.description }}</span>
        </button>
      </div>
    </div>

    <div v-if="reply.actions?.length" class="subsection">
      <h3>建议动作</h3>
      <div class="link-list">
        <article v-for="action in reply.actions" :key="action.title + action.type" class="link-card">
          <strong>{{ action.title }}</strong>
          <span>{{ action.description }}</span>
          <button class="inline-link action-inline-button" type="button" @click="$emit('execute-action', action)">执行</button>
        </article>
      </div>
    </div>

    <div v-if="reply.visualizationSuggestions?.length" class="subsection">
      <h3>可视化建议</h3>
      <div class="visual-list">
        <article v-for="item in reply.visualizationSuggestions" :key="String(item.title || item.type || JSON.stringify(item))" class="visual-card">
          <strong>{{ String(item.title || item.type || '数据图表') }}</strong>
          <code>{{ JSON.stringify(item) }}</code>
        </article>
      </div>
    </div>

    <div v-if="replyMetaEntries.length" class="subsection">
      <h3>生成信息</h3>
      <div class="meta-grid">
        <article v-for="item in replyMetaEntries" :key="item.label" class="meta-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { AIAction, AIInteractiveReply, AIQuickLink } from '@/api/types'

defineProps<{
  reply: AIInteractiveReply | undefined
  replyMetaEntries: Array<{ label: string; value: string }>
}>()

defineEmits<{
  'use-question': [question: string]
  'open-link': [link: AIQuickLink]
  'execute-action': [action: AIAction]
}>()
</script>
