<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Usecase Center</div>
        <h1>用例中心</h1>
        <p class="subtext">按目录查看项目用例，逐步迁移旧版用例工作流到 SPA。</p>
      </div>
      <div class="header-actions">
        <a class="ghost-button" :href="templateDownloadLink">下载模板</a>
        <button class="ghost-button" type="button" @click="selectImportFile">上传用例</button>
        <a class="ghost-button" :href="exportLink">导出</a>
        <button class="ghost-button" type="button" :disabled="rebuildingSearch" @click="rebuildSearchData">
          {{ rebuildingSearch ? '重建中...' : '重建检索数据' }}
        </button>
        <button class="ghost-button" type="button" @click="openDirectoryEditor()">新建目录</button>
        <RouterLink class="primary-link" :to="newUsecaseLink">新建用例</RouterLink>
        <button class="action-button" type="button" @click="load">刷新</button>
      </div>
    </div>
    <input ref="importInput" type="file" accept=".xlsx,.xls" class="hidden-file" @change="handleImportFile" />

    <div v-if="loading" class="status-card">正在加载用例列表...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <section class="toolbar-card">
        <div class="toolbar-path">
          <RouterLink :to="`/p/${projectId}/usecases`">ROOT</RouterLink>
          <template v-for="tier in payload.directoryTiers || []" :key="tier.id">
            <span>/</span>
            <RouterLink :to="directoryLink(tier.id)">{{ tier.name }}</RouterLink>
          </template>
        </div>
        <form class="toolbar-actions" @submit.prevent="applyFilters">
          <input v-model="keywordDraft" class="text-input" type="search" placeholder="搜索用例标题..." aria-label="搜索用例" />
          <select v-model="sortDraft" class="select">
            <option value="updateTime">按更新时间</option>
            <option value="name">按名称</option>
          </select>
          <button class="submit-button" type="submit">查询</button>
        </form>
      </section>

      <form v-if="directoryEditorOpen" class="inline-editor" @submit.prevent="submitDirectoryEditor">
        <label class="field inline-field">
          <span>{{ directoryEditorId ? '重命名目录' : '新建目录' }}</span>
          <input v-model.trim="directoryEditorName" class="text-input" type="text" placeholder="请输入目录名称" />
        </label>
        <button class="submit-button" type="submit">保存目录</button>
        <button class="ghost-button" type="button" @click="closeDirectoryEditor">取消</button>
      </form>

      <div class="page-grid">
        <aside class="side-card">
          <div class="card-title">
            <h2>目录</h2>
            <div class="title-actions">
              <span>{{ payload.directories.length }}</span>
              <button v-if="payload.directories.length > directoryPreviewLimit" class="mini-button" type="button" @click="directoriesExpanded = !directoriesExpanded">
                {{ directoriesExpanded ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <div class="dir-list">
            <RouterLink
              class="dir-link root"
              :class="{ active: currentDirectory === 'root' }"
              :to="`/p/${projectId}/usecases`"
            >
              / ROOT
            </RouterLink>
            <RouterLink
              v-for="dir in visibleDirectories"
              :key="dir.id"
              class="dir-link"
              :to="directoryLink(dir.id)"
            >
              <div class="dir-head">
                <strong>{{ dir.name }}</strong>
                <div class="dir-actions">
                  <button type="button" @click.prevent="openDirectoryEditor(dir.id, dir.name)">重命名</button>
                  <button type="button" @click.prevent="deleteDirectory(dir.id, dir.name, dir.parentId || 'root')">删除</button>
                </div>
              </div>
              <span :title="dir.updateTimeText || undefined">{{ dir.updateTimeRelativeText || dir.updateTimeText || '-' }}</span>
            </RouterLink>
          </div>
        </aside>

        <section class="main-card">
          <div class="card-title">
            <h2>{{ payload.currentDirectoryName }}</h2>
            <span>{{ payload.usecases.length }} 个用例</span>
          </div>
          <div v-if="!payload.usecases.length" class="empty-card">当前目录暂无用例</div>
          <div v-else class="list-grid">
            <article v-for="usecase in paginatedUsecases" :key="usecase.id" class="usecase-card">
              <div class="usecase-top">
                <div>
                  <RouterLink class="usecase-title" :to="`/p/${projectId}/usecases/${usecase.id}`">
                    {{ usecase.title }}
                  </RouterLink>
                  <div class="meta-line">
                    维护者 {{ maintainerName(usecase) }} · {{ usecase.updateTimeText || '-' }}
                  </div>
                </div>
                <div class="actions">
                  <RouterLink class="inline-link" :to="`/p/${projectId}/usecases/${usecase.id}/edit`">编辑</RouterLink>
                  <button class="inline-link button-link" type="button" @click="toggleShare(usecase.id, !usecase.share)">
                    {{ usecase.share ? '关闭共享' : '开启共享' }}
                  </button>
                  <a v-if="usecase.share" class="inline-link" :href="`/share/usecase/${usecase.id}`" target="_blank" rel="noreferrer">访问共享页</a>
                  <button class="danger-link" type="button" @click="remove(usecase.id, usecase.title)">删除</button>
                </div>
              </div>
              <div class="tag-row">
                <span class="tag">缺陷 {{ usecase.defects?.length || 0 }}</span>
                <span class="tag">PRD {{ usecase.prdRequirements?.length || 0 }}</span>
              </div>
            </article>
          </div>
          <AppPagination
            v-if="payload.usecases.length > pageSize"
            v-model:page="currentPage"
            v-model:page-size="pageSize"
            :total="payload.usecases.length"
            item-name="个用例"
          />
        </section>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { backendApiUrl } from '@/api/http'
import AppPagination from '@/components/AppPagination.vue'
import { useDialog } from '@/composables/useDialog'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const dialog = useDialog()
const projectId = computed(() => String(route.params.projectId || ''))
const payload = computed(() => projectStore.usecaseListByProjectId[projectId.value])
const currentDirectory = computed(() => String(route.query.directory || 'root'))
const currentKeyword = computed(() => String(route.query.keyword || ''))
const currentSort = computed(() => String(route.query.sort || 'updateTime'))
const keywordDraft = ref('')
const sortDraft = ref('updateTime')
const loading = ref(false)
const error = ref('')
const importInput = ref<HTMLInputElement | null>(null)
const rebuildingSearch = ref(false)
const directoryEditorOpen = ref(false)
const directoryEditorId = ref('')
const directoryEditorName = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const directoriesExpanded = ref(false)
const directoryPreviewLimit = 12

const visibleDirectories = computed(() => {
  const directories = payload.value?.directories || []
  return directoriesExpanded.value ? directories : directories.slice(0, directoryPreviewLimit)
})

const paginatedUsecases = computed(() => {
  const usecases = payload.value?.usecases || []
  const start = (currentPage.value - 1) * pageSize.value
  return usecases.slice(start, start + pageSize.value)
})

const newUsecaseLink = computed(() => {
  const query = currentDirectory.value !== 'root' ? `?directory=${encodeURIComponent(currentDirectory.value)}` : ''
  return `/p/${projectId.value}/usecases/new${query}`
})

const templateDownloadLink = computed(() => backendApiUrl(`/api/projects/${projectId.value}/usecases/template/download`))

const exportLink = computed(() => {
  const query = new URLSearchParams()
  query.set('directory', currentDirectory.value)
  if (currentSort.value) {
    query.set('sort', currentSort.value)
  }
  if (currentKeyword.value) {
    query.set('keyword', currentKeyword.value)
  }
  return backendApiUrl(`/api/projects/${projectId.value}/usecases/export?${query.toString()}`)
})

function directoryLink(directoryId: string) {
  const query = new URLSearchParams()
  query.set('directory', directoryId)
  if (currentSort.value && currentSort.value !== 'updateTime') {
    query.set('sort', currentSort.value)
  }
  if (currentKeyword.value) {
    query.set('keyword', currentKeyword.value)
  }
  return `/p/${projectId.value}/usecases?${query.toString()}`
}

function maintainerName(usecase: { lastUpdateAuthor?: string; authors?: string[] }) {
  if (!payload.value) {
    return '未设置'
  }
  return (
    payload.value.maintainerNameMap[usecase.lastUpdateAuthor || ''] ||
    payload.value.maintainerNameMap[usecase.authors?.[0] || ''] ||
    '未设置'
  )
}

async function load() {
  if (!projectId.value) {
    error.value = '缺少 projectId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadUsecaseList(projectId.value, {
      directory: currentDirectory.value,
      sort: currentSort.value,
      keyword: currentKeyword.value || undefined,
    })
    keywordDraft.value = currentKeyword.value
    sortDraft.value = currentSort.value
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载用例列表失败'
  } finally {
    loading.value = false
  }
}

async function applyFilters() {
  const query: Record<string, string> = {}
  if (currentDirectory.value !== 'root') {
    query.directory = currentDirectory.value
  }
  if (sortDraft.value !== 'updateTime') {
    query.sort = sortDraft.value
  }
  if (keywordDraft.value.trim()) {
    query.keyword = keywordDraft.value.trim()
  }
  await router.push({ name: 'usecase-list', params: { projectId: projectId.value }, query })
}

async function remove(usecaseId: string, title: string) {
  const confirmed = await dialog.confirm({
    title: '删除用例',
    message: `确认删除用例“${title}”？删除后无法恢复。`,
    confirmText: '确认删除',
    tone: 'danger',
  })
  if (!confirmed) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    await projectStore.removeUsecase(projectId.value, usecaseId)
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除用例失败'
  } finally {
    loading.value = false
  }
}

async function toggleShare(usecaseId: string, share: boolean) {
  loading.value = true
  error.value = ''
  try {
    await projectStore.changeUsecaseShare(projectId.value, usecaseId, share)
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '更新共享状态失败'
  } finally {
    loading.value = false
  }
}

function selectImportFile() {
  importInput.value?.click()
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await projectStore.importUsecases(projectId.value, currentDirectory.value, file)
    const successCount = result?.successCount ?? 0
    await dialog.alert({ title: '用例上传成功', message: `已导入 ${successCount} 条用例。`, tone: 'success' })
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '用例上传失败'
  } finally {
    input.value = ''
    loading.value = false
  }
}

async function rebuildSearchData() {
  const confirmed = await dialog.confirm({
    title: '重建检索数据',
    message: '确认重建当前项目的用例检索数据？重建期间搜索结果可能短暂延迟更新。',
    confirmText: '开始重建',
    tone: 'warning',
  })
  if (!confirmed) {
    return
  }
  rebuildingSearch.value = true
  error.value = ''
  try {
    const updated = await projectStore.rebuildUsecaseSearch(projectId.value)
    await dialog.alert({ title: '检索数据已重建', message: `已回填用例检索数据，更新数量：${updated}。`, tone: 'success' })
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '重建检索数据失败'
  } finally {
    rebuildingSearch.value = false
  }
}

function openDirectoryEditor(id = '', currentName = '') {
  directoryEditorId.value = id
  directoryEditorName.value = currentName
  directoryEditorOpen.value = true
}

function closeDirectoryEditor() {
  directoryEditorOpen.value = false
  directoryEditorId.value = ''
  directoryEditorName.value = ''
}

async function submitDirectoryEditor() {
  const name = directoryEditorName.value.trim()
  if (!name) {
    error.value = '目录名称不能为空'
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (directoryEditorId.value) {
      await projectStore.updateUsecaseDirectory(projectId.value, directoryEditorId.value, {
        parentId: currentDirectory.value,
        name,
      })
    } else {
      await projectStore.addUsecaseDirectory(projectId.value, {
        parentId: currentDirectory.value,
        name,
      })
    }
    closeDirectoryEditor()
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存目录失败'
  } finally {
    loading.value = false
  }
}

async function deleteDirectory(directoryId: string, name: string, parentId: string) {
  loading.value = true
  error.value = ''
  try {
    const preview = await projectStore.previewUsecaseDirectoryDelete(projectId.value, directoryId)
    let deleteUsecases = false
    if (preview.requiresCascade) {
      deleteUsecases = await dialog.confirm({
        title: '级联删除目录',
        message: `目录“${name}”下还有 ${preview.directoryCount} 个子目录、${preview.usecaseCount} 个用例。确认级联删除吗？`,
        confirmText: '级联删除',
        tone: 'danger',
      })
      if (!deleteUsecases) {
        loading.value = false
        return
      }
    } else {
      const confirmed = await dialog.confirm({
        title: '删除目录',
        message: `确认删除目录“${name}”？`,
        confirmText: '确认删除',
        tone: 'danger',
      })
      if (!confirmed) {
        loading.value = false
        return
      }
    }
    await projectStore.removeUsecaseDirectory(projectId.value, directoryId, {
      parentId,
      name,
      deleteUsecases,
    })
    await load()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除目录失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => route.fullPath,
  () => {
    currentPage.value = 1
    load()
  },
)

watch(pageSize, () => {
  currentPage.value = 1
})

onMounted(load)
</script>

<style scoped>
.page-header,
.header-actions,
.toolbar-card,
.toolbar-actions,
.card-title,
.title-actions,
.usecase-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.page-header {
  margin-bottom: 20px;
}


.subtext,
.meta-line,
.dir-link span {
  color: #64748b;
}

.action-button,
.submit-button,
.primary-link,
.ghost-button,
.mini-button {
  border-radius: 999px;
  padding: 10px 14px;
}

.button-link {
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  font: inherit;
}

.hidden-file {
  display: none;
}

.action-button,
.submit-button {
  border: none;
  color: #fff;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
}

.submit-button,
.primary-link {
  background: #0f766e;
  color: #fff;
}

.ghost-button,
.mini-button {
  border: 1px solid rgba(15, 118, 110, 0.18);
  background: rgba(15, 118, 110, 0.06);
  color: #0f766e;
  cursor: pointer;
}

.mini-button {
  padding: 5px 9px;
  font-size: 12px;
  font-weight: 800;
}

.title-actions {
  align-items: center;
  gap: 8px;
}

.status-card,
.toolbar-card,
.side-card,
.main-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error,
.danger-link {
  color: #b91c1c;
}

.toolbar-card {
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.inline-editor {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
  padding: 16px;
  border-radius: 18px;
  background: rgba(240, 253, 250, 0.94);
  border: 1px solid rgba(15, 118, 110, 0.16);
}

.field {
  display: grid;
  gap: 8px;
}

.inline-field {
  min-width: min(360px, 100%);
  flex: 1;
}

.toolbar-path {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-actions {
  flex-wrap: wrap;
}

.text-input,
.select {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}

.page-grid {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 18px;
}

.dir-list,
.list-grid {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}

.dir-link,
.usecase-card {
  padding: 14px;
  border-radius: 16px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.dir-link {
  display: grid;
  gap: 4px;
}

.dir-head,
.dir-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.dir-actions button {
  border: none;
  background: transparent;
  color: #0f766e;
  cursor: pointer;
  padding: 0;
  font: inherit;
}

.dir-link.root,
.dir-link.active {
  background: linear-gradient(180deg, #f0fdfa, #ecfeff);
  border-color: rgba(15, 118, 110, 0.22);
}

.empty-card {
  padding: 22px;
  border-radius: 16px;
  background: #f8fafc;
  color: #64748b;
  text-align: center;
}

.usecase-title,
.inline-link {
  color: #0f172a;
  font-weight: 700;
}

.actions,
.tag-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.danger-link {
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
}

.tag {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.09);
  color: #0f766e;
  font-size: 12px;
}

@media (max-width: 960px) {
  .page-grid {
    grid-template-columns: 1fr;
  }

  .page-header,
  .header-actions,
  .toolbar-card,
  .toolbar-actions,
  .usecase-top {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
