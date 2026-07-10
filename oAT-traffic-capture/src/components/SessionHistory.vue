<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { TrafficRecord } from '../types/traffic'

interface SessionItem {
  id: string
  case_name: string
  start_time: number
  end_time: number | null
  record_count: number
}

const emit = defineEmits<{ load: [records: TrafficRecord[]] }>()

const sessions = ref<SessionItem[]>([])
const loadingId = ref('')
const currentPage = ref(1)
const pageSize = 5

const totalPages = computed(() => Math.max(1, Math.ceil(sessions.value.length / pageSize)))
const pageStart = computed(() => (currentPage.value - 1) * pageSize)
const pagedSessions = computed(() => sessions.value.slice(pageStart.value, pageStart.value + pageSize))
const pageRangeText = computed(() => {
  if (sessions.value.length === 0) return '0-0'
  const start = pageStart.value + 1
  const end = Math.min(pageStart.value + pageSize, sessions.value.length)
  return `${start}-${end}`
})

onMounted(loadSessions)

async function loadSessions() {
  sessions.value = (await window.electronAPI?.listSessions()) ?? []
  normalizePage()
}

async function loadSession(id: string) {
  loadingId.value = id
  try {
    const records = (await window.electronAPI?.loadSession(id)) ?? []
    emit('load', records)
  } finally {
    loadingId.value = ''
  }
}

async function removeSession(id: string) {
  if (!confirm('删除该历史会话？')) return

  const result = await window.electronAPI?.deleteSession(id)
  if (result?.success) {
    sessions.value = sessions.value.filter((session) => session.id !== id)
    normalizePage()
  }
}

function normalizePage() {
  if (currentPage.value > totalPages.value) {
    currentPage.value = totalPages.value
  }
  if (currentPage.value < 1) {
    currentPage.value = 1
  }
}

function goPage(page: number) {
  currentPage.value = Math.min(Math.max(page, 1), totalPages.value)
}

function formatDate(timestamp: number) {
  return new Date(timestamp).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="session-history">
    <div class="section-title">
      <span>历史会话</span>
      <button class="refresh-btn" @click="loadSessions">刷新</button>
    </div>
    <div v-if="sessions.length === 0" class="empty">暂无历史记录</div>
    <div v-for="session in pagedSessions" :key="session.id" class="session-item">
      <div class="session-info">
        <span class="case-name">{{ session.case_name }}</span>
        <span class="meta">{{ formatDate(session.start_time) }} · {{ session.record_count }} 条</span>
      </div>
      <div class="actions">
        <button @click="loadSession(session.id)" :disabled="loadingId === session.id">
          {{ loadingId === session.id ? '加载中' : '加载' }}
        </button>
        <button class="danger" @click="removeSession(session.id)">删除</button>
      </div>
    </div>
    <div v-if="sessions.length > pageSize" class="pagination">
      <span class="page-summary">共 {{ sessions.length }} 条，{{ pageRangeText }}</span>
      <div class="page-actions">
        <button :disabled="currentPage === 1" @click="goPage(currentPage - 1)">上一页</button>
        <span class="page-current">{{ currentPage }} / {{ totalPages }}</span>
        <button :disabled="currentPage === totalPages" @click="goPage(currentPage + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.session-history {
  padding: 0 18px 10px;
  background: #f0f2f5;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: #262626;
  margin-bottom: 8px;
}

.refresh-btn {
  padding: 3px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: white;
  color: #595959;
  font-size: 12px;
  cursor: pointer;
}

.empty {
  padding: 10px 12px;
  background: white;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  font-size: 13px;
  color: #8c8c8c;
}

.session-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 9px;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  margin-bottom: 6px;
  background: white;
  gap: 12px;
}

.session-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.case-name {
  font-size: 12px;
  font-weight: 500;
  color: #262626;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  font-size: 12px;
  color: #8c8c8c;
}

.actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.actions button {
  padding: 3px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: white;
  color: #595959;
  font-size: 12px;
  cursor: pointer;
}

.actions button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.actions button:hover:not(:disabled),
.refresh-btn:hover {
  border-color: #667eea;
  color: #667eea;
}

.actions button.danger:hover {
  border-color: #ff4d4f;
  color: #ff4d4f;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-actions button {
  padding: 3px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: white;
  color: #595959;
  font-size: 12px;
  cursor: pointer;
}

.page-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-actions button:hover:not(:disabled) {
  border-color: #667eea;
  color: #667eea;
}

.page-current {
  min-width: 42px;
  text-align: center;
  color: #595959;
}
</style>
