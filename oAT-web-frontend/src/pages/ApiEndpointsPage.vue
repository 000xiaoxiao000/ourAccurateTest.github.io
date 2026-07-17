<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">API Discovery</div>
        <h1>接口证据扫描（旧工具）</h1>
        <p class="subtext">辅助从源码包或制品包中提取接口证据；主流程请使用 AI 验证基线。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="secondary-link" :to="`/p/${projectId}/apps/${appId}/settings`">返回源码工程</RouterLink>
        <AppRefreshButton :loading="loading" @click="refreshAll" />
      </div>
    </div>

    <div class="hero-grid">
      <section class="panel upload-panel">
        <div class="panel-head">
          <div>
            <h2>扫描输入</h2>
            <p>优先使用 Git 拉取后的缓存源码 zip，也可以上传 zip / jar / war。</p>
          </div>
          <span class="pill">{{ cachedZips.length }} 个缓存包</span>
        </div>

        <label class="field-label" for="cachedZip">已缓存源码包</label>
        <select id="cachedZip" v-model="selectedCachePath" class="text-input">
          <option value="">不使用缓存包，改为手动上传</option>
          <option v-for="zip in cachedZips" :key="zip.cachePath" :value="zip.cachePath">
            {{ zip.fileName }} · {{ formatFileSize(zip.size) }} · {{ zip.lastModifiedText || formatTime(zip.lastModified) }}
          </option>
        </select>

        <label class="field-label" for="artifactFile">手动上传</label>
        <input id="artifactFile" class="text-input" type="file" accept=".zip,.jar,.war" @change="handleFileChange" />

        <div class="form-actions">
          <button class="action-button" type="button" :disabled="uploading" @click="uploadArtifact">
            {{ uploading ? '扫描中...' : '开始扫描' }}
          </button>
          <span v-if="notice" class="notice">{{ notice }}</span>
        </div>
      </section>

      <section class="panel summary-panel">
        <div class="stat-card dark">
          <strong>{{ endpoints.length }}</strong>
          <span>总接口数</span>
        </div>
        <div class="stat-card teal">
          <strong>{{ filteredEndpoints.length }}</strong>
          <span>筛选结果</span>
        </div>
      </section>
    </div>

    <section class="panel list-panel">
      <div class="toolbar">
        <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索 URL / 类名 / 方法 / 来源" aria-label="搜索接口" />
        <select v-model="endpointType" class="text-input compact">
          <option value="">全部类型</option>
          <option v-for="type in endpointTypes" :key="type" :value="type">{{ type }}</option>
        </select>
        <select v-model="hitRange" class="text-input compact">
          <option value="">全部命中</option>
          <option value="0">仅 0 次</option>
          <option value="1-5">1 - 5 次</option>
          <option value="6-20">6 - 20 次</option>
          <option value="21+">21 次以上</option>
        </select>
      </div>

      <div class="toolbar sub-toolbar">
        <div class="segmented">
          <button :class="{ active: viewMode === 'detail' }" type="button" @click="viewMode = 'detail'">按关联用例</button>
          <button :class="{ active: viewMode === 'group' }" type="button" @click="viewMode = 'group'">按 URL 合并</button>
        </div>
        <button class="ghost-button" type="button" @click="resetFilters">重置筛选</button>
        <button class="ghost-button" type="button" @click="expandAllCurrentView">全部展开</button>
        <button class="ghost-button" type="button" @click="collapseAllCurrentView">全部收起</button>
        <button class="ghost-button" type="button" @click="exportCsv">导出 CSV</button>
        <select v-model.number="pageSize" class="text-input compact" aria-label="每页条数">
          <option :value="10">每页 10 条</option>
          <option :value="20">每页 20 条</option>
          <option :value="50">每页 50 条</option>
        </select>
      </div>

      <div v-if="activeFilterChips.length" class="filter-chips">
        <button v-for="chip in activeFilterChips" :key="chip.key" class="filter-chip" type="button" @click="chip.clear">
          {{ chip.label }}：{{ chip.value }} ×
        </button>
      </div>

      <div v-if="loading" class="status-card">正在加载接口列表...</div>
      <div v-else-if="error" class="status-card error">{{ error }}</div>
      <div v-else-if="pagedItems.length === 0" class="status-card">暂无接口数据，请先扫描源码包或调整筛选条件。</div>

      <template v-else>
        <article v-for="item in pagedItems" :key="item.key" class="endpoint-card">
          <div class="endpoint-main">
            <div>
              <div class="endpoint-url">{{ item.url || '-' }}</div>
              <div class="endpoint-meta">接口键：{{ item.endpointKey }}</div>
              <div class="badges">
                <span>{{ item.endpointType || '-' }}</span>
                <span>{{ item.httpMethod || '-' }}</span>
                <span>命中 {{ item.hitCount || 0 }}</span>
                <span>来源 {{ item.mergedSourceCount || item.sourceCount || 0 }}</span>
              </div>
            </div>
            <button class="text-button" type="button" @click="toggleExpanded(item.key)">
              {{ expandedKeys.has(item.key) ? '收起' : '展开' }}
            </button>
          </div>

          <div v-if="expandedKeys.has(item.key)" class="endpoint-details">
            <div class="copy-actions">
              <button class="ghost-button mini" type="button" @click="copyText(item.endpointKey, '接口键已复制')">复制接口键</button>
              <button class="ghost-button mini" type="button" @click="copyText(item.url || '', 'URL 已复制')">复制 URL</button>
              <button class="ghost-button mini" type="button" @click="copyText(item.methodDescs.join(' / '), '方法签名已复制')">复制方法签名</button>
            </div>
            <InfoLine label="类名" :value="item.classNames" />
            <InfoLine label="方法" :value="item.methodNames" />
            <InfoLine label="描述" :value="item.methodDescs" />
            <InfoLine label="来源" :value="item.sourceNames" />
            <div class="usecase-links">
              <span>关联用例：</span>
              <span
                v-for="usecase in item.linkedUsecases"
                :key="usecase.id"
                class="mini-link"
              >
                {{ usecase.title || usecase.id }}
              </span>
              <span v-if="!item.linkedUsecases.length" class="muted">暂无</span>
            </div>
          </div>
        </article>

        <AppPagination v-model:page="currentPage" v-model:page-size="pageSize" :total="filteredEndpoints.length" item-name="个接口" />
      </template>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { apiGetRaw, apiPost } from '@/api/http'
import type { ApiEndpointItem, PulledZipItem } from '@/api/types'
import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'

interface DisplayEndpoint {
  key: string
  endpointKey: string
  endpointType?: string
  url?: string
  httpMethod?: string
  hitCount: number
  mergedSourceCount: number
  sourceCount: number
  classNames: string[]
  methodNames: string[]
  methodDescs: string[]
  sourceNames: string[]
  linkedUsecases: NonNullable<ApiEndpointItem['linkedUsecases']>
}

const InfoLine = defineComponent({
  props: {
    label: { type: String, required: true },
    value: { type: Array<string>, required: true },
  },
  setup(props) {
    return () => h('div', { class: 'info-line' }, [
      h('span', props.label + '：'),
      h('strong', props.value.length ? props.value.join(' / ') : '-'),
    ])
  },
})

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const appId = computed(() => String(route.params.appId || ''))

const cachedZips = ref<PulledZipItem[]>([])
const endpoints = ref<ApiEndpointItem[]>([])
const selectedCachePath = ref('')
const selectedFile = ref<File | null>(null)
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')
const keyword = ref('')
const endpointType = ref('')
const hitRange = ref('')
const viewMode = ref<'detail' | 'group'>('detail')
const currentPage = ref(1)
const pageSize = ref(10)
const expandedKeys = ref(new Set<string>())
const storageKey = computed(() => `api-endpoints-view-state-${projectId.value}-${appId.value}`)

const endpointTypes = computed(() => unique(endpoints.value.map((item) => item.endpointType)))
const activeFilterChips = computed(() => {
  const chips: Array<{ key: string; label: string; value: string; clear: () => void }> = []
  if (keyword.value) chips.push({ key: 'keyword', label: '关键字', value: keyword.value, clear: () => { keyword.value = '' } })
  if (endpointType.value) chips.push({ key: 'endpointType', label: '接口类型', value: endpointType.value, clear: () => { endpointType.value = '' } })
  if (hitRange.value) chips.push({ key: 'hitRange', label: '命中次数', value: hitRange.value, clear: () => { hitRange.value = '' } })
  chips.push({ key: 'viewMode', label: '视图', value: viewMode.value === 'group' ? '按 URL 合并' : '按关联用例', clear: () => { viewMode.value = 'detail' } })
  return chips
})

const filteredEndpoints = computed(() => {
  const source = viewMode.value === 'group' ? groupEndpoints(endpoints.value) : endpoints.value.map(toDisplayEndpoint)
  const needle = keyword.value.toLowerCase()
  return source
    .filter((item) => !endpointType.value || item.endpointType === endpointType.value || item.endpointType?.includes(endpointType.value))
    .filter((item) => matchesHitRange(item.hitCount, hitRange.value))
    .filter((item) => {
      if (!needle) return true
      return [item.url, item.endpointType, item.httpMethod, ...item.classNames, ...item.methodNames, ...item.methodDescs, ...item.sourceNames]
        .join(' ')
        .toLowerCase()
        .includes(needle)
    })
    .sort(sortEndpoint)
})

const pagedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredEndpoints.value.slice(start, start + pageSize.value)
})

watch([keyword, endpointType, hitRange, viewMode, pageSize], () => {
  currentPage.value = 1
  persistViewState()
})

watch(currentPage, persistViewState)

watch(pagedItems, (items) => {
  const next = new Set(expandedKeys.value)
  items.slice(0, 8).forEach((item) => next.add(item.key))
  expandedKeys.value = next
})

function endpointBase() {
  return `/api/projects/${projectId.value}/apps/${appId.value}/api-endpoints`
}

async function refreshAll() {
  await Promise.all([loadCachedZips(), loadEndpoints()])
}

async function loadCachedZips() {
  cachedZips.value = await apiGetRaw<PulledZipItem[]>(`${endpointBase()}/pulled-zips`)
}

async function loadEndpoints() {
  loading.value = true
  error.value = ''
  try {
    endpoints.value = await apiGetRaw<ApiEndpointItem[]>(`${endpointBase()}/list`)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载接口列表失败'
  } finally {
    loading.value = false
  }
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] || null
  if (selectedFile.value) {
    selectedCachePath.value = ''
  }
}

async function uploadArtifact() {
  if (!selectedCachePath.value && !selectedFile.value) {
    notice.value = '请先选择缓存源码包或上传文件'
    return
  }
  uploading.value = true
  notice.value = ''
  try {
    const form = new FormData()
    if (selectedCachePath.value) form.set('cachePath', selectedCachePath.value)
    if (selectedFile.value) form.set('file', selectedFile.value)
    await apiPost<string>(`${endpointBase()}/upload`, form)
    notice.value = '接口证据扫描完成'
    await loadEndpoints()
  } catch (err) {
    notice.value = err instanceof Error ? err.message : '接口证据扫描失败'
  } finally {
    uploading.value = false
  }
}

function toDisplayEndpoint(item: ApiEndpointItem): DisplayEndpoint {
  const endpointKey = buildEndpointKey(item)
  return {
    key: item.id || endpointKey,
    endpointKey,
    endpointType: item.endpointType,
    url: item.url,
    httpMethod: item.httpMethod,
    hitCount: Number(item.hitCount || 0),
    mergedSourceCount: Number(item.mergedSourceCount || 0),
    sourceCount: 1,
    classNames: unique([...(item.classNameList || []), item.className]),
    methodNames: unique([...(item.methodNameList || []), item.methodName]),
    methodDescs: unique([...(item.methodDescList || []), item.methodDesc]),
    sourceNames: unique([...(item.sourceNameList || []), item.sourceName]),
    linkedUsecases: item.linkedUsecases || [],
  }
}

function groupEndpoints(items: ApiEndpointItem[]) {
  const groups = new Map<string, ApiEndpointItem[]>()
  items.forEach((item) => {
    const key = `${item.url || '-'}|${item.httpMethod || '-'}|${item.endpointType || '-'}`
    groups.set(key, [...(groups.get(key) || []), item])
  })
  return Array.from(groups.entries()).map(([key, group]) => {
    const first = group[0]
    const linkedUsecases = uniqueUsecases(group.flatMap((item) => item.linkedUsecases || []))
    return {
      key,
      endpointKey: key.split('|').join(' | '),
      endpointType: unique(group.map((item) => item.endpointType)).join(' / '),
      url: first.url,
      httpMethod: unique(group.map((item) => item.httpMethod)).join(' / '),
      hitCount: group.reduce((sum, item) => sum + Number(item.hitCount || 0), 0),
      mergedSourceCount: group.reduce((sum, item) => sum + Number(item.mergedSourceCount || 0), 0),
      sourceCount: group.length,
      classNames: unique(group.flatMap((item) => [...(item.classNameList || []), item.className])),
      methodNames: unique(group.flatMap((item) => [...(item.methodNameList || []), item.methodName])),
      methodDescs: unique(group.flatMap((item) => [...(item.methodDescList || []), item.methodDesc])),
      sourceNames: unique(group.flatMap((item) => [...(item.sourceNameList || []), item.sourceName])),
      linkedUsecases,
    }
  })
}

function sortEndpoint(a: DisplayEndpoint, b: DisplayEndpoint) {
  if (viewMode.value === 'detail') {
    const linkedDiff = Number(Boolean(b.linkedUsecases.length)) - Number(Boolean(a.linkedUsecases.length))
    if (linkedDiff !== 0) return linkedDiff
  }
  if (a.hitCount !== b.hitCount) return b.hitCount - a.hitCount
  return (a.url || '').localeCompare(b.url || '')
}

function buildEndpointKey(item: ApiEndpointItem) {
  return item.endpointKeyParts?.length ? item.endpointKeyParts.join(' | ') : [item.endpointType, item.httpMethod, item.url].filter(Boolean).join(' | ')
}

function matchesHitRange(hitCount: number, range: string) {
  if (!range) return true
  if (range === '0') return hitCount === 0
  if (range === '1-5') return hitCount >= 1 && hitCount <= 5
  if (range === '6-20') return hitCount >= 6 && hitCount <= 20
  if (range === '21+') return hitCount >= 21
  return true
}

function toggleExpanded(key: string) {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedKeys.value = next
  persistViewState()
}

function expandAllCurrentView() {
  expandedKeys.value = new Set(filteredEndpoints.value.map((item) => item.key))
  persistViewState()
}

function collapseAllCurrentView() {
  expandedKeys.value = new Set()
  persistViewState()
}

function resetFilters() {
  keyword.value = ''
  endpointType.value = ''
  hitRange.value = ''
}

async function copyText(text: string, successMessage = '已复制') {
  if (!text) {
    notice.value = '没有可复制的内容'
    return
  }
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
  } else {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  notice.value = successMessage
}

function exportCsv() {
  const rows = [['接口类型', 'HTTP 方法', 'URL', '命中次数', '类名', '方法', '关联用例']]
  filteredEndpoints.value.forEach((item) => {
    rows.push([
      item.endpointType || '',
      item.httpMethod || '',
      item.url || '',
      String(item.hitCount || 0),
      item.classNames.join(' / '),
      item.methodNames.join(' / '),
      item.linkedUsecases.map((usecase) => usecase.title || usecase.id).join(' / '),
    ])
  })
  const csv = rows.map((row) => row.map((cell) => `"${cell.split('"').join('""')}"`).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `api-endpoints-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}

function unique(values: Array<string | undefined | null>) {
  return Array.from(new Set(values.filter((value): value is string => Boolean(value && value.trim()))))
}

function uniqueUsecases(usecases: NonNullable<ApiEndpointItem['linkedUsecases']>) {
  const map = new Map<string, NonNullable<ApiEndpointItem['linkedUsecases']>[number]>()
  usecases.forEach((usecase) => {
    if (usecase.id) map.set(usecase.id, usecase)
  })
  return Array.from(map.values())
}

function formatFileSize(size?: number) {
  if (!size) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(time?: number) {
  return time ? new Date(time).toLocaleString() : '-'
}

function persistViewState() {
  if (!projectId.value || !appId.value) return
  localStorage.setItem(storageKey.value, JSON.stringify({
    keyword: keyword.value,
    endpointType: endpointType.value,
    hitRange: hitRange.value,
    viewMode: viewMode.value,
    currentPage: currentPage.value,
    pageSize: pageSize.value,
    expandedKeys: Array.from(expandedKeys.value).slice(0, 500),
  }))
}
function restoreViewState() {
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return
    const state = JSON.parse(raw) as { keyword?: string; endpointType?: string; hitRange?: string; viewMode?: 'detail' | 'group'; currentPage?: number; pageSize?: number; expandedKeys?: string[] }
    keyword.value = state.keyword || ''
    endpointType.value = state.endpointType || ''
    hitRange.value = state.hitRange || ''
    viewMode.value = state.viewMode === 'group' ? 'group' : 'detail'
    currentPage.value = state.currentPage || 1
    pageSize.value = state.pageSize && state.pageSize > 0 ? state.pageSize : 10
    expandedKeys.value = new Set(state.expandedKeys || [])
  } catch {
  }
}

onMounted(() => {
  restoreViewState()
  refreshAll()
})
</script>

<style scoped>
.page-header,
.header-actions,
.panel-head,
.endpoint-main,
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.page-header {
  margin-bottom: 20px;
}

.eyebrow {
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.subtext,
.panel-head p,
.endpoint-meta,
.muted {
  color: #64748b;
}

.header-actions,
.toolbar,
.form-actions,
.badges,
.usecase-links {
  flex-wrap: wrap;
}

.secondary-link,
.text-button,
.mini-link {
  color: #0f766e;
  font-weight: 800;
}

.action-button,
.ghost-button,
.segmented button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
  color: #fff;
}

.action-button:disabled,
.ghost-button:disabled {
  opacity: .45;
  cursor: not-allowed;
}

.ghost-button,
.segmented button {
  background: #eef7f7;
  color: #0f766e;
}

.segmented {
  display: inline-flex;
  padding: 4px;
  border-radius: 999px;
  background: #e2edf0;
}

.segmented button.active {
  background: #0f766e;
  color: #fff;
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(300px, .75fr);
  gap: 16px;
  margin-bottom: 16px;
}

.panel,
.status-card,
.endpoint-card {
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 22px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 18px 42px rgba(15, 23, 42, .06);
}

.panel,
.status-card {
  padding: 20px;
}

.status-card.error {
  color: #b91c1c;
}

.upload-panel {
  background:
    radial-gradient(circle at top right, rgba(20, 184, 166, .16), transparent 34%),
    rgba(255, 255, 255, .95);
}

.field-label {
  display: block;
  margin: 14px 0 6px;
  font-weight: 800;
}

.text-input {
  width: 100%;
  border: 1px solid #d9e5ea;
  border-radius: 14px;
  padding: 11px 12px;
  background: #fbfdfe;
  color: #0f172a;
}

.text-input.compact {
  width: min(190px, 100%);
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.notice {
  color: #0f766e;
  font-weight: 700;
}

.pill,
.badges span {
  border-radius: 999px;
  background: #e6f4f1;
  color: #0f766e;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 800;
}

.summary-panel {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.stat-card {
  padding: 18px;
  border-radius: 20px;
  background: #f8fafc;
}

.stat-card strong {
  display: block;
  font-size: 32px;
}

.stat-card.blue strong { color: #2563eb; }
.stat-card.gray strong { color: #64748b; }
.stat-card.dark strong { color: #0f172a; }
.stat-card.teal strong { color: #0f766e; }

.list-panel {
  display: grid;
  gap: 14px;
}

.sub-toolbar {
  justify-content: flex-start;
}

.filter-chips,
.copy-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-chip {
  border: 1px solid rgba(15, 118, 110, .24);
  border-radius: 999px;
  padding: 6px 10px;
  background: #f0fdfa;
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.ghost-button.mini {
  padding: 6px 10px;
  font-size: 12px;
}

.endpoint-card {
  padding: 16px;
}

.endpoint-url {
  color: #0f172a;
  font-size: 17px;
  font-weight: 900;
  word-break: break-all;
}

.badges,
.usecase-links {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.endpoint-details {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid rgba(15, 23, 42, .1);
}

.info-line {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 8px;
  color: #64748b;
}

.info-line strong {
  color: #1f2937;
  word-break: break-all;
}

@media (max-width: 900px) {
  .hero-grid,
  .summary-panel {
    grid-template-columns: 1fr;
  }

  .page-header,
  .endpoint-main {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
