<template>
  <main class="share-shell">
    <header class="share-header">
      <div class="brand-block">
        <MascotCanvas :size="58" color="#0f766e" seed="share-usecase" mood="thinking" />
        <div>
          <div class="eyebrow">Shared Usecase</div>
          <h1>{{ payload?.usecase.title || '共享用例' }}</h1>
        </div>
      </div>
      <nav class="share-nav">
        <RouterLink to="/login">登录</RouterLink>
        <RouterLink to="/register">注册</RouterLink>
      </nav>
    </header>

    <div v-if="loading" class="status-card">正在加载共享用例...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <section class="hero-card">
        <div>
          <div class="label-row" v-if="payload.labels?.length">
            <span
              v-for="label in payload.labels"
              :key="label.name"
              class="label-chip"
              :style="labelStyle(label.color)"
            >
              {{ label.name }}
            </span>
          </div>
          <p class="description">{{ payload.usecase.content ? '该用例已开放共享，以下为只读详情。' : '暂无内容说明。' }}</p>
        </div>
        <div class="meta-grid">
          <div class="meta-item">
            <span>维护者</span>
            <strong>{{ payload.lastUpdateAuthor?.nickname || payload.lastUpdateAuthor?.name || '-' }}</strong>
          </div>
          <div class="meta-item">
            <span>更新时间</span>
            <strong>{{ payload.usecase.updateTimeText || '-' }}</strong>
          </div>
        </div>
      </section>

      <div class="detail-grid">
        <section class="panel content-card">
          <div class="card-title">
            <h2>用例内容</h2>
          </div>
          <div v-if="payload.contentHtml" class="markdown-body" v-html="payload.contentHtml"></div>
          <div v-else class="empty-card">暂无内容</div>
        </section>

        <aside class="side-stack">
        </aside>
      </div>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { fetchShareUsecase } from '@/api/bootstrap'
import type { PublicUsecasePayload } from '@/api/types'
import MascotCanvas from '@/components/MascotCanvas.vue'

const route = useRoute()
const usecaseId = computed(() => String(route.params.usecaseId || ''))
const payload = ref<PublicUsecasePayload>()
const loading = ref(false)
const error = ref('')

function labelStyle(color?: string) {
  const labelColor = color || '#0f766e'
  return {
    color: labelColor,
    backgroundColor: labelColorBackground(labelColor),
    borderColor: labelColorBorder(labelColor),
  }
}

function labelColorBackground(color: string) {
  return labelColorWithAlpha(color, 0.14)
}

function labelColorBorder(color: string) {
  return labelColorWithAlpha(color, 0.20)
}

function labelColorWithAlpha(color: string, alpha: number) {
  const hex = color.trim().replace(/^#/, '')
  const normalized = hex.length === 3 ? hex.split('').map((char) => `${char}${char}`).join('') : hex
  if (!/^[0-9a-fA-F]{6}$/.test(normalized)) return 'rgba(15, 118, 110, .14)'
  const red = Number.parseInt(normalized.slice(0, 2), 16)
  const green = Number.parseInt(normalized.slice(2, 4), 16)
  const blue = Number.parseInt(normalized.slice(4, 6), 16)
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

async function load() {
  if (!usecaseId.value) {
    error.value = '缺少 usecaseId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    payload.value = await fetchShareUsecase(usecaseId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载共享用例失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.share-shell {
  min-height: 100vh;
  padding: 28px min(5vw, 56px) 48px;
  background:
    radial-gradient(circle at top right, rgba(20, 184, 166, 0.14), transparent 34%),
    linear-gradient(135deg, #f8fbff 0%, #f0fdfa 48%, #f8fafc 100%);
}

.share-header,
.brand-block,
.share-nav,
.card-title,
.label-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.share-header {
  justify-content: space-between;
  margin: 0 auto 24px;
  max-width: 1180px;
}

.brand-block h1 {
  margin: 2px 0 0;
}

.eyebrow {
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.share-nav a {
  border-radius: 999px;
  padding: 9px 14px;
  background: rgba(255, 255, 255, 0.8);
  color: #0f766e;
  font-weight: 800;
}

.status-card,
.hero-card,
.panel {
  padding: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.status-card,
.hero-card,
.detail-grid {
  max-width: 1180px;
  margin: 0 auto 18px;
}

.status-card.error {
  color: #b91c1c;
}

.hero-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 18px;
}

.description,
.item-card span {
  color: #64748b;
}

.label-row {
  flex-wrap: wrap;
}

.label-chip {
  border: 1px solid rgba(15, 118, 110, .16);
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.meta-grid,
.detail-grid {
  display: grid;
  gap: 16px;
}

.meta-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.detail-grid {
  grid-template-columns: minmax(0, 1fr) 340px;
  align-items: start;
}

.meta-item,
.item-card,
.empty-card {
  border-radius: 18px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.meta-item span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.side-stack,
.item-list {
  display: grid;
  gap: 12px;
}

.item-card {
  display: block;
  color: #0f172a;
}

.markdown-body :deep(pre),
.markdown-body :deep(code) {
  border-radius: 12px;
  background: #0f172a;
  color: #d1fae5;
}

@media (max-width: 860px) {
  .share-header,
  .hero-card,
  .detail-grid {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: flex-start;
  }

  .meta-grid {
    grid-template-columns: 1fr;
  }
}
</style>
