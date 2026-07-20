<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Create Version</div>
        <h1>{{ center?.app.name || appId }}</h1>
        <p class="subtext">支持 Git 拉取与文件上传两种方式创建新版本。</p>
      </div>
      <div class="header-actions">
        <RouterLink class="secondary-link" :to="`/p/${projectId}/apps/${appId}/versions`">返回版本列表</RouterLink>
      </div>
    </div>

    <div v-if="center && !center.app.repoConfigured" class="warning-card">
      当前应用尚未配置代码仓库，Git 拉取不可用；请先进入代码仓库配置，或切换到文件上传。
    </div>

    <div class="tab-row">
      <button type="button" :class="['tab-button', sourceType === 'git' && 'active']" :disabled="!center?.app.repoConfigured" @click="sourceType = 'git'">Git 拉取</button>
      <button type="button" :class="['tab-button', sourceType === 'upload' && 'active']" @click="sourceType = 'upload'">文件上传</button>
    </div>

    <form class="editor-card" @submit.prevent="submit">
      <div class="grid-two">
        <label class="field">
          <span>版本号</span>
          <input v-model.trim="form.versionNumber" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>设为当前版本</span>
          <select v-model="form.setAsCurrent" class="text-input">
            <option value="">否</option>
            <option value="on">是</option>
          </select>
        </label>
        <label class="field wide">
          <span>版本描述</span>
          <textarea v-model.trim="form.describe" class="text-area" rows="3" />
        </label>
      </div>

      <template v-if="sourceType === 'git'">
        <div class="grid-two">
          <div ref="branchFieldRef" class="field branch-field">
            <span>分支</span>
            <button class="branch-select-trigger text-input" type="button" :disabled="busy || branchesLoading || !center?.app.repoConfigured" :aria-expanded="branchMenuOpen" @click="toggleBranchMenu">
              <span :class="{ placeholder: !form.repoBranch }">{{ form.repoBranch || '请选择分支' }}</span>
              <span class="select-caret" aria-hidden="true">⌄</span>
            </button>
            <div v-if="branchMenuOpen" class="branch-menu">
              <input v-model.trim="branchSearch" class="branch-search" type="search" placeholder="搜索分支" aria-label="搜索分支" @keydown.stop />
              <div v-if="branchesLoading" class="branch-menu-empty">正在加载分支...</div>
              <template v-else-if="filteredBranchOptions.length">
                <button
                  v-for="branch in filteredBranchOptions"
                  :key="branch"
                  class="branch-option"
                  :class="{ active: branch === form.repoBranch }"
                  type="button"
                  @click="selectBranch(branch)"
                >
                  <span>{{ branch }}</span>
                  <small v-if="branch === currentAppBranch">当前分支</small>
                </button>
              </template>
              <div v-else class="branch-menu-empty">暂无匹配分支</div>
            </div>
            <small class="field-hint">共 {{ branchOptions.length }} 个分支，包含远程分支与历史版本分支。</small>
          </div>
          <label class="field">
            <span>Commit ID</span>
            <input v-model.trim="form.repoCommitId" class="text-input" type="text" />
          </label>
          <label class="field wide">
            <span>排除路径</span>
            <input v-model.trim="excludePaths" class="text-input" type="text" placeholder="多个路径用逗号分隔" />
          </label>
        </div>
        <div class="action-row">
          <button class="ghost-button" type="button" :disabled="busy || branchesLoading || !center?.app.repoConfigured" @click="loadBranches()">{{ branchesLoading ? '刷新中...' : '刷新分支' }}</button>
          <button class="ghost-button" type="button" :disabled="busy || !center?.app.repoConfigured || !form.repoBranch" @click="fetchLatestCommitForBranch">获取最新 Commit</button>
          <button class="ghost-button" type="button" :disabled="busy || !center?.app.repoConfigured || !form.repoBranch" @click="checkGit">检测可拉取性</button>
          <button class="ghost-button" type="button" :disabled="busy || !center?.app.repoConfigured || !form.repoBranch" @click="pullGit">执行拉取</button>
          <button class="ghost-button" type="button" :disabled="busy || !gitPulledPath" @click="removePulledCode">删除拉取文件</button>
        </div>
        <div v-if="gitEstimate" class="panel">
          <div class="panel-head">
            <h2>拉取预估</h2>
          </div>
          <div class="meta-list">
            <span>分支 {{ gitEstimate.branch || '-' }}</span>
            <span>Commit {{ gitEstimate.commitId || '-' }}</span>
            <span>时长 {{ formatDuration(gitEstimate.estimatedDurationMs) }}</span>
            <span>包大小 {{ formatBytes(gitEstimate.estimatedPackageSizeBytes) }}</span>
          </div>
          <div v-if="gitEstimate.packageCommitVerify?.unavailableReason" class="status-callout warning">
            <div class="callout-icon" aria-hidden="true">!</div>
            <div>
              <strong>运行时 Commit 校验未完成</strong>
              <span>{{ gitEstimate.packageCommitVerify.unavailableReason }}</span>
              <small>这不会阻止 Git 拉取。</small>
            </div>
          </div>
        </div>
        <div v-if="gitJob" class="panel task-panel">
          <div class="panel-head">
            <div class="panel-title-block">
              <h2>拉取任务</h2>
              <p class="subtext">{{ gitJob.progressName || gitJob.message || '正在处理中' }}</p>
            </div>
            <span class="progress-badge">{{ gitJob.progress }}%</span>
          </div>
          <div class="progress-bar-container">
            <div class="progress-bar-fill" :style="{ width: `${gitJob.progress}%` }"></div>
          </div>
        </div>
      </template>

      <template v-else>
        <label class="field">
          <span>程序文件</span>
          <input class="text-input" type="file" @change="onFileChange" />
        </label>
        <div class="action-row">
          <button class="ghost-button" type="button" :disabled="busy || !selectedFile" @click="uploadFile">上传文件</button>
        </div>
      </template>

      <div class="panel">
        <div class="panel-head">
          <h2>已选择程序文件</h2>
        </div>
        <p class="subtext">{{ uploadedPath || gitPulledPath || '尚未准备程序文件' }}</p>
      </div>

      <p v-if="notice" class="notice-text">{{ notice }}</p>
      <p v-if="error" class="error-text">{{ error }}</p>
      <div class="action-row">
        <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '处理中...' : '创建版本' }}</button>
      </div>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import {
  createVersion,
  deleteGitCode,
  fetchGitPullEstimate,
  fetchGitLatestCommit,
  fetchGitPullStatus,
  fetchRepositoryBranches,
  fetchVersionCenter,
  startGitPull,
  uploadResource,
} from '@/api/bootstrap'
import type { GitJobSummary, GitPullEstimate, VersionCenterPayload } from '@/api/types'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId || ''))
const appId = computed(() => String(route.params.appId || ''))
const center = ref<VersionCenterPayload | null>(null)
const sourceType = ref<'git' | 'upload'>('git')
const branches = ref<string[]>([])
const branchSearch = ref('')
const branchMenuOpen = ref(false)
const branchFieldRef = ref<HTMLElement | null>(null)
const selectedFile = ref<File | null>(null)
const uploadedPath = ref('')
const gitPulledPath = ref('')
const excludePaths = ref('')
const gitEstimate = ref<GitPullEstimate | null>(null)
const gitJob = ref<GitJobSummary | null>(null)
const busy = ref(false)
const branchesLoading = ref(false)
const error = ref('')
const notice = ref('')
const form = ref({
  versionNumber: '',
  describe: '',
  setAsCurrent: '',
  repoBranch: '',
  repoCommitId: '',
})

const currentAppBranch = computed(() => center.value?.app.currentBranch || '')
const branchOptions = computed(() => {
  const historyBranches = [
    ...(center.value?.versions || []).map((version) => version.repoBranch || ''),
  ]
  const orderedBranches = [currentAppBranch.value, form.value.repoBranch, ...branches.value, ...historyBranches]
  const seenBranches = new Set<string>()
  return orderedBranches
    .map(normalizeBranchName)
    .filter((branch) => {
      if (!branch || seenBranches.has(branch)) return false
      seenBranches.add(branch)
      return true
    })
})
const filteredBranchOptions = computed(() => {
  const keyword = branchSearch.value.toLowerCase()
  if (!keyword) return branchOptions.value
  return branchOptions.value.filter((branch) => branch.toLowerCase().includes(keyword))
})

function normalizeBranchName(branch?: string) {
  return String(branch || '').trim().replace(/^refs\/heads\//, '')
}

function closeBranchMenu() {
  branchMenuOpen.value = false
  branchSearch.value = ''
}

function toggleBranchMenu() {
  if (!center.value?.app.repoConfigured) return
  branchMenuOpen.value = !branchMenuOpen.value
  if (branchMenuOpen.value && !branches.value.length) {
    void loadBranches(false)
  }
}

async function selectBranch(branch: string) {
  form.value.repoBranch = branch
  form.value.repoCommitId = ''
  closeBranchMenu()
  await fetchLatestCommitForBranch()
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target as Node | null
  if (!target || branchFieldRef.value?.contains(target)) return
  closeBranchMenu()
}

function formatBytes(value?: number) {
  if (!value) return '-'
  if (value > 1024 * 1024) return `${(value / 1024 / 1024).toFixed(2)} MB`
  if (value > 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${value} B`
}

function formatDuration(value?: number) {
  if (!value) return '-'
  return `${Math.ceil(value / 1000)} 秒`
}

async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    center.value = await fetchVersionCenter(projectId.value, appId.value)
    form.value.setAsCurrent = center.value.versions.length ? '' : 'on'
    if (!center.value.app.repoConfigured) sourceType.value = 'upload'
    else await loadBranches(false)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载旧源码快照工具失败'
  } finally {
    busy.value = false
  }
}

async function loadBranches(showError = true) {
  if (!center.value?.app.repoConfigured) return
  if (branchesLoading.value) return
  branchesLoading.value = true
  if (showError) error.value = ''
  try {
    branches.value = (await fetchRepositoryBranches(projectId.value, appId.value)).map(normalizeBranchName).filter(Boolean)
    if (!form.value.repoBranch && branchOptions.value.length) {
      form.value.repoBranch = branchOptions.value[0]
      await fetchLatestCommitForBranch()
    }
  } catch (err) {
    if (showError) error.value = err instanceof Error ? err.message : '加载分支失败'
  } finally {
    branchesLoading.value = false
  }
}

async function fetchLatestCommitForBranch() {
  if (!form.value.repoBranch || !center.value?.app.repoConfigured) return
  busy.value = true
  error.value = ''
  try {
    form.value.repoCommitId = await fetchGitLatestCommit(projectId.value, appId.value, form.value.repoBranch)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '获取最新 Commit 失败'
  } finally {
    busy.value = false
  }
}

function findExistingVersionFile(versionNumber: string, branch: string, commitId: string) {
  if (!center.value?.versions) return null
  const normalizedBranch = branch.trim()
  const normalizedCommitId = commitId.trim()
  const normalizedVersionNumber = versionNumber.trim()
  
  for (const version of center.value.versions) {
    if (version.versionNumber === normalizedVersionNumber &&
        version.repoBranch === normalizedBranch &&
        version.repoCommitId === normalizedCommitId &&
        version.programFile) {
      return version.programFile
    }
  }
  return null
}

async function checkGit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    gitEstimate.value = await fetchGitPullEstimate(projectId.value, appId.value, {
      branch: form.value.repoBranch,
      commitId: form.value.repoCommitId || undefined,
      versionNumber: form.value.versionNumber || undefined,
      excludePaths: excludePaths.value || undefined,
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Git 检测失败'
    error.value = message
    if (message.includes('该版本号下已存在相同的分支和 CommitID') && form.value.versionNumber && form.value.repoBranch && form.value.repoCommitId) {
      const existingFile = findExistingVersionFile(form.value.versionNumber, form.value.repoBranch, form.value.repoCommitId)
      if (existingFile) {
        gitPulledPath.value = existingFile
      }
    }
  } finally {
    busy.value = false
  }
}

async function pollGit(jobId: string) {
  while (true) {
    gitJob.value = await fetchGitPullStatus(projectId.value, appId.value, jobId)
    if (gitJob.value.finish) break
    await new Promise((resolve) => setTimeout(resolve, 1500))
  }
  if (gitJob.value.success) {
    gitPulledPath.value = gitJob.value.cachePath || ''
    form.value.repoCommitId = gitJob.value.repoCommitId || form.value.repoCommitId
  } else {
    throw new Error(gitJob.value.message || 'Git 拉取失败')
  }
}

async function pullGit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const jobId = await startGitPull(projectId.value, appId.value, {
      branch: form.value.repoBranch,
      commitId: form.value.repoCommitId || undefined,
      excludePaths: excludePaths.value || undefined,
      versionNumber: form.value.versionNumber || undefined,
    })
    await pollGit(jobId)
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Git 拉取失败'
    error.value = message
    
    // 处理重复错误：数据库中有记录
    if (message.includes('该版本号下已存在相同的分支和 CommitID') && form.value.versionNumber && form.value.repoBranch && form.value.repoCommitId) {
      const existingFile = findExistingVersionFile(form.value.versionNumber, form.value.repoBranch, form.value.repoCommitId)
      if (existingFile) {
        gitPulledPath.value = existingFile
      }
    }
    
    // 处理磁盘缓存重复错误：磁盘上有文件但数据库中没有记录
    if (message.includes('该分支和 CommitID 的代码已在磁盘缓存中')) {
      const pathMatch = message.match(/缓存路径:\s*([^,\)]+)/)
      if (pathMatch && pathMatch[1]) {
        gitPulledPath.value = pathMatch[1].trim()
      }
    }
  } finally {
    busy.value = false
  }
}

async function removePulledCode() {
  if (!gitPulledPath.value) return
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await deleteGitCode(projectId.value, appId.value, gitPulledPath.value)
    gitPulledPath.value = ''
    gitJob.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : '删除拉取文件失败'
  } finally {
    busy.value = false
  }
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  selectedFile.value = target.files?.[0] || null
}

async function uploadFile() {
  if (busy.value) return
  if (!selectedFile.value) {
    error.value = '请先选择文件'
    return
  }
  busy.value = true
  error.value = ''
  try {
    uploadedPath.value = await uploadResource(selectedFile.value)
    notice.value = `文件已上传：${selectedFile.value.name}`
  } catch (err) {
    error.value = err instanceof Error ? err.message : '上传文件失败'
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (busy.value) return
  if (!form.value.versionNumber) {
    error.value = '版本号不能为空'
    return
  }
  const programFile = sourceType.value === 'git' ? gitPulledPath.value : uploadedPath.value
  if (!programFile) {
    error.value = '请先准备程序文件'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await createVersion(projectId.value, appId.value, {
      versionNumber: form.value.versionNumber,
      describe: form.value.describe,
      programFile,
      sourceType: sourceType.value,
      repoBranch: form.value.repoBranch,
      repoCommitId: form.value.repoCommitId,
      setAsCurrent: form.value.setAsCurrent,
    })
    gitPulledPath.value = ''
    await router.push(`/p/${projectId.value}/apps/${appId.value}/versions`)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '创建版本失败'
  } finally {
    busy.value = false
  }
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (!gitPulledPath.value) return
  event.preventDefault()
  event.returnValue = 'Git 拉取的临时代码尚未创建版本或删除，离开页面前建议先删除拉取文件。'
}

onMounted(() => {
  load()
  window.addEventListener('beforeunload', beforeUnload)
  document.addEventListener('click', handleDocumentClick)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('click', handleDocumentClick)
})
</script>

<style scoped>
.page-header,
.header-actions,
.tab-row,
.action-row,
.panel-head,
.meta-list {
  display: flex;
  gap: 12px;
}

.page-header,
.panel-head,
.meta-list {
  justify-content: space-between;
  align-items: center;
}

.subtext,
.field span,
.meta-list {
  color: #64748b;
}

.secondary-link {
  color: #0f766e;
  font-weight: 700;
}

.tab-button,
.primary-button,
.ghost-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
}

.tab-button,
.ghost-button {
  background: rgba(15, 23, 42, 0.08);
}

.tab-button.active,
.primary-button {
  background: #0f172a;
  color: #fff;
}

.editor-card,
.panel,
.warning-card {
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

.status-callout {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 12px;
  margin-top: 14px;
  padding: 14px;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-callout.warning {
  background: rgba(255, 247, 237, 0.92);
  border-color: rgba(234, 88, 12, 0.18);
}

.callout-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: rgba(234, 88, 12, 0.14);
  color: #c2410c;
  font-weight: 900;
}

.status-callout div:last-child {
  display: grid;
  gap: 5px;
}

.status-callout strong {
  color: #9a3412;
}

.status-callout span,
.status-callout small {
  color: #64748b;
  line-height: 1.55;
}

button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  gap: 8px;
}

.branch-field {
  position: relative;
}

.branch-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 46px;
  background: #fff;
  color: #0f172a;
  text-align: left;
}

.branch-select-trigger .placeholder,
.field-hint {
  color: #94a3b8;
}

.select-caret {
  color: #334155;
  font-weight: 800;
}

.branch-menu {
  position: absolute;
  top: 72px;
  left: 0;
  right: 0;
  z-index: 18;
  display: grid;
  gap: 6px;
  max-height: 300px;
  overflow: auto;
  padding: 10px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.98);
  border: 1px solid rgba(15, 23, 42, 0.1);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.14);
}

.branch-search {
  width: 100%;
  border-radius: 12px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  padding: 9px 11px;
  outline: none;
}

.branch-search:focus {
  border-color: rgba(15, 118, 110, 0.45);
  box-shadow: 0 0 0 4px rgba(15, 118, 110, 0.12);
}

.branch-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  border: 0;
  border-radius: 12px;
  padding: 10px 12px;
  background: transparent;
  color: #0f172a;
  text-align: left;
  cursor: pointer;
}

.branch-option:hover,
.branch-option.active {
  background: rgba(15, 118, 110, 0.1);
  color: #0f766e;
}

.branch-option span {
  color: inherit;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-option small {
  flex: 0 0 auto;
  color: #0f766e;
  font-weight: 800;
}

.branch-menu-empty {
  padding: 12px;
  color: #64748b;
  text-align: center;
}

.field-hint {
  font-size: 12px;
}

.wide {
  grid-column: 1 / -1;
}

.text-input,
.text-area {
  width: 100%;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  padding: 10px 12px;
}

.action-row,
.panel {
  margin-top: 16px;
  flex-wrap: wrap;
}

.task-panel {
  background: linear-gradient(135deg, rgba(15, 118, 110, 0.04), rgba(59, 130, 246, 0.04));
  border-color: rgba(15, 118, 110, 0.18);
}

.panel-title-block {
  display: grid;
  gap: 4px;
}

.panel-title-block h2,
.panel-title-block p {
  margin: 0;
}

.panel-title-block p.subtext {
  font-size: 13px;
  line-height: 1.5;
}

.progress-badge {
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(15, 118, 110, 0.12);
  color: #0f766e;
  font-size: 14px;
  font-weight: 800;
  white-space: nowrap;
}

.progress-bar-container {
  position: relative;
  width: 100%;
  height: 8px;
  margin-top: 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #0f766e, #14b8a6);
  transition: width 0.4s ease;
  box-shadow: 0 0 12px rgba(15, 118, 110, 0.3);
}

.notice-text {
  color: #0f766e;
  margin-top: 14px;
}

.error-text {
  color: #b91c1c;
  margin-top: 14px;
}

@media (max-width: 840px) {
  .grid-two {
    grid-template-columns: 1fr;
  }
}
</style>
