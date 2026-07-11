<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Usecase Detail</div>
        <h1>{{ payload?.usecase.title || '用例详情' }}</h1>
      </div>
      <div class="header-actions">
        <RouterLink class="secondary-link" :to="backLink">返回列表</RouterLink>
        <RouterLink class="primary-link" :to="`/p/${projectId}/usecases/${usecaseId}/edit`">编辑</RouterLink>
      </div>
    </div>

    <div v-if="loading" class="status-card">正在加载用例详情...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <div class="hero-card">
        <div class="hero-main">
          <div class="tag-row" v-if="payload.labels?.length">
            <span
              v-for="label in payload.labels"
              :key="label.name"
              class="label-chip"
              :style="({ '--label-color': label.color || '#0f766e' } as any)"
            >
              {{ label.name }}
            </span>
          </div>
          <div class="meta-grid">
            <div class="meta-item">
              <span>最后更新</span>
              <strong>{{ payload.usecase.updateTimeText || '-' }}</strong>
            </div>
            <div class="meta-item">
              <span>维护者</span>
              <strong>{{ payload.lastUpdateAuthor?.nickname || payload.lastUpdateAuthor?.name || '-' }}</strong>
            </div>
          </div>
        </div>
        <img v-if="payload.usecase.headImage" class="cover-image" :src="payload.usecase.headImage" alt="cover" />
      </div>

      <div class="detail-grid">
        <section class="content-card">
          <div class="card-title">
            <h2>用例内容</h2>
          </div>
          <div v-if="payload.contentHtml" class="markdown-body" v-html="payload.contentHtml"></div>
          <div v-else class="empty-card">暂无内容</div>
        </section>

        <aside class="side-stack">
          <section class="side-card">
            <div class="card-title">
              <h2>缺陷 / PRD</h2>
            </div>
            <div class="group-block">
              <h3>缺陷</h3>
              <div v-if="!payload.defects?.length" class="empty-card compact">暂无缺陷关联</div>
              <div v-else class="item-list">
                <a
                  v-for="item in payload.defects"
                  :key="item.id"
                  class="item-card link-card"
                  :href="item.url || '#'"
                  :target="item.external ? '_blank' : '_self'"
                >
                  <strong>{{ item.name }}</strong>
                  <span>{{ item.url || '纯文本' }}</span>
                </a>
              </div>
            </div>
            <div class="group-block">
              <h3>PRD</h3>
              <div v-if="!payload.prdRequirements?.length" class="empty-card compact">暂无 PRD 关联</div>
              <div v-else class="item-list">
                <a
                  v-for="item in payload.prdRequirements"
                  :key="item.id"
                  class="item-card link-card"
                  :href="item.url || '#'"
                  :target="item.external ? '_blank' : '_self'"
                >
                  <strong>{{ item.name }}</strong>
                  <span>{{ item.url || '纯文本' }}</span>
                </a>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { useProjectStore } from '@/stores/project'

const route = useRoute()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const usecaseId = computed(() => String(route.params.usecaseId || ''))
const storeKey = computed(() => `${projectId.value}:${usecaseId.value}`)
const payload = computed(() => projectStore.usecaseDetailByKey[storeKey.value])
const backLink = computed(() => `/p/${projectId.value}/usecases`)
const loading = ref(false)
const error = ref('')

async function load() {
  if (!projectId.value || !usecaseId.value) {
    error.value = '缺少 projectId 或 usecaseId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadUsecaseDetail(projectId.value, usecaseId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载用例详情失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-header,
.header-actions,
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.page-header {
  margin-bottom: 20px;
}


.primary-link,
.secondary-link {
  border-radius: 999px;
  padding: 10px 14px;
  font-weight: 700;
}

.primary-link {
  background: #0f766e;
  color: #fff;
}

.secondary-link {
  color: #0f766e;
}

.status-card,
.hero-card,
.content-card,
.side-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.hero-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 18px;
  margin-bottom: 18px;
}

.hero-main {
  display: grid;
  gap: 16px;
}

.cover-image {
  width: 100%;
  height: 180px;
  object-fit: cover;
  border-radius: 18px;
}

.tag-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.label-chip {
  --label-color: #0f766e;

  padding: 6px 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--label-color) 14%, white);
  color: var(--label-color);
  font-size: 12px;
  font-weight: 700;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
}

.meta-item {
  padding: 14px;
  border-radius: 16px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.meta-item span,
.item-card span {
  color: #64748b;
  font-size: 13px;
}

.meta-item strong {
  display: block;
  margin-top: 6px;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(300px, 0.9fr);
  gap: 18px;
}

.side-stack,
.item-list {
  display: grid;
  gap: 12px;
}

.group-block + .group-block {
  margin-top: 16px;
}

.group-block h3 {
  margin: 0 0 10px;
  font-size: 14px;
}

.item-card {
  display: grid;
  gap: 4px;
  padding: 14px;
  border-radius: 16px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.link-card {
  word-break: break-all;
}

.markdown-body {
  line-height: 1.8;
  color: #334155;
}

.markdown-body :deep(pre) {
  overflow-x: auto;
  padding: 14px;
  border-radius: 14px;
  background: #0f172a;
  color: #e2e8f0;
}

.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 14px;
}

.empty-card {
  padding: 22px;
  border-radius: 16px;
  background: #f8fafc;
  color: #64748b;
  text-align: center;
}

.empty-card.compact {
  padding: 14px;
}

@media (max-width: 960px) {
  .hero-card,
  .detail-grid,
  .meta-grid {
    grid-template-columns: 1fr;
  }

  .page-header,
  .header-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
