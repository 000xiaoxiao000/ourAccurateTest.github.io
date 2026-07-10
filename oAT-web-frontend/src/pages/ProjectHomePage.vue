<template>
  <section class="project-home">
    <div v-if="loading" class="status-card">正在加载项目上下文...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="context">
      <div class="hero" :style="{ '--hero-accent': context.ai.mascotPrimary }">
        <div>
          <div class="eyebrow">Project Context</div>
          <h1>{{ context.project.name }}</h1>
          <p>{{ context.project.describe || '暂无项目描述' }}</p>
        </div>
        <div class="hero-stats">
          <div class="hero-stat">
            <strong>{{ context.appCount }}</strong>
            <span>应用总数</span>
          </div>
          <div class="hero-stat">
            <strong>{{ context.onlineAppCount }}</strong>
            <span>在线应用</span>
          </div>
          <div class="hero-stat">
            <strong>{{ context.currentUserRole }}</strong>
            <span>当前角色</span>
          </div>
        </div>
      </div>

      <div class="panel-grid">
        <section class="panel">
          <div class="panel-head">
            <h2>应用上下文</h2>
            <span>{{ context.apps.length }} 个应用</span>
          </div>
          <div class="app-list">
            <article v-for="app in context.apps" :key="app.id" class="app-card">
              <div class="app-card-top">
                <RouterLink class="app-link" :to="`/p/${projectId}/apps/${app.id}/settings`">{{ app.name }}</RouterLink>
                <span class="tag offline">应用</span>
              </div>
              <div class="app-card-meta">
                <span>{{ app.currentVersion || '未设置版本' }}</span>
              </div>
              <div class="app-card-actions">
                <RouterLink :to="`/p/${projectId}/apps/${app.id}/api-endpoints`">接口扫描</RouterLink>
                <RouterLink :to="`/p/${projectId}/apps/${app.id}/repository`">仓库配置</RouterLink>
              </div>
            </article>
          </div>
        </section>

        <section class="panel ai-panel" :style="{ '--ai-accent': context.ai.mascotPrimary }">
          <div class="panel-head ai-panel-head">
            <div>
              <h2>AI 能力</h2>
              <p class="panel-desc">围绕当前项目上下文提供问答、排查和跳转建议</p>
            </div>
            <RouterLink class="ai-entry primary" :to="`/p/${projectId}/ai`">
              {{ context.ai.enabled ? '进入工作台' : '查看配置' }}
            </RouterLink>
          </div>

          <div class="ai-showcase">
            <div class="ai-mascot-card">
              <MascotCanvas :size="118" :color="context.ai.mascotPrimary" :seed="projectId" :interactive="true" />
              <div>
                <strong>{{ context.ai.enabled ? 'AI 助手已就绪' : 'AI 助手待启用' }}</strong>
                <span>{{ context.appCount }} 个应用上下文 · {{ context.onlineAppCount }} 个在线信号</span>
              </div>
            </div>
            <div class="ai-status-card">
              <span class="status-dot" :class="{ active: context.ai.enabled }"></span>
              <div>
                <strong>{{ context.ai.enabled ? '在线响应' : '暂不可用' }}</strong>
                <span>最长思考 {{ context.ai.timeout }} 秒，支持页面上下文连续追问</span>
              </div>
            </div>
          </div>

          <div class="ai-capability-grid">
            <article v-for="item in aiCapabilities" :key="item.title" class="ai-capability-card">
              <span>{{ item.icon }}</span>
              <strong>{{ item.title }}</strong>
              <small>{{ item.text }}</small>
            </article>
          </div>

          <div class="ai-starter-row">
            <RouterLink v-for="question in aiStarters" :key="question" :to="{ path: `/p/${projectId}/ai`, query: { q: question } }">
              {{ question }}
            </RouterLink>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>项目导航</h2>
            <span>常用入口</span>
          </div>
          <div class="quick-grid">
            <RouterLink :to="`/p/${projectId}/apps`">应用管理</RouterLink>
            <RouterLink :to="`/p/${projectId}/usecases`">测试用例</RouterLink>
            <RouterLink :to="`/p/${projectId}/members`">项目成员</RouterLink>
            <RouterLink :to="`/p/${projectId}/labels`">标签管理</RouterLink>
            <RouterLink :to="`/p/${projectId}/apps/online`">采集源健康度</RouterLink>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>分析中心</h2>
            <span>分析工具</span>
          </div>
          <div class="quick-grid">
            <RouterLink :to="`/p/${projectId}/version/apps`">版本中心</RouterLink>
            <RouterLink :to="`/p/${projectId}/map/home`">链路地图</RouterLink>
            <RouterLink :to="`/p/${projectId}/search`">搜索中心</RouterLink>
          </div>
        </section>
      </div>

      <section class="activity-grid">
        <article class="panel activity-panel">
          <div class="panel-head">
            <h2>项目动态</h2>
            <span>{{ context.recentLogs?.length || 0 }} 条</span>
          </div>
          <div v-if="context.recentLogs?.length" class="activity-list">
            <article v-for="log in context.recentLogs" :key="log.id || `${log.title}-${log.createTime}`" class="activity-item">
              <div class="activity-title" v-html="log.title || '-'"></div>
              <p>{{ log.message || '暂无描述' }}</p>
              <span>{{ formatTime(log.createTime) }}</span>
            </article>
          </div>
          <div v-else class="empty-card subtle">暂无项目动态</div>
        </article>

      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import MascotCanvas from '@/components/MascotCanvas.vue'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const projectStore = useProjectStore()
const loading = ref(false)
const error = ref('')

const projectId = computed(() => String(route.params.projectId || ''))
const context = computed(() => projectStore.contextByProjectId[projectId.value])

const aiCapabilities = [
  { icon: '问', title: '项目问答', text: '基于当前项目、应用和页面信息回答问题' },
  { icon: '查', title: '异常排查', text: '结合项目、应用和链路地图提示排查路径' },
  { icon: '转', title: '智能跳转', text: '把常用入口整理成下一步操作建议' },
  { icon: '记', title: '会话记忆', text: '保留本地会话，方便连续追问和复盘' },
]

const aiStarters = ['总结项目风险', '线上异常怎么排查', '下一步优先看哪里']

function formatTime(value?: string) {
  if (!value) {
    return '-'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return parsed.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectContext(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载项目上下文失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.status-card {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error {
  color: #b91c1c;
}

.hero {
  --hero-accent: #0f766e;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.8fr);
  gap: 18px;
  padding: 26px;
  border-radius: 28px;
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--hero-accent) 22%, white) 0%, transparent 36%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(239, 247, 248, 0.94));
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 22px 48px rgba(15, 23, 42, 0.08);
}


.hero p {
  color: #5b6b79;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.hero-stat {
  padding: 16px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.hero-stat strong {
  display: block;
  font-size: 22px;
}

.hero-stat span {
  color: #6b7280;
  font-size: 13px;
}

.panel-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin-top: 18px;
}

.activity-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin-top: 18px;
}

.panel {
  padding: 22px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.app-list {
  display: grid;
  gap: 12px;
}

.app-card {
  padding: 16px;
  border-radius: 18px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.app-card-top,
.app-card-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.app-card-meta {
  margin-top: 8px;
  color: #6b7280;
  font-size: 13px;
}

.app-card-actions {
  display: flex;
  gap: 12px;
  margin-top: 10px;
}

.app-link,
.app-card-actions a,
.ai-entry {
  color: #0f766e;
  font-weight: 700;
}

.tag {
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.tag.online {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.tag.offline {
  background: rgba(148, 163, 184, 0.18);
  color: #475569;
}

.panel-desc {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 14px;
}

.ai-panel {
  --ai-accent: #0f766e;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 88% 12%, color-mix(in srgb, var(--ai-accent) 18%, white), transparent 34%),
    linear-gradient(145deg, rgba(255, 255, 255, .96), rgba(244, 250, 249, .92));
}

.ai-panel::after {
  position: absolute;
  right: -70px;
  bottom: -92px;
  width: 220px;
  height: 220px;
  content: '';
  border-radius: 999px;
  background: color-mix(in srgb, var(--ai-accent) 10%, transparent);
  pointer-events: none;
}

.ai-panel-head {
  position: relative;
  z-index: 1;
  align-items: flex-start;
}

.ai-entry.primary {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  padding: 10px 16px;
  background: #0f766e;
  color: #fff;
  box-shadow: 0 12px 24px rgba(15, 118, 110, .18);
}

.ai-showcase {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(180px, .8fr);
  gap: 12px;
}

.ai-mascot-card,
.ai-status-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 20px;
  background: rgba(255, 255, 255, .78);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .72);
}

.ai-mascot-card {
  padding: 14px 16px 14px 12px;
}

.ai-status-card {
  padding: 16px;
}

.ai-mascot-card strong,
.ai-status-card strong {
  display: block;
  color: #172033;
  font-size: 15px;
}

.ai-mascot-card span,
.ai-status-card span {
  display: block;
  margin-top: 4px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.45;
}

.status-dot {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 6px rgba(148, 163, 184, .14);
}

.status-dot.active {
  background: #16a34a;
  box-shadow: 0 0 0 6px rgba(22, 163, 74, .14), 0 0 18px rgba(22, 163, 74, .38);
}

.ai-capability-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.ai-capability-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  column-gap: 10px;
  row-gap: 2px;
  padding: 12px;
  border: 1px solid rgba(15, 118, 110, .11);
  border-radius: 16px;
  background: rgba(255, 255, 255, .74);
}

.ai-capability-card > span {
  grid-row: span 2;
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--ai-accent) 14%, white);
  color: #0f766e;
  font-weight: 900;
}

.ai-capability-card strong {
  color: #0f766e;
  font-size: 14px;
}

.ai-capability-card small {
  color: #64748b;
  line-height: 1.4;
}

.ai-starter-row {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.ai-starter-row a {
  border: 1px solid rgba(15, 118, 110, .16);
  border-radius: 999px;
  padding: 8px 12px;
  background: rgba(240, 253, 250, .86);
  color: #0f766e;
  font-size: 13px;
  font-weight: 700;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.quick-grid a {
  padding: 14px 16px;
  border-radius: 18px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: #0f766e;
  font-weight: 700;
}

.activity-panel {
  min-height: 220px;
}

.activity-list {
  display: grid;
  gap: 12px;
}

.activity-item {
  padding: 14px 16px;
  border-radius: 18px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.activity-item p {
  margin: 8px 0 0;
  color: #475569;
}

.activity-item span {
  display: block;
  margin-top: 8px;
  color: #94a3b8;
  font-size: 12px;
}

.activity-title :deep(a) {
  color: #0f766e;
  font-weight: 700;
}

.subtle {
  color: #94a3b8;
}

@media (max-width: 960px) {
  .hero,
  .panel-grid,
  .activity-grid {
    grid-template-columns: 1fr;
  }

  .hero-stats {
    grid-template-columns: 1fr;
  }

  .quick-grid,
  .ai-showcase,
  .ai-capability-grid {
    grid-template-columns: 1fr;
  }

  .ai-panel-head {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
