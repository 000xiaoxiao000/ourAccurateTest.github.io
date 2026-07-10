<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Collectors</div>
        <h1>采集源健康度</h1>
      </div>
      <button class="action-button" type="button" @click="reload">刷新</button>
    </div>

    <div v-if="loading" class="status-card">正在加载采集源...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else>
      <div class="runtime-overview">
        <div class="summary-row">
          <div class="summary-card">
            <strong>{{ collectorPayload?.total || 0 }}</strong>
            <span>采集源数</span>
          </div>
          <div class="summary-card">
            <strong>{{ healthyCollectorCount }}</strong>
            <span>健康采集源</span>
          </div>
          <div class="summary-card">
            <strong>{{ silentCollectorCount }}</strong>
            <span>静默采集源</span>
          </div>
          <div class="summary-card">
            <strong>{{ projectApps.length }}</strong>
            <span>项目应用数</span>
          </div>
        </div>
        <div class="toolbar-card" aria-label="采集源筛选">
          <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索应用、语言、IP、PID、状态" aria-label="搜索采集源" />
          <span>{{ filteredCollectorSources.length }} 个匹配采集源</span>
        </div>
      </div>

      <section class="table-card collector-card">
        <div class="card-title">
          <div>
            <h2>采集源列表</h2>
            <p class="subtext">按最近上报时间展示在线、静默和离线状态。</p>
          </div>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th>应用</th>
              <th>语言</th>
              <th>采集类型</th>
              <th>健康度</th>
              <th>最近活跃</th>
              <th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="source in paginatedCollectorSources" :key="source.sourceId || `${source.appId}-${source.language}`">
              <td>{{ source.appName || source.appId || '-' }}</td>
              <td>{{ source.language || '-' }}</td>
              <td>{{ source.collectorType === 'RESIDENT' ? '常驻' : '批量' }}</td>
              <td><span :class="['health-badge', healthTone(source.health)]">{{ healthLabel(source.health) }}</span></td>
              <td>{{ formatLastSeen(source.lastSeenTime) }}</td>
              <td>{{ source.addressIp || source.sessionId || source.sourceId || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!filteredCollectorSources.length" class="empty-card">暂无采集源状态</div>
      </section>

      <AppPagination
        v-if="filteredCollectorSources.length > 0"
        v-model:page="currentPage"
        v-model:page-size="pageSize"
        :total="filteredCollectorSources.length"
        item-name="采集源"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppPagination from '@/components/AppPagination.vue'
import { useProjectStore } from '@/stores/project'
import type { AppSummary, CollectorSourceSummary } from '@/api/types'

const route = useRoute()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const collectorPayload = computed(() => projectStore.collectorSourcesByProjectId[projectId.value])
const projectContext = computed(() => projectStore.contextByProjectId[projectId.value])
const projectApps = computed<AppSummary[]>(() => projectContext.value?.apps || [])
const appId = computed(() => String(route.query.appId || ''))
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const refreshIntervalMs = 15000
let refreshTimer: number | undefined

const filteredCollectorSources = computed(() => {
  const sources = collectorPayload.value?.sources || []
  const term = keyword.value.toLowerCase()
  return sources.filter((source) => {
    const matchesApp = !appId.value || source.appId === appId.value || source.appName === appId.value
    const matchesKeyword = !term || [
      source.appName,
      source.appId,
      source.language,
      source.collectorType,
      source.health,
      source.addressIp,
      source.pid,
    ].some((value) => String(value || '').toLowerCase().includes(term))
    return matchesApp && matchesKeyword
  })
})

const healthyCollectorCount = computed(() =>
  (collectorPayload.value?.sources || []).filter((source) => source.health === 'ONLINE').length,
)

const silentCollectorCount = computed(() =>
  (collectorPayload.value?.sources || []).filter((source) => source.health === 'SILENT').length,
)

const paginatedCollectorSources = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredCollectorSources.value.slice(start, start + pageSize.value)
})

function healthLabel(health?: CollectorSourceSummary['health']) {
  if (health === 'ONLINE') return '在线'
  if (health === 'SILENT') return '静默'
  if (health === 'OFFLINE') return '离线'
  return '未知'
}

function healthTone(health?: CollectorSourceSummary['health']) {
  if (health === 'ONLINE') return 'tone-ok'
  if (health === 'SILENT') return 'tone-warn'
  if (health === 'OFFLINE') return 'tone-error'
  return 'tone-muted'
}

function formatLastSeen(value?: number) {
  if (!value) return '-'
  const diff = Math.max(0, Date.now() - value)
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

async function load(options: { silent?: boolean } = {}) {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  if (!options.silent) {
    loading.value = true
    error.value = ''
  }
  try {
    if (!projectContext.value) {
      await projectStore.loadProjectContext(projectId.value)
    }
    await projectStore.loadCollectorSources(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载采集源失败'
  } finally {
    if (!options.silent) {
      loading.value = false
    }
  }
}

function reload() {
  load()
}

watch([keyword, appId, pageSize], () => {
  currentPage.value = 1
})

watch(() => filteredCollectorSources.value.length, (total) => {
  const totalPages = Math.max(1, Math.ceil(total / pageSize.value))
  if (currentPage.value > totalPages) {
    currentPage.value = totalPages
  }
})

onMounted(() => {
  load()
  refreshTimer = window.setInterval(() => {
    if (!loading.value) {
      load({ silent: true })
    }
  }, refreshIntervalMs)
})

onUnmounted(() => {
  if (refreshTimer !== undefined) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
})
</script>

<style scoped src="@/features/admin/styles/online-apps-page.css"></style>
