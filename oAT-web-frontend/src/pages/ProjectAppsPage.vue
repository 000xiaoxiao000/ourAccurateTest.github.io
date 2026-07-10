<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Applications</div>
        <h1>应用列表</h1>
      </div>
      <div class="header-actions">
        <button class="ghost-button" type="button" @click="creating = !creating">
          {{ creating ? '收起创建' : '新建应用' }}
        </button>
        <button class="action-button" type="button" @click="load">刷新</button>
      </div>
    </div>

    <form v-if="creating" class="editor-card" @submit.prevent="submitCreate">
      <h2>创建应用</h2>
      <div class="editor-grid">
        <label class="field">
          <span>应用名称</span>
          <input v-model.trim="form.name" class="text-input" type="text" />
        </label>
        <label v-if="isResidentCollector" class="field">
          <span>源码工程名</span>
          <input v-model.trim="form.srcName" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>主语言</span>
          <select v-model="form.language" class="select">
            <option v-for="option in languageOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label v-if="isResidentCollector" class="field">
          <span>作用范围</span>
          <input v-model.trim="form.range" class="text-input" type="text" />
        </label>
        <label class="field wide">
          <span>应用描述</span>
          <textarea v-model.trim="form.describe" class="text-area" rows="3" />
        </label>
        <label v-if="isResidentCollector" class="field wide">
          <span>应用参数</span>
          <textarea v-model.trim="form.properties" class="text-area" rows="4" />
        </label>
        <div class="field wide">
          <LanguageConfigForm v-model="form.languageConfig" :language="form.language" />
        </div>
        <label class="field">
          <span>当前版本</span>
          <input v-model.trim="form.currentVersion" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>当前分支</span>
          <input v-model.trim="form.currentBranch" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>当前 CommitId</span>
          <input v-model.trim="form.currentCommitId" class="text-input" type="text" />
        </label>
      </div>
      <p v-if="createError" class="error-text">{{ createError }}</p>
      <div class="editor-actions">
        <button class="primary-button" type="submit" :disabled="submittingCreate">
          {{ submittingCreate ? '创建中...' : '创建应用' }}
        </button>
      </div>
    </form>

    <div class="summary-grid">
      <div class="summary-card">
        <span>应用总数</span>
        <strong>{{ apps.length }}</strong>
      </div>
      <div class="summary-card">
        <span>在线实例</span>
        <strong>{{ totalOnlineCount }}</strong>
      </div>
      <div class="summary-card">
        <span>已配置仓库</span>
        <strong>{{ repoConfiguredCount }}</strong>
      </div>
      <div class="summary-card">
        <span>项目编号</span>
        <strong class="small-text">{{ projectId }}</strong>
      </div>
    </div>

    <div class="toolbar-card app-toolbar">
      <div>
        <h2>应用清单</h2>
        <p>搜索和主要结果保持在首屏，分页控件固定在列表底部。</p>
      </div>
      <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索应用 ID、名称、版本、工程或类型" aria-label="搜索应用" />
    </div>

    <div v-if="loading" class="status-card">正在加载应用列表...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else>
      <div v-if="filteredApps.length === 0" class="status-card">没有匹配的应用</div>
      <div v-else class="card-grid">
      <article v-for="app in paginatedApps" :key="app.id" class="card">
        <div class="card-top">
          <div>
            <strong>{{ app.name }}</strong>
            <div class="app-id">{{ app.id }}</div>
            <div class="card-sub">{{ app.srcName || app.range || '未配置源工程信息' }}</div>
          </div>
          <RouterLink :class="['badge', collectorHealthTone(app)]" :to="`/p/${projectId}/apps/online?appId=${app.id}`">
            {{ collectorHealthLabel(app) }}
          </RouterLink>
        </div>
        <div class="card-desc">{{ app.describe || '暂无应用描述' }}</div>
        <div class="meta-list">
          <span>{{ app.language || app.sourceType || 'JAVA' }}</span>
          <span>{{ collectorSummary(app) }}</span>
          <span>版本 {{ app.currentVersion || '-' }}</span>
          <span>分支 {{ app.currentBranch || '-' }}</span>
          <span>{{ app.repoConfigured ? '已配置仓库' : '未配置仓库' }}</span>
        </div>
        <div class="card-actions">
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/settings`">应用设置</RouterLink>
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/api-endpoints`">接口扫描</RouterLink>
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/repository`">仓库配置</RouterLink>
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/versions`">版本中心</RouterLink>
          <a :href="backendApiUrl(`/p/${projectId}/app/${app.id}/oAT.key`)" download>下载注册文件</a>
          <button class="text-danger" type="button" @click="startDelete(app.id)">删除应用</button>
        </div>

        <form v-if="deletingId === app.id" class="delete-form" @submit.prevent="submitDelete(app.id)">
          <label class="field">
            <span>输入登录密码以删除应用</span>
            <input v-model="deletePassword" class="text-input" type="password" autocomplete="current-password" />
          </label>
          <p v-if="deleteError" class="error-text">{{ deleteError }}</p>
          <div class="editor-actions">
            <button class="danger-button" type="submit" :disabled="submittingDelete">
              {{ submittingDelete ? '删除中...' : '确认删除' }}
            </button>
            <button class="ghost-button" type="button" @click="cancelDelete">取消</button>
          </div>
        </form>
      </article>
      </div>
      <AppPagination
        v-if="filteredApps.length > 0"
        v-model:page="currentPage"
        v-model:page-size="pageSize"
        :total="filteredApps.length"
        item-name="个应用"
        :page-sizes="[10, 20, 50, 100]"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { backendApiUrl } from '@/api/http'
import AppPagination from '@/components/AppPagination.vue'
import LanguageConfigForm from '@/features/app/LanguageConfigForm.vue'
import { isResidentLanguage, languageOptions } from '@/features/app/languageProfiles'
import { useProjectStore } from '@/stores/project'
import type { AppSummary, CollectorSourceSummary } from '@/api/types'

const DEFAULT_APP_PROPERTIES = `#代码追踪范围包括
#codeStack.include=`
const DEFAULT_LANGUAGE_CONFIG = '{}'
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.appsByProjectId[projectId.value] || [])
const collectorSources = computed(() => projectStore.collectorSourcesByProjectId[projectId.value]?.sources || [])
const loading = ref(false)
const error = ref('')
const creating = ref(false)
const createError = ref('')
const deleteError = ref('')
const deletingId = ref('')
const deletePassword = ref('')
const submittingCreate = ref(false)
const submittingDelete = ref(false)
const keyword = ref('')
const pageSize = ref(10)
const currentPage = ref(1)
const form = reactive({
  name: '',
  srcName: '',
  language: 'JAVA',
  languageConfig: DEFAULT_LANGUAGE_CONFIG,
  range: '',
  describe: '',
  properties: DEFAULT_APP_PROPERTIES,
  currentVersion: '',
  currentBranch: '',
  currentCommitId: '',
})

const filteredApps = computed(() => {
  const needle = keyword.value.toLowerCase()
  if (!needle) return apps.value
  return apps.value.filter((app) => [
    app.id,
    app.name,
    app.srcName,
    app.range,
    app.language,
    app.sourceType,
    collectorHealth(app),
    collectorSummary(app),
    app.currentVersion,
    app.currentBranch,
    app.currentCommitId,
  ].join(' ').toLowerCase().includes(needle))
})

const paginatedApps = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredApps.value.slice(start, start + pageSize.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredApps.value.length / pageSize.value)))

const totalOnlineCount = computed(() => apps.value.filter((app) => collectorHealth(app) === 'ONLINE').length)

const repoConfiguredCount = computed(() => apps.value.filter((app) => app.repoConfigured).length)
const isResidentCollector = computed(() => isResidentLanguage(form.language))

function applyRouteIntent() {
  const create = route.query.create
  if (create === '1' || create === 'true') {
    creating.value = true
    createError.value = ''
  }
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await Promise.all([
      projectStore.loadProjectApps(projectId.value),
      projectStore.loadCollectorSources(projectId.value).catch(() => null),
    ])
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载应用列表失败'
  } finally {
    loading.value = false
  }
}

type AppCollectorHealth = 'ONLINE' | 'SILENT' | 'OFFLINE' | 'UNKNOWN' | 'NEVER'

function appCollectorSources(app: AppSummary): CollectorSourceSummary[] {
  return collectorSources.value.filter((source) => source.appId === app.id)
}

function collectorHealth(app: AppSummary): AppCollectorHealth {
  const sources = appCollectorSources(app)
  if (!sources.length) return app.onlineCount > 0 ? 'ONLINE' : 'NEVER'
  if (sources.some((source) => source.health === 'ONLINE')) return 'ONLINE'
  if (sources.some((source) => source.health === 'SILENT')) return 'SILENT'
  if (sources.some((source) => source.health === 'UNKNOWN')) return 'UNKNOWN'
  return 'OFFLINE'
}

function collectorHealthLabel(app: AppSummary) {
  const health = collectorHealth(app)
  const sources = appCollectorSources(app)
  if (health === 'ONLINE') return sources.length ? `${sources.filter((source) => source.health === 'ONLINE').length} 源在线` : `${app.onlineCount || 0} 在线`
  if (health === 'SILENT') return '采集静默'
  if (health === 'OFFLINE') return '采集离线'
  if (health === 'UNKNOWN') return '状态未知'
  return '未接入'
}

function collectorHealthTone(app: AppSummary) {
  const health = collectorHealth(app)
  if (health === 'ONLINE') return 'online'
  if (health === 'SILENT') return 'silent'
  if (health === 'OFFLINE') return 'offline'
  if (health === 'UNKNOWN') return 'unknown'
  return 'never'
}

function collectorSummary(app: AppSummary) {
  const sources = appCollectorSources(app)
  if (!sources.length) return app.onlineCount > 0 ? `Java 实例 ${app.onlineCount}` : '无采集源'
  const resident = sources.filter((source) => source.collectorType === 'RESIDENT').length
  const batch = sources.filter((source) => source.collectorType === 'BATCH').length
  return `采集源 ${sources.length} · 常驻 ${resident} · 批量 ${batch}`
}

async function submitCreate() {
  if (!form.name) {
    createError.value = '应用名称不能为空'
    return
  }
  submittingCreate.value = true
  createError.value = ''
  try {
    const created = await projectStore.createManagedApp(projectId.value, normalizeFormForSubmit())
    form.name = ''
    form.srcName = ''
    form.language = 'JAVA'
    form.languageConfig = DEFAULT_LANGUAGE_CONFIG
    form.range = ''
    form.describe = ''
    form.properties = DEFAULT_APP_PROPERTIES
    form.currentVersion = ''
    form.currentBranch = ''
    form.currentCommitId = ''
    creating.value = false
    await router.push(`/p/${projectId.value}/apps/${created.id}/settings`)
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '创建应用失败'
  } finally {
    submittingCreate.value = false
  }
}

function normalizeFormForSubmit() {
  return {
    ...form,
    srcName: isResidentCollector.value ? form.srcName : '',
    range: isResidentCollector.value ? form.range : 'only',
    properties: isResidentCollector.value ? form.properties : '',
  }
}

function startDelete(appId: string) {
  deletingId.value = appId
  deletePassword.value = ''
  deleteError.value = ''
}

function cancelDelete() {
  deletingId.value = ''
  deletePassword.value = ''
  deleteError.value = ''
}

async function submitDelete(appId: string) {
  if (!deletePassword.value) {
    deleteError.value = '请输入登录密码'
    return
  }
  submittingDelete.value = true
  deleteError.value = ''
  try {
    await projectStore.removeManagedApp(projectId.value, appId, deletePassword.value)
    cancelDelete()
  } catch (err) {
    deleteError.value = err instanceof Error ? err.message : '删除应用失败'
  } finally {
    submittingDelete.value = false
  }
}

onMounted(load)

watch(
  () => route.query.create,
  () => {
    applyRouteIntent()
  },
  { immediate: true },
)

watch([keyword, pageSize], () => {
  currentPage.value = 1
})

watch(totalPages, (pages) => {
  if (currentPage.value > pages) currentPage.value = pages
})
</script>

<style scoped>
.page-header,
.header-actions,
.editor-actions,
.card-actions {
  display: flex;
  gap: 12px;
}

.page-header {
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header-actions,
.card-actions,
.editor-actions {
  flex-wrap: wrap;
}


.action-button,
.primary-button,
.ghost-button,
.danger-button {
  border: none;
  border-radius: 999px;
  min-height: var(--oat-min-target);
  padding: 10px 16px;
  cursor: pointer;
  font-weight: 800;
}

.action-button,
.primary-button {
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-primary-hover));
  color: #fff;
}

.ghost-button {
  border: 1px solid rgba(var(--oat-primary-rgb), .16);
  background: rgba(var(--oat-primary-rgb), 0.08);
  color: var(--oat-primary-dark);
}

.danger-button {
  background: linear-gradient(135deg, var(--oat-danger), #ef4444);
  color: #fff;
}

.editor-card,
.status-card,
.summary-card,
.toolbar-card {
  padding: 18px;
  border-radius: var(--oat-radius-lg);
  background: var(--oat-surface-raised);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.summary-card {
  background:
    radial-gradient(circle at top right, rgba(15, 118, 110, 0.13), transparent 44%),
    rgba(255, 255, 255, 0.94);
}

.summary-card span {
  color: #64748b;
  font-weight: 700;
}

.summary-card strong {
  display: block;
  margin-top: 8px;
  color: #0f172a;
  font-size: 28px;
  word-break: break-word;
}

.summary-card .small-text {
  font-size: 13px;
}

.app-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, .8fr) minmax(260px, 1.2fr);
  gap: 12px;
  align-items: end;
  margin-bottom: 18px;
  position: sticky;
  top: 82px;
  z-index: 8;
  backdrop-filter: saturate(180%) blur(14px);
}

.app-toolbar h2,
.app-toolbar p {
  margin: 0;
}

.app-toolbar h2 {
  margin-bottom: 6px;
  color: #0f172a;
  font-size: 18px;
}

.app-toolbar p {
  color: #64748b;
}

.editor-card {
  display: grid;
  gap: 14px;
  margin-bottom: 18px;
}

.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 8px;
}

.field.wide {
  grid-column: 1 / -1;
}

.field span {
  color: #475569;
  font-size: 13px;
}

.text-input,
.text-area {
  width: 100%;
  border: 1px solid rgba(15, 23, 42, 0.14);
  border-radius: var(--oat-radius-md);
  min-height: var(--oat-min-target);
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.96);
}

.status-card.error,
.error-text,
.text-danger {
  color: #b91c1c;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 360px), 520px));
  justify-content: start;
  align-items: stretch;
  gap: 16px;
  min-height: min(520px, calc(100vh - 360px));
}

.card {
  padding: 20px;
  border-radius: var(--oat-radius-xl);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.card-sub,
.card-desc,
.meta-list,
.helper-text {
  color: #64748b;
}

.helper-text {
  margin: 10px 0 0;
  font-size: 13px;
}

.card-sub {
  margin-top: 6px;
  font-size: 13px;
}

.app-id {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
  word-break: break-all;
}

.card-desc {
  margin-top: 14px;
  min-height: 42px;
}

.meta-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
  font-size: 13px;
}

.card-actions {
  margin-top: 14px;
}

.card-actions a,
.card-actions button {
  color: #0f766e;
  font-weight: 700;
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
}

.card-actions .text-danger {
  color: #dc2626;
}

.card-actions .text-danger:hover:not(:disabled) {
  color: #b91c1c;
}

.delete-form {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid rgba(185, 28, 28, 0.12);
}

.badge {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  text-decoration: none;
}

.badge.online {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.badge.offline {
  background: rgba(148, 163, 184, 0.18);
  color: #475569;
}

.badge.silent {
  background: rgba(217, 119, 6, 0.14);
  color: #b45309;
}

.badge.unknown,
.badge.never {
  background: rgba(100, 116, 139, 0.12);
  color: #64748b;
}

@media (max-width: 720px) {
  .editor-grid,
  .summary-grid,
  .app-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
