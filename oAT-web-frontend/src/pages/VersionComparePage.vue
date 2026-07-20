<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Compare & Reports</div>
        <h1>{{ center?.app.name || appId }}</h1>
        <p class="subtext">版本比对、制品上传、Git Commit 选择和历史比对报告集中管理。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="secondary-link" :to="`/p/${projectId}/apps/${appId}/versions`">返回版本列表</RouterLink>
        <RouterLink class="secondary-link" :to="`/p/${projectId}/apps/${appId}/repository`">代码仓库配置</RouterLink>
      </div>
    </div>

    <div v-if="center && !repositoryConfigured" class="warning-card">
      当前应用尚未配置代码仓库，Git 比对、分支刷新和 Commit 选择不可用；制品比对仍可使用。
    </div>

    <VersionCompareEditorForm
      :mode="mode"
      :repository-configured="repositoryConfigured"
      :busy="busy"
      :package-compare="packageCompare"
      :package-options="packageOptions"
      :git-compare="gitCompare"
      :branch-options="branchOptions"
      :package-name="packageName"
      :error="error"
      @set-mode="setMode"
      @update:package-compare="packageCompare = $event"
      @update:git-compare="gitCompare = $event"
      @update:package-name="packageName = $event"
      @persist-draft="persistDraft"
      @restore-draft="restoreDraft"
      @clear-draft="clearDraft"
      @upload-package="uploadPackageFile"
      @delete-package="deleteSelectedPackage"
      @load-branches="loadBranches"
      @branch-change="handleBranchChange"
      @select-branch="selectBranch"
      @open-commit-picker="openCommitPicker"
      @fill-latest-commit="fillLatestCommit"
      @submit="submitCompare"
    />

    <VersionCompareJobPanel
      v-model:auto-scroll="autoScrollLog"
      :job="job"
      :fallback-name="mode === 'git' ? 'Git 版本比对' : '制品版本比对'"
      :state-text="jobStateText"
      :progress="jobProgress"
      :class-diff-count="classDiffCount"
      :method-diff-count="methodDiffCount"
      :active-job-id="activeJobId"
      :polling="jobPolling"
      :poll-error="jobPollError"
      :log-lines="jobLogLines"
      :log-groups="jobLogGroups"
      :report-route="reportRoute"
      :log-type-text="jobLogTypeText"
      @refresh="refreshJobOnce"
      @copy-log="copyJobLog"
    />

    <VersionCompareReportsPanel
      v-model:page="compareReportPage"
      v-model:page-size="reportPageSize"
      v-model:collapsed="compareReportsCollapsed"
      :project-id="projectId"
      :app-id="appId"
      :reports="paginatedCompareReports"
      :total-count="center?.compareReports.length || 0"
      :busy="busy"
      :short-text="shortText"
      :commit-tooltip="commitTooltip"
      @delete="removeCompareReport"
    />

    <VersionCommitPickerDialog
      v-model:page="commitPage"
      v-model:page-size="commitPageSize"
      :visible="commitPicker.visible"
      :target="commitPicker.target"
      :keyword="commitPicker.keyword"
      :only-selectable="commitPicker.onlySelectable"
      :commits="paginatedCommits"
      :filtered-count="filteredCommits.length"
      @update:keyword="commitPicker.keyword = $event"
      @update:only-selectable="commitPicker.onlySelectable = $event"
      @close="closeCommitPicker"
      @select="selectCommit"
    />

    <ConfirmDangerDialog
      title-id="versionConfirmTitle"
      :visible="confirmDialog.visible"
      :title="confirmDialog.title"
      :message="confirmDialog.message"
      @cancel="cancelConfirm"
      @confirm="acceptConfirm"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  deleteCompareReport,
  deleteVersionFile,
  fetchCompareJob,
  fetchGitLatestCommit,
  fetchGitRecentCommits,
  fetchRepositoryBranches,
  fetchVersionCenter,
  startCompareJob,
  uploadResource,
} from '@/api/bootstrap'
import type { CompareJobSummary, GitCommitOption, VersionCenterPayload } from '@/api/types'
import VersionCompareEditorForm, {
  type VersionCompareMode,
  type VersionGitCompareState,
  type VersionPackageCompareState,
  type VersionPackageOption,
  type VersionPackageRole,
} from '@/features/version/components/VersionCompareEditorForm.vue'
import VersionCompareReportsPanel from '@/features/version/components/VersionCompareReportsPanel.vue'
import VersionCommitPickerDialog from '@/features/version/components/VersionCommitPickerDialog.vue'
import VersionCompareJobPanel from '@/features/version/components/VersionCompareJobPanel.vue'
import ConfirmDangerDialog from '@/shared/components/ConfirmDangerDialog.vue'

type PackageRole = VersionPackageRole
type UploadedPackage = VersionPackageOption
type JobLogLine = { raw: string; time: string; content: string; type: string }
type JobLogGroup = { name: string; lines: JobLogLine[] }
type ConfirmDialogState = {
  visible: boolean
  title: string
  message: string
  resolve?: (confirmed: boolean) => void
}

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const appId = computed(() => String(route.params.appId || ''))
const initialJobId = computed(() => String(route.query.jobId || ''))
const storageKey = computed(() => `version-compare-draft:${projectId.value}:${appId.value}`)
const center = ref<VersionCenterPayload | null>(null)
const mode = ref<VersionCompareMode>('package')
const packageName = ref('')
const packageCompare = ref<VersionPackageCompareState>({ sourceFile: '', targetFile: '' })
const gitCompare = ref<VersionGitCompareState>({ branch: '', oldCommit: '', newCommit: '' })
const uploadedPackages = ref<UploadedPackage[]>([])
const branches = ref<string[]>([])
const commits = ref<GitCommitOption[]>([])
const commitPicker = ref({ visible: false, target: 'old' as 'old' | 'new', keyword: '', onlySelectable: true })
const job = ref<CompareJobSummary | null>(null)
const busy = ref(false)
const error = ref('')
const activeJobId = ref('')
const jobPolling = ref(false)
const jobPollError = ref('')
const autoScrollLog = ref(true)
const confirmDialog = ref<ConfirmDialogState>({ visible: false, title: '', message: '' })
const compareReportPage = ref(1)
const reportPageSize = ref(10)
const compareReportsCollapsed = ref(false)
const commitPage = ref(1)
const commitPageSize = ref(12)
let pollTimer: number | undefined
let loadRequestSeq = 0
let branchRequestSeq = 0

const repositoryConfigured = computed(() => Boolean(center.value?.app.repoConfigured))
const currentAppBranch = computed(() => center.value?.app.currentBranch || '')
const branchOptions = computed(() => {
  const historyBranches = (center.value?.versions || [])
    .map((item) => item.repoBranch || '')
    .filter(Boolean)
  const ordered = [currentAppBranch.value, ...branches.value, ...historyBranches]
  return ordered.filter((branch, index, list) => branch && list.indexOf(branch) === index)
})
const packageOptions = computed(() => {
  const versionItems = (center.value?.packageVersions || []).map((item) => ({
    value: item.programFile || '',
    label: `${item.versionNumber} · ${item.programName || item.programFile || '-'}`,
    uploaded: false,
  })).filter((item) => item.value)
  const merged = [...uploadedPackages.value, ...versionItems]
  const seen = new Set<string>()
  return merged.filter((item) => {
    if (!item.value || seen.has(item.value)) return false
    seen.add(item.value)
    return true
  })
})
const filteredCommits = computed(() => {
  const needle = commitPicker.value.keyword.toLowerCase()
  return commits.value.filter((item) => {
    const selectable = !commitPicker.value.onlySelectable || Boolean(item.commitId)
    if (!selectable) return false
    if (!needle) return true
    return [item.commitId, item.shortCommitId, item.message, item.author].join(' ').toLowerCase().includes(needle)
  })
})
const paginatedCommits = computed(() => {
  const start = (commitPage.value - 1) * commitPageSize.value
  return filteredCommits.value.slice(start, start + commitPageSize.value)
})
const paginatedCompareReports = computed(() => {
  const reports = center.value?.compareReports || []
  const start = (compareReportPage.value - 1) * reportPageSize.value
  return reports.slice(start, start + reportPageSize.value)
})
const jobProgress = computed(() => Math.max(0, Math.min(100, job.value?.progress || 0)))
const jobStateText = computed(() => {
  if (!job.value) return '未启动'
  if (job.value.error) return '失败'
  if (job.value.finish) return '已完成'
  return jobPolling.value ? '执行中' : '等待刷新'
})
const classDiffCount = computed(() => {
  const current = job.value
  if (!current) return 0
  return (current.addClassCount || 0) + (current.updateClassCount || 0) + (current.deleteClassCount || 0)
})
const methodDiffCount = computed(() => {
  const current = job.value
  if (!current) return 0
  return (current.addMethodCount || 0) + (current.updateMethodCount || 0) + (current.deleteMethodCount || 0)
})
const jobLogLines = computed(() => sanitizeJobLog(job.value?.log || ''))
const jobLogGroups = computed(() => groupJobLogLines(jobLogLines.value))
const reportRoute = computed(() => `/p/${projectId.value}/version/reports/${activeJobId.value || job.value?.id || ''}?appId=${appId.value}`)

watch([() => commitPicker.value.keyword, () => commitPicker.value.onlySelectable, commitPageSize], () => {
  commitPage.value = 1
})

watch(reportPageSize, () => {
  compareReportPage.value = 1
})


async function load() {
  if (busy.value) return
  const requestSeq = ++loadRequestSeq
  busy.value = true
  error.value = ''
  try {
    const nextCenter = await fetchVersionCenter(projectId.value, appId.value)
    if (requestSeq !== loadRequestSeq) return
    center.value = nextCenter
    restoreDraft()
    if (repositoryConfigured.value) await loadBranches(false)
    if (initialJobId.value) {
      await startPollingJob(initialJobId.value)
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载比对中心失败'
  } finally {
    busy.value = false
  }
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

function groupJobLogLines(lines: string[]) {
  const groups: JobLogGroup[] = []
  const groupMap = new Map<string, JobLogGroup>()
  for (const raw of lines) {
    const parsed = parseJobLogLine(raw)
    const groupName = detectJobLogGroup(parsed.content)
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

function parseJobLogLine(raw: string): JobLogLine {
  const match = raw.match(/^(\d{2}:\d{2}:\d{2})\s*(.*)$/)
  const content = match ? match[2] : raw
  return { raw, time: match ? match[1] : '日志', content, type: detectJobLogType(content) }
}

function detectJobLogType(line: string) {
  if (line.includes('新增')) return 'add'
  if (line.includes('修改')) return 'update'
  if (line.includes('删除')) return 'delete'
  if (line.includes('失败') || line.toLowerCase().includes('error')) return 'error'
  if (line.includes('比对完成') || line.includes('分析完成') || line.includes('报告已生成')) return 'done'
  if (line.includes('查找') || line.includes('检索') || line.includes('命中') || line.includes('影响')) return 'search'
  return 'default'
}

function detectJobLogGroup(line: string) {
  if (line.includes('发现 [新增]') || line.includes('发现 [修改]') || line.includes('发现 [删除]') || line.includes('新增方法')) return '变更发现'
  if (line.includes('比对完成') || line.includes('变更统计')) return '比对汇总'
  if (line.includes('开始分析用例影响') || line.includes('查找影响用例') || line.includes('命中用例')) return '影响分析'
  return '运行日志'
}

function jobLogTypeText(type: string) {
  const labels: Record<string, string> = { add: '新增', update: '修改', delete: '删除', done: '完成', search: '分析', error: '错误', default: '日志' }
  return labels[type] || '日志'
}

function shortText(value?: string) {
  if (!value) return ''
  return value.length > 16 ? `${value.slice(0, 8)}...${value.slice(-6)}` : value
}

function commitTooltip(value?: string) {
  return value || '暂无 CommitID'
}

function clearPollTimer() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

async function refreshJobOnce() {
  if (!activeJobId.value) return
  jobPollError.value = ''
  try {
    job.value = await fetchCompareJob(projectId.value, appId.value, activeJobId.value)
  } catch (err) {
    jobPollError.value = err instanceof Error ? err.message : '刷新任务状态失败'
  }
}

async function pollJobTick() {
  if (!activeJobId.value) return
  jobPolling.value = true
  await refreshJobOnce()
  const current = job.value
  if (current?.finish || current?.error) {
    jobPolling.value = false
    await loadReportsOnly()
    return
  }
  pollTimer = window.setTimeout(pollJobTick, 1500)
}

async function startPollingJob(jobId: string) {
  activeJobId.value = jobId
  clearPollTimer()
  await pollJobTick()
}

async function loadReportsOnly() {
  try {
    const latest = await fetchVersionCenter(projectId.value, appId.value)
    center.value = latest
  } catch {
    // Keep the visible job log even if refreshing report cards fails.
  }
}

async function copyJobLog() {
  if (!job.value?.log) return
  await navigator.clipboard?.writeText(jobLogLines.value.join('\n'))
}

function setMode(nextMode: VersionCompareMode) {
  if (nextMode === 'git' && !repositoryConfigured.value) return
  mode.value = nextMode
  persistDraft()
}

function persistDraft() {
  localStorage.setItem(storageKey.value, JSON.stringify({
    mode: mode.value,
    packageName: packageName.value,
    packageCompare: packageCompare.value,
    gitCompare: gitCompare.value,
    uploadedPackages: uploadedPackages.value,
  }))
}

function restoreDraft() {
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (!raw) return
    const draft = JSON.parse(raw) as {
      mode?: 'package' | 'git'
      packageName?: string
      packageCompare?: { sourceFile?: string; targetFile?: string }
      gitCompare?: { branch?: string; oldCommit?: string; newCommit?: string }
      uploadedPackages?: UploadedPackage[]
    }
    mode.value = draft.mode === 'git' && repositoryConfigured.value ? 'git' : 'package'
    packageName.value = draft.packageName || ''
    packageCompare.value = { sourceFile: draft.packageCompare?.sourceFile || '', targetFile: draft.packageCompare?.targetFile || '' }
    gitCompare.value = {
      branch: currentAppBranch.value || draft.gitCompare?.branch || '',
      oldCommit: draft.gitCompare?.branch === (currentAppBranch.value || draft.gitCompare?.branch || '') ? draft.gitCompare?.oldCommit || '' : '',
      newCommit: draft.gitCompare?.branch === (currentAppBranch.value || draft.gitCompare?.branch || '') ? draft.gitCompare?.newCommit || '' : '',
    }
    uploadedPackages.value = draft.uploadedPackages || []
  } catch {
    clearDraft()
  }
}

function clearDraft() {
  packageName.value = ''
  packageCompare.value = { sourceFile: '', targetFile: '' }
  gitCompare.value = { branch: '', oldCommit: '', newCommit: '' }
  uploadedPackages.value = []
  localStorage.removeItem(storageKey.value)
}

async function uploadPackageFile(role: PackageRole, event: Event) {
  if (busy.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  try {
    const path = await uploadResource(file)
    const item = { value: path, label: `上传 · ${file.name}`, uploaded: true }
    uploadedPackages.value = [item, ...uploadedPackages.value.filter((candidate) => candidate.value !== path)]
    if (role === 'source') packageCompare.value.sourceFile = path
    else packageCompare.value.targetFile = path
    persistDraft()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '上传制品失败'
  } finally {
    input.value = ''
    busy.value = false
  }
}

async function deleteSelectedPackage(role: PackageRole) {
  if (busy.value) return
  const filePath = role === 'source' ? packageCompare.value.sourceFile : packageCompare.value.targetFile
  if (!filePath) return
  const confirmed = await askConfirm('删除制品文件', `确认删除文件 ${filePath}？删除后本地缓存文件将不可恢复。`)
  if (!confirmed) return
  busy.value = true
  error.value = ''
  try {
    await deleteVersionFile(projectId.value, appId.value, filePath)
    uploadedPackages.value = uploadedPackages.value.filter((item) => item.value !== filePath)
    if (role === 'source') packageCompare.value.sourceFile = ''
    else packageCompare.value.targetFile = ''
    persistDraft()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除文件失败'
  } finally {
    busy.value = false
  }
}

async function loadBranches(showError = true) {
  if (!repositoryConfigured.value) return
  const requestSeq = ++branchRequestSeq
  try {
    const nextBranches = await fetchRepositoryBranches(projectId.value, appId.value)
    if (requestSeq !== branchRequestSeq) return
    branches.value = nextBranches
    if (!gitCompare.value.branch) {
      gitCompare.value.branch = currentAppBranch.value || branches.value[0] || ''
    }
    persistDraft()
  } catch (err) {
    if (showError) error.value = err instanceof Error ? err.message : '加载 Git 分支失败'
  }
}

function handleBranchChange() {
  gitCompare.value.oldCommit = ''
  gitCompare.value.newCommit = ''
  commits.value = []
  persistDraft()
}

function selectBranch(branch: string) {
  if (gitCompare.value.branch === branch) return
  gitCompare.value.branch = branch
  handleBranchChange()
}

async function openCommitPicker(target: 'old' | 'new') {
  if (busy.value) return
  if (!gitCompare.value.branch) {
    error.value = '请先选择 Git 分支'
    return
  }
  busy.value = true
  error.value = ''
  try {
    commits.value = await fetchGitRecentCommits(projectId.value, appId.value, gitCompare.value.branch, 80)
    commitPicker.value = { visible: true, target, keyword: '', onlySelectable: true }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载 Commit 列表失败'
  } finally {
    busy.value = false
  }
}

function closeCommitPicker() {
  commitPicker.value.visible = false
}

function selectCommit(commitId: string) {
  if (commitPicker.value.target === 'old') gitCompare.value.oldCommit = commitId
  else gitCompare.value.newCommit = commitId
  persistDraft()
  closeCommitPicker()
}

async function fillLatestCommit() {
  if (!gitCompare.value.branch) return
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    gitCompare.value.newCommit = await fetchGitLatestCommit(projectId.value, appId.value, gitCompare.value.branch)
    persistDraft()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '获取最新 Commit 失败'
  } finally {
    busy.value = false
  }
}

async function submitCompare() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  jobPollError.value = ''
  try {
    if (mode.value === 'git' && !repositoryConfigured.value) throw new Error('当前应用未配置代码仓库')
    if (mode.value === 'git' && (!gitCompare.value.branch || !gitCompare.value.oldCommit || !gitCompare.value.newCommit)) {
      throw new Error('请完整填写 Git 分支、旧 Commit 和新 Commit')
    }
    if (mode.value === 'package' && (!packageCompare.value.sourceFile || !packageCompare.value.targetFile)) {
      throw new Error('请完整选择旧版本文件和新版本文件')
    }
    persistDraft()
    const payload =
      mode.value === 'git'
        ? {
            mode: 'git',
            branch: gitCompare.value.branch,
            oldCommit: gitCompare.value.oldCommit,
            newCommit: gitCompare.value.newCommit,
            packageName: packageName.value || undefined,
          }
        : {
            mode: 'package',
            sourceFile: packageCompare.value.sourceFile,
            targetFile: packageCompare.value.targetFile,
            packageName: packageName.value || undefined,
          }
    const result = await startCompareJob(projectId.value, appId.value, payload)
    job.value = result.job || null
    await startPollingJob(result.jobId)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '启动比对失败'
  } finally {
    busy.value = false
  }
}

async function removeCompareReport(reportId: string) {
  if (busy.value) return
  const confirmed = await askConfirm('删除比对报告', '确认删除该比对报告？删除后历史比对结果和任务日志将不可恢复。')
  if (!confirmed) return
  busy.value = true
  error.value = ''
  try {
    await deleteCompareReport(projectId.value, appId.value, reportId)
    center.value = await fetchVersionCenter(projectId.value, appId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除比对报告失败'
  } finally {
    busy.value = false
  }
}

function askConfirm(title: string, message: string) {
  return new Promise<boolean>((resolve) => {
    confirmDialog.value = { visible: true, title, message, resolve }
  })
}

function closeConfirm(confirmed: boolean) {
  const resolve = confirmDialog.value.resolve
  confirmDialog.value = { visible: false, title: '', message: '' }
  resolve?.(confirmed)
}

function cancelConfirm() {
  closeConfirm(false)
}

function acceptConfirm() {
  closeConfirm(true)
}

onBeforeUnmount(() => {
  clearPollTimer()
  if (confirmDialog.value.visible) {
    closeConfirm(false)
  }
})

onMounted(load)
</script>

<style scoped>
.page-header,
.header-actions,
.panel-head {
  display: flex;
  gap: 12px;
}

.page-header,
.panel-head {
  justify-content: space-between;
  align-items: center;
}

.page-header {
  margin-bottom: 20px;
}


.subtext {
  color: #64748b;
}

.secondary-link {
  color: #0f766e;
  font-weight: 700;
}

.primary-button,
.ghost-button,
.danger-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease;
}

.ghost-button {
  background: rgba(15, 23, 42, 0.08);
}

.primary-button {
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
  box-shadow: 0 12px 22px rgba(20, 184, 166, .22);
}

.danger-button {
  background: rgba(185, 28, 28, .12);
  color: #b91c1c;
}

.primary-button:hover:not(:disabled) {
  background: linear-gradient(135deg, #0b5f59, #0f9f94);
  box-shadow: 0 14px 26px rgba(20, 184, 166, .30);
}

.ghost-button:hover:not(:disabled) {
  background: rgba(15, 118, 110, .12);
  color: #0f766e;
}

.danger-button:hover:not(:disabled) {
  background: #b91c1c;
  color: #fff;
  box-shadow: 0 12px 22px rgba(185, 28, 28, .18);
}

.primary-button:hover:not(:disabled),
.ghost-button:hover:not(:disabled),
.danger-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.small {
  padding: 7px 10px;
  font-size: 12px;
}

button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.panel,
.warning-card,
.modal-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.warning-card {
  margin-bottom: 14px;
  background: rgba(245, 158, 11, .12);
  color: #92400e;
}

.panel {
  margin-top: 18px;
  flex-wrap: wrap;
}

.error-text {
  color: #b91c1c;
}

</style>
