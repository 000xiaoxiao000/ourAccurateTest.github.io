<template>
  <section class="language-config-panel">
    <div class="language-config-head">
      <div>
        <h2>源码解析配置</h2>
        <p>{{ activeProfile.description }}</p>
      </div>
      <span class="collector-pill">{{ activeProfile.label }}</span>
    </div>

    <div class="language-config-grid">
      <label v-for="field in activeFields" :key="field.key" :class="['config-field', field.wide && 'wide']">
        <span>{{ field.label }}</span>
        <select v-if="field.options" :value="fieldValue(field.key)" class="select" @change="updateField(field.key, ($event.target as HTMLSelectElement).value)">
          <option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
        <input
          v-else-if="field.type === 'number'"
          :value="fieldValue(field.key)"
          class="text-input"
          type="number"
          :min="field.min"
          :step="field.step || 1"
          @input="updateField(field.key, ($event.target as HTMLInputElement).value)"
        />
        <input
          v-else
          :value="fieldValue(field.key)"
          class="text-input"
          type="text"
          :placeholder="field.placeholder"
          @input="updateField(field.key, ($event.target as HTMLInputElement).value)"
        />
      </label>
    </div>

    <details class="raw-config">
      <summary>JSON 原文</summary>
      <textarea :value="modelValue" class="text-area" rows="4" spellcheck="false" @input="updateRaw(($event.target as HTMLTextAreaElement).value)"></textarea>
      <p v-if="parseError" class="config-error">{{ parseError }}</p>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { resolveLanguageProfile, type AppLanguage } from '@/features/app/languageProfiles'

const props = defineProps<{
  language: AppLanguage
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const parseError = ref('')
const lastValidConfig = ref<Record<string, unknown>>({})

const activeProfile = computed(() => resolveLanguageProfile(props.language))
const activeFields = computed(() => activeProfile.value.fields)
const parsedConfig = computed<Record<string, unknown>>(() => parseConfig(props.modelValue))

watch(
  () => props.language,
  () => {
    const next = { ...parsedConfig.value }
    activeFields.value.forEach((field) => {
      if (next[field.key] === undefined && field.options?.length) next[field.key] = field.options[0].value
    })
    emitConfig(next)
  },
)

function parseConfig(value: string): Record<string, unknown> {
  if (!value?.trim()) {
    parseError.value = ''
    return {}
  }
  try {
    const parsed = JSON.parse(value)
    parseError.value = ''
    const config = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
    lastValidConfig.value = config
    return config
  } catch {
    parseError.value = '语言配置 JSON 格式不正确，字段表单会保留最近可解析的数据。'
    return lastValidConfig.value
  }
}

function fieldValue(key: string) {
  const value = parsedConfig.value[key]
  return value === undefined || value === null ? '' : String(value)
}

function updateField(key: string, rawValue: string) {
  const field = activeFields.value.find((item) => item.key === key)
  const next = { ...parsedConfig.value }
  if (rawValue === '') {
    delete next[key]
  } else if (field?.type === 'number') {
    const numeric = Number(rawValue)
    if (Number.isFinite(numeric)) next[key] = numeric
  } else {
    next[key] = rawValue
  }
  emitConfig(next)
}

function updateRaw(value: string) {
  emit('update:modelValue', value)
}

function emitConfig(value: Record<string, unknown>) {
  emit('update:modelValue', JSON.stringify(value, null, 2))
}
</script>

<style scoped>
.language-config-panel {
  display: grid;
  gap: 14px;
  margin-top: 16px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.72);
}

.language-config-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.language-config-head h2,
.language-config-head p {
  margin: 0;
}

.language-config-head h2 {
  color: #0f172a;
  font-size: 16px;
}

.language-config-head p {
  margin-top: 4px;
  color: #64748b;
  font-size: 13px;
}

.collector-pill {
  flex: 0 0 auto;
  border-radius: 999px;
  padding: 5px 10px;
  background: rgba(15, 118, 110, 0.12);
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
}

.language-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.config-field {
  display: grid;
  gap: 8px;
}

.config-field.wide {
  grid-column: 1 / -1;
}

.config-field span {
  color: #475569;
  font-size: 13px;
  font-weight: 700;
}

.text-input,
.text-area,
.select {
  width: 100%;
  border: 1px solid rgba(15, 23, 42, 0.14);
  border-radius: 10px;
  min-height: 42px;
  padding: 10px 12px;
  background: #fff;
}

.raw-config {
  color: #475569;
  font-size: 13px;
}

.raw-config summary {
  cursor: pointer;
  font-weight: 800;
}

.raw-config .text-area {
  margin-top: 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
}

.config-error {
  margin: 8px 0 0;
  color: #b91c1c;
  font-weight: 700;
}

@media (max-width: 720px) {
  .language-config-head {
    display: grid;
  }

  .language-config-grid {
    grid-template-columns: 1fr;
  }
}
</style>
