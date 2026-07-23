<template>
  <section>
    <div class="page-header report-hero">
      <div class="hero-copy">
        <div class="eyebrow">Compare Report</div>
        <h1>{{ reportTitle }}</h1>
        <p class="subtext">{{ payload?.app?.name || '版本比对报告' }} · {{ payload?.report?.createTimeText || '生成中' }}</p>
        <div v-if="payload?.report" class="hero-tags">
          <span v-if="payload.report.gitBranch">分支：{{ payload.report.gitBranch }}</span>
          <span>{{ isGitReport ? 'Git 版本比对' : '制品包比对' }}</span>
          <span>{{ totalDiffCount }} 项变更</span>
          <span>影响用例 {{ displayUsecaseCount }}</span>
          <span>影响接口 {{ endpointCount }}</span>
        </div>
      </div>
      <div class="header-actions">
        <RouterLink v-if="appId" class="secondary-link" :to="`/p/${projectId}/apps/${appId}/compare`">返回版本比对</RouterLink>
      </div>
    </div>

    <div v-if="loading && !payload" class="status-card">正在加载比对报告...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <div v-else-if="payload?.state === 'pending'" class="pending-card">
      <span class="pending-dot"></span>
      <h2>比对报告生成中</h2>
      <p>{{ payload.retryMessage || '报告正在生成或索引刷新中，页面会自动重试。' }}</p>
      <small>第 {{ pendingRetryCount }} / {{ maxPendingRetries }} 次重试</small>
      <RouterLink v-if="appId && pendingRetryCount >= maxPendingRetries" class="primary-button" :to="`/p/${projectId}/apps/${appId}/compare`">返回比对中心</RouterLink>
    </div>

    <template v-else-if="payload?.report">
      <div class="hero-grid">
        <article class="hero-card accent-class">
          <span>类变更</span>
          <strong>{{ classDiffCount }}</strong>
          <small>新增 {{ payload.report.addClassCount }} / 修改 {{ payload.report.updateClassCount }} / 删除 {{ payload.report.deleteClassCount }}</small>
        </article>
        <article class="hero-card accent-method">
          <span>方法变更</span>
          <strong>{{ methodDiffCount }}</strong>
          <small>新增 {{ payload.report.addMethodCount }} / 修改 {{ payload.report.updateMethodCount }} / 删除 {{ payload.report.deleteMethodCount }}</small>
        </article>
        <article class="hero-card accent-usecase">
          <span>影响用例</span>
          <strong>{{ displayUsecaseCount }}</strong>
          <small>{{ impactHintText }}</small>
        </article>
        <article class="hero-card accent-endpoint">
          <span>影响接口</span>
          <strong>{{ endpointCount }}</strong>
          <small>基于旧接口依据扫描与变更类/方法匹配</small>
        </article>
      </div>

      <section class="panel info-panel">
        <div class="panel-head">
          <h2>报告元信息</h2>
        </div>
        <div class="revision-flow">
          <article class="revision-card old">
            <span>{{ oldRevisionLabel }}</span>
            <strong :title="oldRevisionValue">{{ shortText(oldRevisionValue) }}</strong>
            <small>{{ isGitReport ? '基线版本 / oldCommit' : '基线制品 / 旧版本文件' }}</small>
          </article>
          <div class="revision-arrow" aria-hidden="true">→</div>
          <article class="revision-card new">
            <span>{{ newRevisionLabel }}</span>
            <strong :title="newRevisionValue">{{ shortText(newRevisionValue) }}</strong>
            <small>{{ isGitReport ? '目标版本 / newCommit' : '目标制品 / 新版本文件' }}</small>
          </article>
        </div>
        <div class="info-grid compact-info-grid">
          <div v-if="payload.report.gitBranch" class="info-item"><span>分支</span><strong>{{ payload.report.gitBranch }}</strong></div>
          <div class="info-item"><span>报告类型</span><strong>{{ isGitReport ? 'Git 版本比对' : '制品包比对' }}</strong></div>
          <div class="info-item"><span>生成时间</span><strong>{{ payload.report.createTimeText || '-' }}</strong></div>
          <div class="info-item"><span>说明</span><strong>{{ versionMetaNote }}</strong></div>
        </div>
      </section>

      <nav class="report-section-nav" aria-label="报告内容导航">
        <a href="#report-differences">变更项 {{ filteredDifferences.length }}</a>
        <a href="#report-usecases">影响用例 {{ displayUsecaseCount }}</a>
        <a href="#report-endpoints">影响接口 {{ endpointCount }}</a>
        <a href="#report-logs">比对日志 {{ logGroups.length }}</a>
        <button type="button" @click="differencePage = 1">回到首组变更</button>
      </nav>

      <section id="report-differences" class="panel toolbar-panel">
        <div>
          <h2>变更项</h2>
          <p class="subtext">按老前端的类/方法结构展示，支持搜索和新增、修改、删除筛选；默认收起长列表以便快速查看重点。</p>
        </div>
        <div class="filter-actions">
          <input v-model.trim="keyword" class="text-input" type="search" placeholder="搜索类名、方法名或描述" aria-label="搜索变更项" />
          <button v-for="mode in diffModes" :key="mode.value" :class="['filter-chip', diffMode === mode.value && 'active']" type="button" @click="diffMode = mode.value">{{ mode.label }}</button>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>差异清单</h2>
          <div class="panel-head-actions">
            <span>{{ paginatedDifferences.length }} / {{ filteredDifferences.length }}</span>
          </div>
        </div>
        <div class="diff-list compact-list">
          <article v-for="item in paginatedDifferences" :key="item.className" class="diff-card" :class="[modelClass(item.model), selectedClass === item.className && 'selected']">
            <button class="diff-top" type="button" @click="toggleClass(item.className)">
              <span class="class-name"><i :class="modelIconClass(item.model)"></i>{{ item.className }}</span>
              <span :class="['tag', modelClass(item.model)]">{{ modelText(item.model) }}</span>
            </button>
            <div v-show="isClassOpen(item.className)" class="method-list compact-methods">
              <button v-for="method in item.methods" :key="`${method.methodName}-${method.methodDesc}`" class="method-item" :class="modelClass(method.model || item.model)" type="button" @click="selectedClass = item.className">
                <span><i :class="modelIconClass(method.model || item.model)"></i>{{ method.methodName }}</span>
                <small>{{ method.methodDesc ? `行: ${method.methodDesc}` : '方法明细' }}</small>
              </button>
              <div v-if="!item.methods.length" class="empty-inline">类级变更，暂无方法明细</div>
            </div>
          </article>
          <div v-if="!filteredDifferences.length" class="empty-card">暂无匹配差异</div>
        </div>
        <AppPagination
          v-if="filteredDifferences.length > 0"
          v-model:page="differencePage"
          v-model:page-size="differencePageSize"
          :total="filteredDifferences.length"
          item-name="变更"
          :page-sizes="[6, 12, 24, 48]"
        />
      </section>

      <section class="impact-grid">
        <div id="report-usecases" class="panel">
          <div class="panel-head"><h2>影响用例</h2><span>{{ displayUsecaseCount }}</span></div>
          <div v-if="usecaseDisplayMismatch" class="info-message">
            报告原始命中 {{ payload.report.impactCaseCount }} 条，当前可展示详情 {{ payload.usecases?.length || 0 }} 条；未展示的通常是历史报告未保存用例明细、用例已删除或索引未刷新，可展开比对日志核对命中用例 ID。
          </div>
          <div v-if="showImpactHints" class="impact-hint-card">
            <button class="impact-hint-toggle" type="button" @click="impactHintOpen = !impactHintOpen">
              <span><strong>当前未命中影响用例，点击查看排查建议</strong><small>常见原因是用例关联不足，或现有用例未覆盖本次变更类/方法。</small></span>
              <span :class="['caret', impactHintOpen && 'open']">⌄</span>
            </button>
            <div v-if="impactHintOpen" class="impact-hint-body">
              <div v-if="payload.impactHints?.zeroHitClasses?.length" class="hint-line">未命中的类：{{ payload.impactHints.zeroHitClasses.join('、') }}</div>
              <div class="hint-line">先展开下方任务日志，查看每个变更类对应的命中用例信息。</div>
              <div class="hint-line">优先补充本次改动相关入口，例如对应 Controller / Service 的用例，再重新发起比对。</div>
            </div>
          </div>
          <div class="usecase-groups">
            <article v-for="group in usecaseGroups" :key="group.directory" class="usecase-group">
              <button class="group-title" type="button" @click="toggleUsecaseGroup(group.directory)">
                <span>▾ {{ group.directory }}</span>
                <small>{{ group.items.length }} 条</small>
              </button>
              <div v-show="openUsecaseGroups.has(group.directory)" class="impact-list">
                <article v-for="item in paginatedUsecaseGroupItems(group.items)" :key="item.id" class="impact-card usecase-card">
                  <div class="impact-top">
                    <span v-if="item.available !== false" class="result-link">{{ item.title }}</span>
                    <span v-else class="result-link unavailable">{{ item.title }}</span>
                    <button class="inline-link" type="button" @click="toggleUsecase(item.id)">影响点：{{ item.differences.length }}</button>
                  </div>
                  <div v-if="item.labels?.length" class="label-list">
                    <span v-for="label in item.labels" :key="label">{{ label }}</span>
                  </div>
                  <div v-show="openUsecases.has(item.id)" class="difference-points">
                    <span v-for="difference in item.differences" :key="difference">{{ difference }}</span>
                    <span v-if="!item.differences.length">暂无影响点明细</span>
                  </div>
                </article>
              </div>
            </article>
            <AppPagination
              v-if="flatUsecases.length > 0"
              v-model:page="usecasePage"
              v-model:page-size="usecasePageSize"
              :total="flatUsecases.length"
              item-name="用例"
              :page-sizes="[5, 10, 20, 50]"
            />
            <div v-if="!usecaseGroups.length" class="empty-card">
              {{ usecaseDetailsMissing ? `报告记录了 ${displayUsecaseCount} 条影响用例，但未能加载用例详情；请展开比对日志查看命中用例 ID，重新生成报告后会保存完整影响用例。` : '未发现影响用例。' }}
            </div>
          </div>
        </div>

        <div id="report-endpoints" class="panel">
          <div class="panel-head"><h2>影响接口</h2><span>{{ endpointCount }}</span></div>
          <div class="impact-list endpoint-list">
            <article v-for="endpoint in paginatedEndpoints" :key="endpoint.id || `${endpoint.endpointType}-${endpoint.url}-${endpoint.methodName}`" class="impact-card endpoint-card">
              <div class="impact-top endpoint-top">
                <span class="method-badge">{{ endpoint.httpMethod || endpoint.endpointType || 'API' }}</span>
                <strong>{{ endpoint.url || endpoint.methodName || '-' }}</strong>
              </div>
              <p>{{ endpoint.className || '-' }}{{ endpoint.methodName ? `#${endpoint.methodName}` : '' }}</p>
              <div class="endpoint-state">
                <span>命中 {{ endpoint.hitCount || 0 }}</span>
              </div>
              <div class="meta-list">
                <span v-for="name in endpoint.matchedClasses" :key="name">{{ name }}</span>
                <span v-for="name in endpoint.matchedMethods" :key="`m-${name}`">{{ name }}</span>
              </div>
              <div v-if="endpoint.linkedUsecases?.length" class="linked-usecases">
                <strong>关联用例</strong>
                <span v-for="usecase in endpoint.linkedUsecases" :key="usecase.id">
                  {{ usecase.title || usecase.id }}<small v-if="usecase.directory">{{ usecase.directory }}</small>
                </span>
              </div>
              <div v-else class="linked-usecases muted">暂无接口关联用例</div>
            </article>
            <div v-if="!payload.endpoints?.length" class="empty-card">未匹配到影响接口依据。建议优先使用 AI 验证基线确认需求、用例和源码关系。</div>
          </div>
          <AppPagination
            v-if="endpointCount > 0"
            v-model:page="endpointPage"
            v-model:page-size="endpointPageSize"
            :total="endpointCount"
            item-name="接口"
            :page-sizes="[4, 8, 16, 32]"
          />
        </div>
      </section>

      <section id="report-logs" class="panel log-panel">
        <div class="panel-head">
          <div>
            <h2>比对日志</h2>
            <p class="subtext">按老前端控制台分组：变更发现、比对汇总、影响分析、运行日志。</p>
          </div>
          <div class="log-actions">
            <button class="ghost-button small" type="button" @click="logCollapsed = !logCollapsed">{{ logCollapsed ? '展开日志' : '收起日志' }}</button>
            <button class="ghost-button small" type="button" :disabled="!payload.report.jobLog" @click="copyLog">复制日志</button>
          </div>
        </div>
        <div v-show="!logCollapsed" class="report-log">
          <article v-for="group in logGroups" :key="group.name" class="log-group">
            <div class="log-group-head"><strong>{{ group.name }}</strong><span>{{ group.lines.length }}</span></div>
            <div class="log-group-body">
              <div v-for="(line, index) in group.lines" :key="`${group.name}-${index}-${line.raw}`" class="log-line">
                <span :class="['log-marker', line.type]"></span>
                <span class="log-time">{{ line.time }}</span>
                <span :class="['log-tag', line.type]">{{ logTypeText(line.type) }}</span>
                <span class="log-text">{{ line.content }}</span>
              </div>
            </div>
          </article>
          <div v-if="!logGroups.length" class="empty-inline">暂无任务日志</div>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppPagination from '@/components/AppPagination.vue'
import { fetchCompareReport } from '@/api/bootstrap'
import type { DifferenceGroupSummary, UsecaseImpactSummary, VersionReportDetailPayload } from '@/api/types'

type LogLine = { raw: string; time: string; content: string; type: string }
type LogGroup = { name: string; lines: LogLine[] }

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const reportId = computed(() => String(route.params.reportId || ''))
const appId = computed(() => String(route.query.appId || payload.value?.app?.id || ''))
const payload = ref<VersionReportDetailPayload | null>(null)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const diffMode = ref('all')
const selectedClass = ref('')
const openClasses = ref(new Set<string>())
const openUsecaseGroups = ref(new Set<string>())
const openUsecases = ref(new Set<string>())
const impactHintOpen = ref(false)
const logCollapsed = ref(true)
const showAllDifferences = ref(false)
const differencePage = ref(1)
const differencePageSize = ref(6)
const usecasePage = ref(1)
const usecasePageSize = ref(5)
const endpointPage = ref(1)
const endpointPageSize = ref(4)
const pendingRetryCount = ref(0)
const maxPendingRetries = 5
const differencePreviewLimit = 30
let pendingTimer: number | undefined

const diffModes = [
  { value: 'all', label: '全部' },
  { value: 'add', label: '新增' },
  { value: 'update', label: '修改' },
  { value: 'delete', label: '删除' },
]

const reportTitle = computed(() => compactReportTitle(payload.value?.report?.jobName || reportId.value))
const isGitReport = computed(() => Boolean(payload.value?.report?.gitBranch || payload.value?.report?.gitOldCommit || payload.value?.report?.gitNewCommit))
const oldRevisionValue = computed(() => payload.value?.report?.gitOldCommit || payload.value?.report?.sourceVersion || '')
const newRevisionValue = computed(() => payload.value?.report?.gitNewCommit || payload.value?.report?.targetVersion || '')
const oldRevisionLabel = computed(() => (isGitReport.value ? '旧 Commit' : '旧版本'))
const newRevisionLabel = computed(() => (isGitReport.value ? '新 Commit' : '新版本'))
const versionMetaNote = computed(() => (isGitReport.value ? '旧/新 Commit 与旧/新版本为同一组 Git ref' : '旧/新版本对应制品包比对方向'))
const classDiffCount = computed(() => {
  const report = payload.value?.report
  return report ? report.addClassCount + report.updateClassCount + report.deleteClassCount : 0
})
const methodDiffCount = computed(() => {
  const report = payload.value?.report
  return report ? report.addMethodCount + report.updateMethodCount + report.deleteMethodCount : 0
})
const totalDiffCount = computed(() => classDiffCount.value + methodDiffCount.value)
const endpointCount = computed(() => payload.value?.endpoints?.length || 0)
const displayUsecaseCount = computed(() => Math.max(payload.value?.usecases?.length || 0, payload.value?.report?.impactCaseCount || 0))
const usecaseDetailsMissing = computed(() => (payload.value?.report?.impactCaseCount || 0) > 0 && !(payload.value?.usecases?.length))
const usecaseDisplayMismatch = computed(() => {
  const reportCount = payload.value?.report?.impactCaseCount || 0
  const displayedCount = payload.value?.usecases?.length || 0
  return reportCount > 0 && displayedCount !== reportCount
})
const impactHintText = computed(() => {
  const hints = payload.value?.impactHints
  if (!hints) return '基于变更与用例关联分析'
  return '基于变更与用例关联分析'
})
const showImpactHints = computed(() => displayUsecaseCount.value === 0)
const filteredDifferences = computed(() => {
  const needle = keyword.value.toLowerCase()
  return (payload.value?.differences || []).filter((item) => {
    if (diffMode.value !== 'all' && item.model !== diffMode.value) return false
    if (!needle) return true
    return [item.className, item.model, ...item.methods.flatMap((method) => [method.methodName, method.methodDesc || ''])]
      .join(' ')
      .toLowerCase()
      .includes(needle)
  })
})
const visibleDifferences = computed(() => filteredDifferences.value)
const paginatedDifferences = computed(() => {
  const start = (differencePage.value - 1) * differencePageSize.value
  return filteredDifferences.value.slice(start, start + differencePageSize.value)
})
const flatUsecases = computed(() => payload.value?.usecases || [])
const paginatedFlatUsecases = computed(() => {
  const start = (usecasePage.value - 1) * usecasePageSize.value
  return flatUsecases.value.slice(start, start + usecasePageSize.value)
})
const paginatedEndpointList = computed(() => {
  const endpoints = payload.value?.endpoints || []
  const start = (endpointPage.value - 1) * endpointPageSize.value
  return endpoints.slice(start, start + endpointPageSize.value)
})
const paginatedEndpoints = computed(() => paginatedEndpointList.value)
const usecaseGroups = computed(() => {
  const groups = new Map<string, UsecaseImpactSummary[]>()
  for (const item of paginatedFlatUsecases.value) {
    const directory = item.directoryPath || 'ROOT'
    const list = groups.get(directory) || []
    list.push(item)
    groups.set(directory, list)
  }
  return Array.from(groups.entries()).map(([directory, items]) => ({ directory, items }))
})
const jobLogLines = computed(() => sanitizeJobLog(payload.value?.report?.jobLog || ''))
const logGroups = computed(() => groupLogLines(jobLogLines.value))

watch(() => payload.value?.differences, (differences) => {
  openClasses.value = new Set((differences || []).slice(0, 6).map((item) => item.className).filter(Boolean))
  showAllDifferences.value = false
}, { immediate: true })

watch([keyword, () => diffMode.value], () => {
  differencePage.value = 1
  showAllDifferences.value = false
})

watch(differencePageSize, () => {
  differencePage.value = 1
})

watch(usecasePageSize, () => {
  usecasePage.value = 1
})

watch(endpointPageSize, () => {
  endpointPage.value = 1
})


watch(() => filteredDifferences.value.length, (total) => {
  const totalPages = Math.max(1, Math.ceil(total / differencePageSize.value))
  if (differencePage.value > totalPages) differencePage.value = totalPages
})

watch(() => flatUsecases.value.length, (total) => {
  const totalPages = Math.max(1, Math.ceil(total / usecasePageSize.value))
  if (usecasePage.value > totalPages) usecasePage.value = totalPages
})

watch(endpointCount, (total) => {
  const totalPages = Math.max(1, Math.ceil(total / endpointPageSize.value))
  if (endpointPage.value > totalPages) endpointPage.value = totalPages
})


watch(usecaseGroups, (groups) => {
  openUsecaseGroups.value = new Set(groups.map((group) => group.directory))
}, { immediate: true })

async function load() {
  loading.value = true
  error.value = ''
  clearPendingTimer()
  try {
    payload.value = await fetchCompareReport(projectId.value, reportId.value)
    if (payload.value.state === 'pending' && pendingRetryCount.value < maxPendingRetries) {
      pendingRetryCount.value += 1
      pendingTimer = window.setTimeout(load, 1200)
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载比对报告失败'
  } finally {
    loading.value = false
  }
}

function compactReportTitle(value: string) {
  return value
    .replace(/git-([^\s/]{8})[^\s/]*\.zip/g, 'git-$1...zip')
    .replace(/([a-f0-9]{10})[a-f0-9]{20,}/gi, '$1...')
}

function shortText(value?: string) {
  if (!value) return '-'
  return value.length > 18 ? `${value.slice(0, 12)}...${value.slice(-6)}` : value
}

function modelText(model?: string) {
  if (model === 'add') return '新增'
  if (model === 'delete') return '删除'
  return '修改'
}

function modelClass(model?: string) {
  return model === 'add' ? 'model-add' : model === 'delete' ? 'model-delete' : 'model-update'
}

function modelIconClass(model?: string) {
  return model === 'add' ? 'icon-add' : model === 'delete' ? 'icon-delete' : 'icon-update'
}

function isClassOpen(className: string) {
  return openClasses.value.has(className)
}

function toggleClass(className: string) {
  selectedClass.value = className
  const next = new Set(openClasses.value)
  if (next.has(className)) next.delete(className)
  else next.add(className)
  openClasses.value = next
}

function paginatedUsecaseGroupItems(items: UsecaseImpactSummary[]) {
  const visibleIds = new Set(paginatedFlatUsecases.value.map((item) => item.id))
  return items.filter((item) => visibleIds.has(item.id))
}

function toggleUsecaseGroup(directory: string) {
  const next = new Set(openUsecaseGroups.value)
  if (next.has(directory)) next.delete(directory)
  else next.add(directory)
  openUsecaseGroups.value = next
}

function toggleUsecase(id: string) {
  const next = new Set(openUsecases.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  openUsecases.value = next
}

function sanitizeJobLog(rawLog: string) {
  if (!rawLog) return []
  return rawLog
    .replace(/<em\s+class=['"]logger\s+error['"]>/g, '')
    .replace(/<\/em>/g, '')
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter(Boolean)
}

function groupLogLines(lines: string[]) {
  const groups: LogGroup[] = []
  const groupMap = new Map<string, LogGroup>()
  for (const raw of lines) {
    const parsed = parseLogLine(raw)
    const groupName = detectLogGroup(parsed.content)
    let group = groupMap.get(groupName)
    if (!group) {
      group = { name: groupName, lines: [] }
      groupMap.set(groupName, group)
      groups.push(group)
    }
    group.lines.push(parsed)
  }
  return groups
}

function parseLogLine(raw: string): LogLine {
  const match = raw.match(/^(\d{2}:\d{2}:\d{2})\s*(.*)$/)
  const content = match ? match[2] : raw
  return { raw, time: match ? match[1] : '日志', content, type: detectLogType(content) }
}

function detectLogType(line: string) {
  if (line.includes('新增')) return 'add'
  if (line.includes('修改')) return 'update'
  if (line.includes('删除')) return 'delete'
  if (line.includes('失败') || line.toLowerCase().includes('error')) return 'error'
  if (line.includes('比对完成') || line.includes('分析完成') || line.includes('报告已生成')) return 'done'
  if (line.includes('查找') || line.includes('检索') || line.includes('命中') || line.includes('影响')) return 'search'
  return 'default'
}

function detectLogGroup(line: string) {
  if (line.includes('发现 [新增]') || line.includes('发现 [修改]') || line.includes('发现 [删除]') || line.includes('新增方法')) return '变更发现'
  if (line.includes('比对完成') || line.includes('变更统计')) return '比对汇总'
  if (line.includes('开始分析用例影响') || line.includes('查找影响用例') || line.includes('命中用例')) return '影响分析'
  return '运行日志'
}

function logTypeText(type: string) {
  const labels: Record<string, string> = { add: '新增', update: '修改', delete: '删除', done: '完成', search: '分析', error: '错误', default: '日志' }
  return labels[type] || '日志'
}

async function copyLog() {
  await navigator.clipboard?.writeText(jobLogLines.value.join('\n'))
}

function clearPendingTimer() {
  if (pendingTimer !== undefined) {
    window.clearTimeout(pendingTimer)
    pendingTimer = undefined
  }
}

onMounted(load)
onBeforeUnmount(clearPendingTimer)
</script>

<style scoped src="@/features/version/styles/version-report-detail-page.css"></style>
