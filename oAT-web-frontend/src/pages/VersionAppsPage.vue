<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Legacy Snapshot Tool</div>
        <h1>源码快照工具（旧）</h1>
        <p class="subtext">辅助查看源码工程的历史提交和比对报告；主流程请使用 AI 验证中的源码资产和分析基线。</p>
      </div>
    </div>

    <div v-if="loading" class="status-card">正在加载源码工程列表...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <div v-else class="card-grid">
      <article v-for="app in apps" :key="app.id" class="card">
        <div class="card-top">
          <strong>{{ app.name }}</strong>
          <span class="tag">{{ app.currentVersion || '未设当前版本' }}</span>
        </div>
        <p class="subtext">{{ app.describe || '暂无源码工程说明' }}</p>
        <div class="card-body-row">
          <div class="meta-list">
            <span>{{ app.repoConfigured ? '已配置仓库' : '未配置仓库' }}</span>
          </div>
          <div class="action-row">
            <RouterLink class="table-link" :to="{ name: 'version-list', params: { projectId, appId: app.id } }">快照列表</RouterLink>
            <RouterLink class="table-link" :to="{ name: 'version-compare', params: { projectId, appId: app.id } }">旧比对报告</RouterLink>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { fetchProjectApps } from '@/api/bootstrap'
import type { AppSummary } from '@/api/types'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = ref<AppSummary[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    apps.value = await fetchProjectApps(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载源码工程列表失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header,
.card-top,
.meta-list,
.card-body-row,
.action-row {
  display: flex;
  gap: 12px;
}

.page-header,
.card-top,
.card-body-row {
  justify-content: space-between;
  align-items: center;
}

.meta-list,
.action-row {
  align-items: center;
  flex-wrap: wrap;
}

.page-header {
  margin-bottom: 12px;
}


.subtext,
.meta-list {
  color: #64748b;
}

.status-card,
.card {
  padding: 14px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 520px));
  justify-content: start;
  align-items: stretch;
  gap: 10px;
}

.card-body-row {
  margin-top: 10px;
}

.action-row {
  justify-content: flex-end;
}

.tag {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.08);
  color: #0f766e;
  font-size: 12px;
  font-weight: 700;
}

.table-link {
  color: #0f766e;
  font-weight: 700;
}
</style>
