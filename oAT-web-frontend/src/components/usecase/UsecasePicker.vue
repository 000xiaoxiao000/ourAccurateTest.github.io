<template>
  <section v-if="open" class="picker-card">
    <div class="picker-head">
      <div class="picker-head-text">
        <h3>{{ title }}</h3>
        <p v-if="description">{{ description }}</p>
      </div>
      <button class="close-btn" type="button" aria-label="关闭" @click="close">✕</button>
    </div>

    <div class="picker-tools">
      <input v-model="keyword" class="search-input" type="search" placeholder="搜索用例标题、ID 或内容..." aria-label="搜索用例" />
      <div class="tool-actions">
        <button class="tool-btn" type="button" @click="selectAll">全选</button>
        <button class="tool-btn" type="button" @click="clearSelection">清空</button>
        <span class="selection-badge">已选 <strong>{{ draftIds.length }}</strong> 个</span>
      </div>
    </div>

    <div v-if="!filteredUsecases.length" class="empty-state">
      <span class="empty-icon" aria-hidden="true">🔍</span>
      <p>没有匹配的用例</p>
    </div>
    <div v-else class="usecase-grid">
      <label v-for="usecase in filteredUsecases" :key="usecase.id" class="usecase-option">
        <input v-model="draftIds" type="checkbox" :value="usecase.id" />
        <div class="option-content">
          <strong class="option-title">{{ usecase.title || usecase.id }}</strong>
          <small class="option-meta">{{ usecase.updateTimeText || usecase.id }}</small>
        </div>
      </label>
    </div>

    <div class="picker-footer">
      <button class="primary-btn" type="button" :disabled="busy" @click="submit">
        {{ busy ? '保存中...' : '保存关联' }}
      </button>
      <button class="secondary-btn" type="button" :disabled="busy" @click="close">取消</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { UsecaseSummary } from '@/api/types'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    usecases: UsecaseSummary[]
    selectedIds?: string[]
    busy?: boolean
  }>(),
  {
    description: '从项目用例中选择需要关联的条目。',
    selectedIds: () => [],
    busy: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [ids: string[]]
}>()

const keyword = ref('')
const draftIds = ref<string[]>([])

const filteredUsecases = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return props.usecases
  }
  return props.usecases.filter((usecase) => {
    return [usecase.id, usecase.title, usecase.content]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(kw))
  })
})

watch(
  () => [props.open, props.selectedIds] as const,
  () => {
    if (props.open) {
      keyword.value = ''
      draftIds.value = [...props.selectedIds]
    }
  },
  { immediate: true },
)

function selectAll() {
  draftIds.value = Array.from(new Set([...draftIds.value, ...filteredUsecases.value.map((usecase) => usecase.id)]))
}

function clearSelection() {
  draftIds.value = []
}

function close() {
  emit('update:open', false)
}

function submit() {
  emit('submit', [...draftIds.value])
}
</script>

<style scoped>
.picker-card {
  display: grid;
  gap: 14px;
  margin: 14px 0;
  padding: 16px;
  border: 1px solid rgba(var(--oat-primary-rgb), 0.2);
  border-radius: var(--oat-radius-lg);
  background: linear-gradient(135deg, rgba(240, 253, 250, 0.5) 0%, rgba(255, 255, 255, 0.9) 100%);
  box-shadow: 0 4px 16px rgba(var(--oat-primary-rgb), 0.06);
}

.picker-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.picker-head-text h3 {
  margin: 0 0 4px;
  color: var(--oat-text);
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.01em;
}

.picker-head-text p {
  margin: 0;
  color: var(--oat-text-muted);
  font-size: 13px;
  line-height: 1.5;
}

.close-btn {
  flex-shrink: 0;
  display: inline-grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--oat-border);
  border-radius: 999px;
  background: var(--oat-surface);
  color: var(--oat-text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.close-btn:hover {
  border-color: rgba(220, 38, 38, 0.3);
  background: rgba(220, 38, 38, 0.05);
  color: var(--oat-danger);
}

.picker-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.search-input {
  flex: 1;
  min-width: min(300px, 100%);
  min-height: 38px;
  border: 1px solid var(--oat-border);
  border-radius: var(--oat-radius-md);
  padding: 8px 12px;
  background: var(--oat-surface);
  color: var(--oat-text);
  font-size: 14px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.search-input:focus {
  border-color: rgba(var(--oat-primary-rgb), 0.5);
  box-shadow: 0 0 0 3px rgba(var(--oat-primary-rgb), 0.1);
  outline: none;
}

.tool-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tool-btn {
  min-height: 30px;
  border: 1px solid var(--oat-border);
  border-radius: 999px;
  padding: 5px 12px;
  background: var(--oat-surface);
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tool-btn:hover {
  border-color: rgba(var(--oat-primary-rgb), 0.3);
  background: rgba(var(--oat-primary-rgb), 0.05);
  color: var(--oat-primary-dark);
}

.selection-badge {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(var(--oat-primary-rgb), 0.1);
  color: var(--oat-primary-dark);
  font-size: 12px;
  font-weight: 600;
}

.selection-badge strong {
  font-weight: 800;
  margin: 0 2px;
}

.empty-state {
  display: grid;
  place-items: center;
  gap: 8px;
  padding: 32px 20px;
  border-radius: var(--oat-radius-md);
  background: var(--oat-surface-soft);
  text-align: center;
}

.empty-icon {
  font-size: 24px;
  opacity: 0.4;
}

.empty-state p {
  margin: 0;
  color: var(--oat-text-muted);
  font-size: 14px;
}

.usecase-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
  max-height: 360px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(var(--oat-primary-rgb), 0.3) transparent;
}

.usecase-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--oat-border);
  border-radius: var(--oat-radius-sm);
  background: var(--oat-surface);
  cursor: pointer;
  transition: all 0.15s ease;
}

.usecase-option:hover {
  border-color: rgba(var(--oat-primary-rgb), 0.25);
  background: rgba(var(--oat-primary-rgb), 0.03);
}

.usecase-option input[type='checkbox'] {
  margin-top: 2px;
  flex-shrink: 0;
}

.option-content {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.option-title {
  display: block;
  color: var(--oat-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.option-meta {
  display: block;
  color: var(--oat-text-muted);
  font-size: 11px;
}

.picker-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px solid var(--oat-border);
}

.primary-btn {
  min-height: 36px;
  border: none;
  border-radius: 999px;
  padding: 8px 18px;
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-primary-hover));
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 4px 12px rgba(var(--oat-primary-rgb), 0.25);
}

.primary-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(var(--oat-primary-rgb), 0.3);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.secondary-btn {
  min-height: 36px;
  border: 1px solid var(--oat-border);
  border-radius: 999px;
  padding: 8px 16px;
  background: var(--oat-surface);
  color: var(--oat-text-secondary);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
}

.secondary-btn:hover:not(:disabled) {
  border-color: rgba(var(--oat-primary-rgb), 0.3);
  background: rgba(var(--oat-primary-rgb), 0.05);
  color: var(--oat-primary-dark);
}

.secondary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 720px) {
  .picker-head,
  .picker-tools,
  .picker-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .usecase-grid {
    grid-template-columns: 1fr;
  }
}
</style>
