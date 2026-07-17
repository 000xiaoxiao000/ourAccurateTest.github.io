<template>
  <section class="projects-page">
    <div class="page-head">
      <div>
        <div class="eyebrow">Bootstrap</div>
        <h1>项目列表</h1>
      </div>
      <div class="head-actions">
        <button class="ghost-button" type="button" @click="toggleCreate">
          {{ creating ? '收起创建' : '新建项目' }}
        </button>
        <button class="refresh-button" type="button" @click="load">刷新</button>
      </div>
    </div>

    <p class="page-guidance">常用操作和筛选保持在首屏内，项目卡片按当前条件分页展示。</p>

    <form v-if="creating" class="editor-card" @submit.prevent="submitCreate">
      <h2>创建项目</h2>
      <label class="field">
        <span>项目名称</span>
        <input v-model.trim="createForm.name" class="text-input" type="text" />
      </label>
      <label class="field">
        <span>项目描述</span>
        <textarea v-model.trim="createForm.describe" class="text-area" rows="4" />
      </label>
      <p v-if="createError" class="error-text">{{ createError }}</p>
      <div class="editor-actions">
        <button class="primary-button" type="submit" :disabled="submittingCreate">
          {{ submittingCreate ? '创建中...' : '创建项目' }}
        </button>
      </div>
    </form>

    <div class="stats-grid">
      <div class="stat-card">
        <span>项目总数</span>
        <strong>{{ projects.length }}</strong>
        <small>你当前可访问的全部项目</small>
      </div>
      <div class="stat-card">
        <span>已配置源码工程</span>
        <strong>{{ projectWithAppsCount }}</strong>
        <small>至少创建了一个源码工程</small>
      </div>
      <div class="stat-card">
        <span>待完善项目</span>
        <strong>{{ projectWithoutAppsCount }}</strong>
        <small>还没有创建源码工程</small>
      </div>
      <div class="stat-card">
        <span>最近维护项目</span>
        <strong>{{ maintainedProjectCount }}</strong>
        <small>已有源码工程或有更新时间</small>
      </div>
    </div>

    <div class="toolbar-card project-toolbar">
      <div>
        <h2>项目列表</h2>
        <p>支持按项目名称/描述筛选，并可按最近更新、创建时间或名称排序。</p>
      </div>
      <div class="filter-row">
        <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索项目名称、描述或创建人" aria-label="搜索项目" />
        <select v-model="sortMode" class="text-input select-input">
          <option value="recent">最近更新优先</option>
          <option value="created">最近创建优先</option>
          <option value="name">按名称排序</option>
        </select>
        <div class="view-toggle" role="group" aria-label="项目视图切换">
          <button type="button" :class="{ active: viewMode === 'card' }" @click="viewMode = 'card'">卡片</button>
          <button type="button" :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">列表</button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="status-card">正在加载项目列表...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else>
      <div v-if="filteredProjects.length === 0" class="status-card">没有匹配的项目</div>
      <div v-else class="project-grid" :class="{ 'list-view': viewMode === 'list' }">
      <article v-for="project in paginatedProjects" :key="project.id" class="project-card" :class="{ recent: isRecentProject(project.id) }">
        <div class="project-card-body">
          <div class="project-card-main">
            <div class="project-card-head">
              <RouterLink class="project-title" :to="`/p/${project.id}/home`" @click="recordRecentProject(project)">{{ project.name }}</RouterLink>
              <span v-if="isRecentProject(project.id)" class="recent-badge">最近访问</span>
            </div>
            <p class="project-card-desc">{{ project.describe || '暂无项目描述' }}</p>
          </div>

          <div class="project-card-meta">
            <span><strong>{{ projectAppCount(project.id) }}</strong> 源码工程</span>
            <span><strong>{{ project.memberCount }}</strong> 成员</span>
            <span class="project-creator">{{ project.createDisplayName || project.create || '-' }}</span>
            <span class="project-recent">{{ recentProjectText(project.id) }}</span>
          </div>

          <div class="project-card-actions">
            <button class="link-button" type="button" @click="recordRecentProject(project); startEdit(project)">编辑</button>
            <button class="link-button danger" type="button" @click="startDelete(project)">删除</button>
            <RouterLink class="link-button route" :to="`/p/${project.id}/home`" @click="recordRecentProject(project)">进入项目</RouterLink>
          </div>
        </div>

        <form v-if="editingId === project.id" class="inline-form" @submit.prevent="submitEdit(project.id)">
          <label class="field">
            <span>项目名称</span>
            <input v-model.trim="editForm.name" class="text-input" type="text" />
          </label>
          <label class="field">
            <span>项目描述</span>
            <textarea v-model.trim="editForm.describe" class="text-area" rows="3" />
          </label>
          <p v-if="editError" class="error-text">{{ editError }}</p>
          <div class="editor-actions">
            <button class="primary-button" type="submit" :disabled="submittingEdit">
              {{ submittingEdit ? '保存中...' : '保存修改' }}
            </button>
            <button class="ghost-button" type="button" @click="cancelEdit">取消</button>
          </div>
        </form>

        <form v-if="deletingId === project.id" class="inline-form danger-panel" @submit.prevent="submitDelete(project.id)">
          <label class="field">
            <span>输入登录密码以删除项目</span>
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
        v-if="filteredProjects.length > 0"
        v-model:page="currentPage"
        v-model:page-size="pageSize"
        :total="filteredProjects.length"
        item-name="个项目"
        :page-sizes="[6, 9, 12, 24]"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import type { ProjectSummary } from '@/api/types'
import AppPagination from '@/components/AppPagination.vue'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const { projects } = storeToRefs(projectStore)
const loading = ref(false)
const error = ref('')
const creating = ref(false)
const createError = ref('')
const editError = ref('')
const deleteError = ref('')
const editingId = ref('')
const deletingId = ref('')
const submittingCreate = ref(false)
const submittingEdit = ref(false)
const submittingDelete = ref(false)
const deletePassword = ref('')
const keyword = ref('')
const sortMode = ref<'recent' | 'created' | 'name'>('recent')
const viewMode = ref<'card' | 'list'>('card')
const pageSize = ref(6)
const currentPage = ref(1)
const recentProjects = ref<Array<{ id: string; name: string; visitedAt: number }>>([])
const projectAppCounts = ref<Record<string, number>>({})

const createForm = reactive({
  name: '',
  describe: '',
})

const editForm = reactive({
  name: '',
  describe: '',
})

const projectWithAppsCount = computed(() => projects.value.filter((project) => projectAppCount(project.id) > 0).length)

const projectWithoutAppsCount = computed(() => projects.value.filter((project) => projectAppCount(project.id) === 0).length)

const maintainedProjectCount = computed(() => projects.value.filter((project) => projectAppCount(project.id) > 0 || Boolean(project.updateTime)).length)

const filteredProjects = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  const matched = projects.value.filter((project) => {
    if (!needle) return true
    return [project.name, project.describe, project.createDisplayName, project.create]
      .join(' ')
      .toLowerCase()
      .includes(needle)
  })
  return matched.sort((left, right) => {
    if (sortMode.value === 'name') {
      return left.name.localeCompare(right.name, 'zh-CN')
    }
    const leftTime = sortMode.value === 'created' ? timeValue(left.createTime) : timeValue(left.updateTime || left.createTime)
    const rightTime = sortMode.value === 'created' ? timeValue(right.createTime) : timeValue(right.updateTime || right.createTime)
    return rightTime - leftTime
  })
})

const paginatedProjects = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredProjects.value.slice(start, start + pageSize.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredProjects.value.length / pageSize.value)))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const loadedProjects = await projectStore.loadProjects()
    await loadProjectAppCounts(loadedProjects)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载项目失败'
  } finally {
    loading.value = false
  }
}

async function loadProjectAppCounts(projectList: ProjectSummary[]) {
  await Promise.all(projectList.map(async (project) => {
    try {
      const apps = await projectStore.loadProjectApps(project.id)
      projectAppCounts.value = { ...projectAppCounts.value, [project.id]: apps.length }
    } catch {
      projectAppCounts.value = { ...projectAppCounts.value, [project.id]: 0 }
    }
  }))
}

function projectAppCount(projectId: string) {
  return projectAppCounts.value[projectId] ?? 0
}

function loadRecentProjects() {
  try {
    recentProjects.value = JSON.parse(localStorage.getItem('oat_recent_projects') || '[]')
  } catch {
    recentProjects.value = []
  }
}

function recordRecentProject(project: ProjectSummary) {
  const next = [
    { id: project.id, name: project.name, visitedAt: Date.now() },
    ...recentProjects.value.filter((item) => item.id !== project.id),
  ].slice(0, 12)
  recentProjects.value = next
  localStorage.setItem('oat_recent_projects', JSON.stringify(next))
}

function isRecentProject(projectId: string) {
  return recentProjects.value.some((item) => item.id === projectId)
}

function recentProjectText(projectId: string) {
  const item = recentProjects.value.find((recent) => recent.id === projectId)
  return item ? `最近访问 ${formatRelativeTime(item.visitedAt)}` : '首次访问后会记录'
}

function timeValue(value?: string) {
  return value ? new Date(value).getTime() || 0 : 0
}

function formatRelativeTime(timestamp: number) {
  const diff = Date.now() - timestamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

function toggleCreate() {
  creating.value = !creating.value
  createError.value = ''
}

function applyRouteIntent() {
  const create = route.query.create
  const editId = typeof route.query.edit === 'string' ? route.query.edit : ''
  const deleteId = typeof route.query.delete === 'string' ? route.query.delete : ''

  if (create === '1' || create === 'true') {
    creating.value = true
    editingId.value = ''
    deletingId.value = ''
    createError.value = ''
    return
  }

  if (editId) {
    const project = projects.value.find((item) => item.id === editId)
    if (project) {
      startEdit(project)
    }
    return
  }

  if (deleteId) {
    startDelete({ id: deleteId })
    return
  }
}

async function submitCreate() {
  if (!createForm.name) {
    createError.value = '项目名称不能为空'
    return
  }
  submittingCreate.value = true
  createError.value = ''
  try {
    const project = await projectStore.createManagedProject({ ...createForm })
    createForm.name = ''
    createForm.describe = ''
    creating.value = false
    editingId.value = ''
    deletingId.value = ''
    await load()
    await router.push(`/p/${project.id}/home`)
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '创建项目失败'
  } finally {
    submittingCreate.value = false
  }
}

function startEdit(project: { id: string; name: string; describe?: string }) {
  editingId.value = project.id
  deletingId.value = ''
  editForm.name = project.name
  editForm.describe = project.describe || ''
  editError.value = ''
}

function cancelEdit() {
  editingId.value = ''
  editError.value = ''
}

async function submitEdit(projectId: string) {
  if (!editForm.name) {
    editError.value = '项目名称不能为空'
    return
  }
  submittingEdit.value = true
  editError.value = ''
  try {
    await projectStore.updateManagedProject(projectId, { ...editForm })
    editingId.value = ''
  } catch (err) {
    editError.value = err instanceof Error ? err.message : '修改项目失败'
  } finally {
    submittingEdit.value = false
  }
}

function startDelete(project: { id: string }) {
  deletingId.value = project.id
  editingId.value = ''
  deletePassword.value = ''
  deleteError.value = ''
}

function cancelDelete() {
  deletingId.value = ''
  deletePassword.value = ''
  deleteError.value = ''
}

async function submitDelete(projectId: string) {
  if (!deletePassword.value) {
    deleteError.value = '请输入登录密码'
    return
  }
  submittingDelete.value = true
  deleteError.value = ''
  try {
    await projectStore.removeManagedProject(projectId, deletePassword.value)
    cancelDelete()
  } catch (err) {
    deleteError.value = err instanceof Error ? err.message : '删除项目失败'
  } finally {
    submittingDelete.value = false
  }
}

onMounted(() => {
  loadRecentProjects()
  load()
})

watch([keyword, sortMode, pageSize], () => {
  currentPage.value = 1
})

watch(totalPages, (pages) => {
  if (currentPage.value > pages) {
    currentPage.value = pages
  }
})

watch(
  [projects, () => route.query.create, () => route.query.edit, () => route.query.delete],
  () => {
    applyRouteIntent()
  },
  { immediate: true },
)
</script>

<style scoped>
.page-head,
.head-actions,
.project-card-actions,
.editor-actions {
  display: flex;
  gap: 12px;
}

.page-head,
.project-card-meta,
.project-card-actions {
  justify-content: space-between;
}

.page-head {
  align-items: center;
  margin-bottom: 20px;
}

.head-actions {
  flex-wrap: wrap;
}

.page-guidance {
  margin: -8px 0 14px;
  color: #64748b;
  font-size: 14px;
}


.refresh-button,
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

.refresh-button,
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

.status-card,
.editor-card,
.toolbar-card,
.stat-card {
  padding: 18px;
  border-radius: var(--oat-radius-lg);
  background: var(--oat-surface-raised);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.editor-card {
  display: grid;
  gap: 14px;
  margin-bottom: 18px;
}

.status-card.error,
.error-text {
  color: #b91c1c;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  background:
    radial-gradient(circle at top right, rgba(15, 118, 110, 0.14), transparent 42%),
    rgba(255, 255, 255, 0.94);
}

.stat-card span,
.stat-card small,
.project-toolbar p {
  color: #64748b;
}

.stat-card strong {
  display: block;
  margin: 8px 0 4px;
  color: #0f172a;
  font-size: 28px;
}

.project-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1.2fr);
  gap: 16px;
  align-items: end;
  margin-bottom: 18px;
  position: sticky;
  top: 82px;
  z-index: 8;
  backdrop-filter: saturate(180%) blur(14px);
}

.project-toolbar h2,
.project-toolbar p {
  margin: 0;
}

.project-toolbar h2 {
  margin-bottom: 6px;
}

.filter-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 168px auto;
  gap: 10px;
  align-items: center;
}

.select-input {
  border-radius: 999px;
}

.view-toggle {
  display: inline-flex;
  padding: 4px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
}

.view-toggle button,
.view-toggle button {
  border: none;
  border-radius: 999px;
  padding: 8px 12px;
  background: transparent;
  color: #475569;
  cursor: pointer;
  font-weight: 800;
}

.view-toggle button.active,
.view-toggle button.active {
  background: #0f766e;
  color: #fff;
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
  align-items: start;
}

.project-grid.list-view {
  grid-template-columns: 1fr;
  gap: 10px;
}

.project-grid.list-view .project-card {
  padding: 0;
  overflow: hidden;
  border-radius: var(--oat-radius-lg);
  background: rgba(255, 255, 255, 0.96);
}

.project-card {
  padding: 18px;
  border-radius: var(--oat-radius-lg);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.99) 0%, rgba(248, 252, 253, 0.96) 100%);
  border: 1px solid rgba(15, 23, 42, 0.09);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.07);
  transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease;
}

.project-card:hover {
  transform: translateY(-1px);
  border-color: rgba(15, 118, 110, 0.18);
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.09);
}

.project-card.recent {
  border-color: rgba(15, 118, 110, 0.22);
}

.project-card-body {
  display: grid;
  gap: 16px;
}

.project-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
}

.recent-badge {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.1);
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
}

.project-title {
  color: #0f172a;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.3;
}

.project-card-desc {
  display: -webkit-box;
  min-height: 44px;
  margin: 8px 0 0;
  overflow: hidden;
  color: #5b6b79;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.project-card-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 0;
  color: #6b7280;
  font-size: 13px;
}

.project-card-meta span {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.82);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-card-meta strong {
  color: #0f172a;
  font-weight: 800;
}

.project-creator,
.project-recent {
  grid-column: auto;
}

.project-card-actions {
  justify-content: flex-start;
  margin-top: 0;
  align-items: center;
  flex-wrap: wrap;
  padding-top: 2px;
}

.project-grid.list-view .project-card-body {
  grid-template-columns: minmax(240px, 1.1fr) minmax(420px, 1.4fr) minmax(210px, auto);
  gap: 18px;
  align-items: center;
  min-height: 104px;
  padding: 18px 20px;
}

.project-grid.list-view .project-card-main {
  min-width: 0;
}

.project-grid.list-view .project-card-desc {
  min-height: 0;
  -webkit-line-clamp: 1;
}

.project-grid.list-view .project-card-meta {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.project-grid.list-view .project-card-meta span {
  padding: 0;
  border: 0;
  background: transparent;
}

.project-grid.list-view .project-card-actions {
  justify-content: flex-end;
  padding-top: 0;
}

.inline-form {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid rgba(15, 23, 42, 0.08);
}

.danger-panel {
  border-top-color: rgba(185, 28, 28, 0.16);
}

.field {
  display: grid;
  gap: 8px;
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

.link-button {
  min-height: 34px;
  border: 1px solid rgba(15, 118, 110, 0.12);
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.06);
  padding: 6px 12px;
  color: #0f766e;
  font-weight: 800;
  cursor: pointer;
}

.link-button.route {
  text-decoration: none;
  background: #0f766e;
  color: #fff;
}

.link-button.danger {
  border-color: rgba(185, 28, 28, 0.12);
  background: rgba(185, 28, 28, 0.06);
  color: #b91c1c;
}

@media (max-width: 980px) {
  .stats-grid,
  .project-toolbar,
  .filter-row,
  .project-grid.list-view .project-card-body {
    grid-template-columns: 1fr;
  }

  .project-grid.list-view .project-card-body {
    gap: 14px;
  }

  .project-grid.list-view .project-card-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .project-card-meta,
  .project-grid.list-view .project-card-meta {
    grid-template-columns: 1fr;
  }
}
</style>
