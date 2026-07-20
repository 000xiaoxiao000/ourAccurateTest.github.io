<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Source Projects</div>
        <h1>源码工程</h1>
      </div>
      <div class="header-actions">
        <button class="ghost-button" type="button" @click="creating = !creating">
          {{ creating ? '收起创建' : '新建应用' }}
        </button>
        <AppRefreshButton :loading="loading" @click="load" />
      </div>
    </div>

    <form v-if="creating" class="editor-card" @submit.prevent="submitCreate">
      <h2>创建源码工程</h2>
      <p class="form-hint">源码工程用于绑定代码仓库，并作为 AI 验证基线中的源码对象。</p>
      <div class="editor-grid">
        <label class="field">
          <span>工程名称</span>
          <input v-model.trim="form.name" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>主语言</span>
          <select v-model="form.language" class="select">
            <option v-for="option in languageOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label class="field wide">
          <span>工程说明</span>
          <textarea v-model.trim="form.describe" class="text-area" rows="3" />
        </label>
        <div class="field wide">
          <LanguageConfigForm v-model="form.languageConfig" :language="form.language" />
        </div>
      </div>
      <p v-if="createError" class="error-text">{{ createError }}</p>
      <div class="editor-actions">
        <button class="primary-button" type="submit" :disabled="submittingCreate">
          {{ submittingCreate ? '创建中...' : '创建源码工程' }}
        </button>
      </div>
    </form>

    <div class="summary-grid">
      <div class="summary-card">
        <span>源码工程</span>
        <strong>{{ apps.length }}</strong>
      </div>
      <div class="summary-card">
        <span>已配置仓库</span>
        <strong>{{ repoConfiguredCount }}</strong>
      </div>
      <div class="summary-card">
        <span>语言类型</span>
        <strong>{{ languageCount }}</strong>
      </div>
      <div class="summary-card">
        <span>项目编号</span>
        <strong class="small-text">{{ projectId }}</strong>
      </div>
    </div>

    <div class="toolbar-card app-toolbar">
      <div>
        <h2>源码工程清单</h2>
        <p>用于后续配置代码仓库、导入源码快照，并参与需求-用例-源码一致性验证。</p>
      </div>
      <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索工程 ID、名称、语言或描述" aria-label="搜索源码工程" />
    </div>

    <div v-if="loading" class="status-card">正在加载源码工程列表...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else>
      <div v-if="filteredApps.length === 0" class="status-card">没有匹配的源码工程</div>
      <div v-else class="card-grid">
      <article v-for="app in paginatedApps" :key="app.id" class="card">
        <div class="card-top">
          <div>
            <strong>{{ app.name }}</strong>
            <div class="app-id">{{ app.id }}</div>
            <div class="card-sub">{{ app.repoConfigured ? '已绑定代码仓库' : '待配置代码仓库' }}</div>
          </div>
          <span :class="['badge', app.repoConfigured ? 'online' : 'never']">{{ app.repoConfigured ? '仓库已配置' : '未配置仓库' }}</span>
        </div>
        <div class="card-desc">{{ app.describe || '暂无工程说明' }}</div>
        <div class="meta-list">
          <span>{{ app.language || app.sourceType || 'JAVA' }}</span>
          <span>{{ app.repoConfigured ? '可作为源码导入来源' : '需先配置仓库' }}</span>
        </div>
        <div class="card-actions">
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/settings`">工程设置</RouterLink>
          <RouterLink :to="`/p/${projectId}/apps/${app.id}/repository`">仓库配置</RouterLink>
          <button class="text-danger" type="button" @click="startDelete(app.id)">删除工程</button>
        </div>

        <form v-if="deletingId === app.id" class="delete-form" @submit.prevent="submitDelete(app.id)">
          <label class="field">
            <span>输入登录密码以删除源码工程</span>
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
        item-name="个源码工程"
        :page-sizes="[10, 20, 50, 100]"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import LanguageConfigForm from '@/features/app/LanguageConfigForm.vue'
import { languageOptions } from '@/features/app/languageProfiles'
import { useProjectStore } from '@/stores/project'
import type { AppSummary } from '@/api/types'

const DEFAULT_LANGUAGE_CONFIG = '{}'
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.appsByProjectId[projectId.value] || [])
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
  language: 'JAVA',
  languageConfig: DEFAULT_LANGUAGE_CONFIG,
  describe: '',
})

const filteredApps = computed(() => {
  const needle = keyword.value.toLowerCase()
  if (!needle) return apps.value
  return apps.value.filter((app) => [
    app.id,
    app.name,
    app.language,
    app.sourceType,
    app.describe,
    app.repoConfigured ? '已配置仓库' : '未配置仓库',
  ].join(' ').toLowerCase().includes(needle))
})

const paginatedApps = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredApps.value.slice(start, start + pageSize.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredApps.value.length / pageSize.value)))

const repoConfiguredCount = computed(() => apps.value.filter((app) => app.repoConfigured).length)
const languageCount = computed(() => new Set(apps.value.map((app) => app.language || app.sourceType || 'JAVA')).size)

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
    await projectStore.loadProjectApps(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载源码工程列表失败'
  } finally {
    loading.value = false
  }
}

async function submitCreate() {
  if (submittingCreate.value) return
  if (!form.name) {
    createError.value = '工程名称不能为空'
    return
  }
  submittingCreate.value = true
  createError.value = ''
  try {
    const created = await projectStore.createManagedApp(projectId.value, normalizeFormForSubmit())
    form.name = ''
    form.language = 'JAVA'
    form.languageConfig = DEFAULT_LANGUAGE_CONFIG
    form.describe = ''
    creating.value = false
    await router.push(`/p/${projectId.value}/apps/${created.id}/settings`)
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '创建源码工程失败'
  } finally {
    submittingCreate.value = false
  }
}

function normalizeFormForSubmit() {
  return {
    ...form,
    srcName: form.name,
    range: 'only',
    properties: '',
    currentVersion: '',
    currentBranch: '',
    currentCommitId: '',
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
  if (submittingDelete.value) return
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
    deleteError.value = err instanceof Error ? err.message : '删除源码工程失败'
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

.form-hint {
  margin: -4px 0 2px;
  color: #64748b;
  line-height: 1.55;
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
