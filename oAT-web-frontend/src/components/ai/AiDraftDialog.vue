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

        <p class="ai-draft-meta">任务状态：{{ draft?.status || 'UNKNOWN' }}</p>

        <textarea v-model="draftText" class="ai-draft-textarea" spellcheck="false"></textarea>

        <footer class="ai-draft-actions">
          <button class="ai-draft-button secondary" type="button" @click="reject">拒绝</button>
          <button class="ai-draft-button primary" type="button" @click="confirm">确认落库</button>
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

const draftText = ref('')

watch(
  () => props.draft,
  (draft) => {
    draftText.value = draft ? JSON.stringify(draft.payload ?? {}, null, 2) : ''
  },
  { immediate: true },
)

const prettyTitle = computed(() => props.title || 'AI 草稿')

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
  z-index: 6000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .42);
}

.ai-draft-card {
  width: min(760px, 100%);
  max-height: min(86vh, 860px);
  display: grid;
  gap: 12px;
  padding: 20px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 28px 70px rgba(15, 23, 42, .22);
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
  color: #64748b;
  font-size: 13px;
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

.ai-draft-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.ai-draft-button {
  min-width: 100px;
  padding: 10px 14px;
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
