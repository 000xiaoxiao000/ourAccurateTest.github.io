<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Labels</div>
        <h1>标签管理</h1>
      </div>
      <button class="action-button" type="button" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="status-card">正在加载标签数据...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <div class="label-grid">
        <section class="label-card">
          <h2>用例标签</h2>
          <div class="chips">
            <button
              v-for="label in payload.usecaseLabels"
              :key="`usecase-${label.name}`"
              class="chip"
              type="button"
              :style="({ '--chip-color': colorHex(label.color) } as any)"
              @click="remove('usecase', label.name)"
            >
              {{ label.name }}
            </button>
          </div>
          <div class="form-row">
            <input v-model="usecaseName" class="text-input" type="text" placeholder="新标签名称" />
            <select v-model="usecaseColor" class="select">
              <option v-for="item in colors" :key="item.name" :value="item.name">{{ item.name }}</option>
            </select>
            <button class="primary-button" type="button" @click="save('usecase', usecaseName, usecaseColor)">
              保存
            </button>
          </div>
        </section>

      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { useProjectStore } from '@/stores/project'

const route = useRoute()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const payload = computed(() => projectStore.labelsByProjectId[projectId.value])
const loading = ref(false)
const error = ref('')

const usecaseName = ref('')
const usecaseColor = ref('grey')

const colors = [
  { name: 'grey', hex: '#64748b' },
  { name: 'blue', hex: '#2563eb' },
  { name: 'green', hex: '#16a34a' },
  { name: 'orange', hex: '#ea580c' },
  { name: 'red', hex: '#dc2626' },
  { name: 'purple', hex: '#7c3aed' },
  { name: 'teal', hex: '#0f766e' },
]

function colorHex(name: string) {
  return colors.find((item) => item.name === name)?.hex || '#64748b'
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectLabels(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载标签失败'
  } finally {
    loading.value = false
  }
}

async function save(type: string, name: string, color: string) {
  if (!name.trim()) {
    error.value = '标签名称不能为空'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.saveProjectLabel(projectId.value, type, name.trim(), color)
    usecaseName.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存标签失败'
  } finally {
    loading.value = false
  }
}

async function remove(type: string, name: string) {
  loading.value = true
  error.value = ''
  try {
    await projectStore.removeLabel(projectId.value, type, name)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除标签失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}


.action-button,
.primary-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  color: #fff;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
}

.primary-button {
  background: #0f766e;
}

.status-card,
.label-card {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.label-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 16px 0;
}

.chip {
  --chip-color: #64748b;

  border: none;
  border-radius: 999px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--chip-color) 14%, white);
  color: var(--chip-color);
  cursor: pointer;
}

.form-row {
  display: flex;
  gap: 12px;
}

.text-input,
.select {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}

.text-input {
  flex: 1 1 auto;
}

@media (max-width: 960px) {
  .label-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .page-header,
  .form-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
