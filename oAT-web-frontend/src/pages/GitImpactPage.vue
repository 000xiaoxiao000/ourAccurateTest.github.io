<template>
  <section class="git-impact-page">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">Git Change Impact</div>
        <h1>Git 变更影响分析</h1>
        <p class="subtext">比较两个 Commit，定位结构化变更、调用传播路径，以及受影响的验收标准和回归用例。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadOverview" />
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <section class="panel-section form-stack">
      <div class="section-head"><h2>分析范围</h2><span>确定性传播优先，LLM 仅辅助确认</span></div>
      <label>
        <span>分析基线</span>
        <select v-model="form.baselineId">
          <option value="">请选择已完成的 AI 验证基线</option>
          <option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">{{ baseline.name }} · {{ baseline.status }}</option>
        </select>
      </label>
      <div class="inline-grid">
        <label>
          <span>源码工程</span>
          <select v-model="form.appId">
            <option value="">请选择源码工程</option>
            <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
          </select>
        </label>
        <label><span>Base Commit</span><input v-model.trim="form.baseCommit" placeholder="比较起点 Commit" /></label>
        <label><span>Head Commit</span><input v-model.trim="form.headCommit" placeholder="比较终点 Commit" /></label>
      </div>
      <div class="analyze-action-row">
        <button
          type="button"
          class="analyze-button"
          :disabled="loading || !canAnalyze"
          :aria-label="loading ? '正在分析 Git 影响范围' : '分析 Git 影响范围'"
          @click="analyze"
        >
          <svg v-if="loading" class="analyze-icon spinning" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20 11a8 8 0 0 0-14.9-4L3 9m0-5v5h5M4 13a8 8 0 0 0 14.9 4L21 15m0 5v-5h-5" />
          </svg>
          <span>{{ loading ? '正在分析影响范围...' : '分析 Git 影响范围' }}</span>
          <span v-if="!canAnalyze && !loading" class="analyze-hint">请完善分析条件</span>
        </button>
      </div>
      <div v-if="analysisJob" :class="['analysis-progress', analysisJob.status.toLowerCase()]">
        <div class="progress-top">
          <strong>{{ analysisJob.message || jobStatusText }}</strong>
          <span>{{ analysisJob.percent }}%</span>
        </div>
        <div class="progress-track"><div :style="{ width: `${analysisJob.percent}%` }"></div></div>
        <div class="progress-meta">
          <span>{{ stageText(analysisJob.stage) }}</span>
          <span>{{ jobStatusText }}</span>
        </div>
      </div>
    </section>

    <section v-if="result" class="panel-section result-panel">
      <div class="section-head">
        <h2>影响结果</h2>
        <span>{{ shortCommit(result.report.changeSet.baseCommit) }} → {{ shortCommit(result.report.changeSet.headCommit) }}</span>
      </div>

      <div class="summary-grid">
        <div class="summary-item"><strong>{{ result.report.changeSet.files.length }}</strong><span>变更文件</span></div>
        <div class="summary-item">
          <strong>{{ result.report.directChanges.length }}</strong>
          <span class="summary-help" title="直接结构变更：本次 Git diff 中实际发生新增、修改或删除的代码结构元素，如类、方法、字段等。">直接结构变更</span>
        </div>
        <div class="summary-item">
          <strong>{{ transitiveCandidates.length }}</strong>
          <span class="summary-help" title="传播候选：由直接代码变更沿调用、依赖、继承或引用关系推导出的潜在受影响对象，需要进一步确认是否真的受影响。">传播候选</span>
        </div>
        <div class="summary-item"><strong>{{ result.traceability.affectedCriteria.length }}</strong><span>验收标准</span></div>
        <div class="summary-item"><strong>{{ result.traceability.affectedTestcases.length }}</strong><span>回归用例</span></div>
      </div>

      <div class="commit-strip">
        <div><span>Base</span><code>{{ result.report.changeSet.baseCommit }}</code></div>
        <div><span>Head</span><code>{{ result.report.changeSet.headCommit }}</code></div>
        <div><span>分析器</span><code>{{ result.report.changeSet.analyzerVersion || 'unknown' }}</code></div>
      </div>

      <div v-if="llmReview" :class="['llm-status', llmReview.status.toLowerCase()]">
        <strong>LLM 辅助确认：{{ llmReviewText }}</strong>
        <span v-if="llmReview.total">{{ llmReview.completed }} / {{ llmReview.total }} 条</span>
        <span v-if="llmReview.message">{{ llmReview.message }}</span>
      </div>

      <div class="result-toolbar">
        <label>
          <span>关键字</span>
          <input v-model.trim="resultFilters.keyword" placeholder="筛选文件、符号、路径或用例" />
        </label>
        <label>
          <span>传播类型</span>
          <select v-model="resultFilters.classification">
            <option value="">全部候选</option>
            <option value="TRANSITIVE">确定传播</option>
            <option value="POSSIBLE">可能影响</option>
            <option value="UNKNOWN">待确认</option>
          </select>
        </label>
      </div>

      <div v-if="!result.report.directChanges.length" class="empty-state compact">两个 Commit 间未发现可分析的结构化源码变更。</div>

      <div v-else class="result-layout">
        <section class="result-block files-block">
          <div class="block-head">
            <h3>文件变更</h3>
            <span>{{ filteredFiles.length }} / {{ result.report.changeSet.files.length }} 个文件</span>
          </div>
          <div v-if="!filteredFiles.length" class="empty-inline">没有匹配的文件变更。</div>
          <article v-for="file in paginatedFiles" :key="`${file.oldPath}-${file.newPath}`" class="file-row">
            <div class="row-main">
              <span :class="['badge', badgeClass(file.changeType)]">{{ typeText(file.changeType) }}</span>
              <strong>{{ filePath(file) }}</strong>
            </div>
            <div class="meta-line">
              <span>{{ file.language || 'unknown' }}</span>
              <span v-if="file.renameScore">rename {{ file.renameScore }}%</span>
              <span v-if="formatRanges(file.oldRanges)">旧行 {{ formatRanges(file.oldRanges) }}</span>
              <span v-if="formatRanges(file.newRanges)">新行 {{ formatRanges(file.newRanges) }}</span>
            </div>
          </article>
          <AppPagination
            v-model:page="pages.files"
            v-model:page-size="pageSizes.files"
            :total="filteredFiles.length"
            item-name="文件"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block direct-block">
          <div class="block-head">
            <h3>直接结构变更</h3>
            <span>{{ filteredDirectChanges.length }} / {{ result.report.directChanges.length }} 项</span>
          </div>
          <div v-if="!filteredDirectChanges.length" class="empty-inline">没有匹配的结构变更。</div>
          <article v-for="change in paginatedDirectChanges" :key="changeKey(change)" class="change-card">
            <div class="change-title">
              <span :class="['badge', badgeClass(change.changeType)]">{{ typeText(change.changeType) }}</span>
              <div>
                <strong>{{ symbolName(changeKey(change)) }}</strong>
                <span>{{ symbolMeta(change) }}</span>
              </div>
            </div>
            <div class="facet-list">
              <span v-for="facet in change.facets" :key="facet">{{ facetText(facet) }}</span>
            </div>
            <div class="meta-line">
              <span v-if="activeSymbol(change)?.path">{{ activeSymbol(change)?.path }}</span>
              <span v-if="rangeText(activeSymbol(change)?.range)">行 {{ rangeText(activeSymbol(change)?.range) }}</span>
              <span v-if="formatRanges(change.evidenceRanges)">变更行 {{ formatRanges(change.evidenceRanges) }}</span>
            </div>
            <details v-if="activeSymbol(change)?.snippet" class="snippet-box">
              <summary>查看源码片段</summary>
              <div class="snippet-code" role="region" aria-label="源码片段">
                <div v-for="line in snippetLines(change)" :key="line.key" class="snippet-line">
                  <span class="snippet-line-number">{{ line.number }}</span>
                  <code>{{ line.text || ' ' }}</code>
                </div>
              </div>
            </details>
          </article>
          <AppPagination
            v-model:page="pages.direct"
            v-model:page-size="pageSizes.direct"
            :total="filteredDirectChanges.length"
            item-name="变更"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block candidates-block">
          <div class="block-head">
            <h3>传播影响路径</h3>
            <span>{{ filteredCandidates.length }} / {{ transitiveCandidates.length }} 条候选</span>
          </div>
          <div v-if="!filteredCandidates.length" class="empty-inline">没有匹配的传播影响。</div>
          <article v-for="candidate in paginatedCandidates" :key="candidateKey(candidate)" class="candidate-card">
            <div class="candidate-top">
              <span :class="['badge', classificationClass(candidate.classification)]">{{ classificationText(candidate.classification) }}</span>
              <strong>{{ symbolName(candidate.targetSymbol) }}</strong>
              <span class="confidence">{{ percent(candidate.confidence) }}</span>
            </div>
            <div class="meta-line">
              <span>{{ directionText(candidate.direction) }}</span>
              <span>{{ candidate.distance }} 跳</span>
              <span>{{ reasonText(candidate.reason) }}</span>
            </div>
            <div v-if="candidate.path?.symbols?.length" class="path-chain">
              <template v-for="(symbol, index) in candidate.path.symbols" :key="`${candidateKey(candidate)}-${index}`">
                <span>{{ symbolName(symbol) }}</span>
                <b v-if="index < candidate.path.symbols.length - 1">{{ candidate.path.edgeTypes?.[index] || '→' }}</b>
              </template>
            </div>
            <p v-if="llmJudgement(candidate)" class="llm-note">
              LLM：{{ llmText(llmJudgement(candidate)?.decision) }} · {{ percent(llmJudgement(candidate)?.confidence || 0) }}
            </p>
          </article>
          <AppPagination
            v-model:page="pages.candidates"
            v-model:page-size="pageSizes.candidates"
            :total="filteredCandidates.length"
            item-name="候选"
            :page-sizes="[10, 20, 50]"
          />
        </section>

        <section class="result-block trace-block">
          <div class="block-head">
            <h3>业务追溯映射</h3>
            <span>{{ result.traceability.affectedSymbols.length }} 个命中符号</span>
          </div>
          <div v-if="result.traceability.affectedSymbols.length" class="symbol-hit-strip">
            <span v-for="symbol in visibleAffectedSymbols" :key="symbol" :title="symbol">{{ symbolName(symbol) }}</span>
            <b v-if="hiddenAffectedSymbolCount > 0">+{{ hiddenAffectedSymbolCount }}</b>
          </div>
          <div
            v-if="result.traceability.affectedSymbols.length && !result.traceability.affectedCriteria.length && !result.traceability.affectedTestcases.length"
            class="empty-inline trace-warning"
          >
            已识别代码影响符号，但当前基线没有匹配到验收标准或测试用例追溯链接。
          </div>
          <div class="trace-columns">
            <div>
              <h4>受影响验收标准</h4>
              <div v-if="!filteredCriteria.length" class="empty-inline">暂无匹配的验收标准。</div>
              <details v-for="criterion in paginatedCriteria" :key="criterion.id" class="trace-card trace-item-detail">
                <summary>
                  <span>
                    <strong>{{ criterion.requirementKey }} / {{ criterion.acKey }}</strong>
                    <em>{{ criterion.title || criterion.content }}</em>
                  </span>
                  <b>详情</b>
                </summary>
                <div class="trace-inline-detail">
                  <p>{{ criterion.content || criterion.title || '暂无内容' }}</p>
                  <div class="detail-meta">
                    <span v-if="criterion.priority">优先级 {{ criterion.priority }}</span>
                    <span>{{ criterion.testable ? '可测试' : '不可测试' }}</span>
                    <span v-if="criterion.ambiguity">存在歧义</span>
                    <span>置信度 {{ percent(criterion.confidence) }}</span>
                    <span v-if="criterion.sourceLocator">{{ criterion.sourceLocator }}</span>
                  </div>
                </div>
              </details>
              <AppPagination
                v-model:page="pages.criteria"
                v-model:page-size="pageSizes.criteria"
                :total="filteredCriteria.length"
                item-name="标准"
                :page-sizes="[5, 10, 20]"
              />
            </div>
            <div>
              <h4>建议回归用例</h4>
              <div v-if="!filteredTestcases.length" class="empty-inline">暂无匹配的测试用例。</div>
              <details v-for="testcase in paginatedTestcases" :key="testcase.id" class="trace-card trace-item-detail">
                <summary>
                  <span>
                    <strong>{{ testcase.externalKey }}</strong>
                    <em>{{ testcase.title }}</em>
                  </span>
                  <b>详情</b>
                </summary>
                <div class="trace-inline-detail">
                  <dl>
                    <template v-if="testcase.requirementRefs"><dt>关联需求</dt><dd>{{ testcase.requirementRefs }}</dd></template>
                    <template v-if="testcase.preconditions"><dt>前置条件</dt><dd>{{ testcase.preconditions }}</dd></template>
                    <template v-if="testcase.steps"><dt>执行步骤</dt><dd>{{ testcase.steps }}</dd></template>
                    <template v-if="testcase.testData"><dt>测试数据</dt><dd>{{ testcase.testData }}</dd></template>
                    <template v-if="testcase.expected"><dt>预期结果</dt><dd>{{ testcase.expected }}</dd></template>
                    <template v-if="testcase.sourceLocator"><dt>来源定位</dt><dd>{{ testcase.sourceLocator }}</dd></template>
                  </dl>
                </div>
              </details>
              <AppPagination
                v-model:page="pages.testcases"
                v-model:page-size="pageSizes.testcases"
                :total="filteredTestcases.length"
                item-name="用例"
                :page-sizes="[5, 10, 20]"
              />
            </div>
          </div>
          <details v-if="result.traceability.affectedSymbols.length" class="trace-detail-panel">
            <summary>查看命中代码符号</summary>
            <div class="trace-detail-grid">
              <section>
                <h4>命中代码符号</h4>
                <div class="detail-list compact">
                  <code v-for="symbol in result.traceability.affectedSymbols" :key="symbol">{{ symbol }}</code>
                </div>
              </section>
            </div>
          </details>
        </section>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { fetchGitChangeImpactJob, fetchGitImpactLlmReview, fetchVerificationOverview, startGitChangeImpactJob, type GitChangeImpactResponse, type GitImpactAnalysisJob, type GitImpactLlmReviewProgress, type GitImpactReport, type VerificationOverview } from '@/api/verification'
import AppPagination from '@/components/AppPagination.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useToast } from '@/composables/useToast'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const toast = useToast()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.contextByProjectId[projectId.value]?.apps || [])
const overview = ref<VerificationOverview>({ requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] })
const result = ref<GitChangeImpactResponse | null>(null)
const analysisJob = ref<GitImpactAnalysisJob | null>(null)
const llmReview = ref<GitImpactLlmReviewProgress | null>(null)
let analysisPollTimer: number | undefined
let llmPollTimer: number | undefined
const loading = ref(false)
const error = ref('')
const form = reactive({ baselineId: '', appId: '', baseCommit: '', headCommit: '' })
const resultFilters = reactive({ keyword: '', classification: '' })
const pages = reactive({ files: 1, direct: 1, candidates: 1, criteria: 1, testcases: 1 })
const pageSizes = reactive({ files: 10, direct: 10, candidates: 10, criteria: 5, testcases: 5 })
const canAnalyze = computed(() => !!form.baselineId && !!form.appId && !!form.baseCommit && !!form.headCommit)
const transitiveCandidates = computed(() => (result.value?.report.candidates || [])
  .filter(candidate => candidate.classification !== 'DIRECT')
  .sort((a, b) => b.confidence - a.confidence || a.distance - b.distance))
const llmReviewText = computed(() => {
  if (!llmReview.value) return '未开始'
  return ({
    PENDING: '排队中',
    RUNNING: '后台审阅中',
    COMPLETED: '已完成',
    FAILED: '失败',
    UNAVAILABLE: '不可用',
    NOT_FOUND: '未找到',
  } as Record<string, string>)[llmReview.value.status] || llmReview.value.status
})
const jobStatusText = computed(() => {
  if (!analysisJob.value) return '未开始'
  return ({ PENDING: '排队中', RUNNING: '分析中', COMPLETED: '已完成', FAILED: '失败' } as Record<string, string>)[analysisJob.value.status] || analysisJob.value.status
})
const keyword = computed(() => resultFilters.keyword.toLowerCase())
const filteredFiles = computed(() => (result.value?.report.changeSet.files || []).filter(file => matchesKeyword([filePath(file), file.changeType, file.language])))
const filteredDirectChanges = computed(() => (result.value?.report.directChanges || []).filter(change => matchesKeyword([
  changeKey(change),
  change.changeType,
  change.facets.join(' '),
  activeSymbol(change)?.path,
  activeSymbol(change)?.qualifiedName,
  activeSymbol(change)?.signature,
])))
const filteredCandidates = computed(() => transitiveCandidates.value.filter(candidate => {
  const matchesType = !resultFilters.classification || candidate.classification === resultFilters.classification
  return matchesType && matchesKeyword([candidate.seedSymbol, candidate.targetSymbol, candidate.classification, candidate.direction, candidate.reason])
}))
const filteredCriteria = computed(() => (result.value?.traceability.affectedCriteria || []).filter(criterion => matchesKeyword([
  criterion.requirementKey,
  criterion.acKey,
  criterion.title,
  criterion.content,
])))
const filteredTestcases = computed(() => (result.value?.traceability.affectedTestcases || []).filter(testcase => matchesKeyword([
  testcase.externalKey,
  testcase.title,
])))
const paginatedFiles = computed(() => paginate(filteredFiles.value, pages.files, pageSizes.files))
const paginatedDirectChanges = computed(() => paginate(filteredDirectChanges.value, pages.direct, pageSizes.direct))
const paginatedCandidates = computed(() => paginate(filteredCandidates.value, pages.candidates, pageSizes.candidates))
const paginatedCriteria = computed(() => paginate(filteredCriteria.value, pages.criteria, pageSizes.criteria))
const paginatedTestcases = computed(() => paginate(filteredTestcases.value, pages.testcases, pageSizes.testcases))
const visibleAffectedSymbols = computed(() => (result.value?.traceability.affectedSymbols || []).slice(0, 8))
const hiddenAffectedSymbolCount = computed(() => Math.max(0, (result.value?.traceability.affectedSymbols.length || 0) - visibleAffectedSymbols.value.length))

async function loadOverview() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectContext(projectId.value)
    overview.value = await fetchVerificationOverview(projectId.value)
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function analyze() {
  if (loading.value) return
  if (!canAnalyze.value) return
  loading.value = true
  error.value = ''
  result.value = null
  llmReview.value = null
  stopAnalysisPolling()
  stopLlmReviewPolling()
  try {
    analysisJob.value = await startGitChangeImpactJob(projectId.value, form.baselineId, {
      appId: form.appId, baseCommit: form.baseCommit, headCommit: form.headCommit,
    })
    resetPages()
    startAnalysisPolling(analysisJob.value.jobId)
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
    loading.value = false
    analysisJob.value = null
    stopAnalysisPolling()
  } finally {
  }
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '请求失败，请稍后重试'
}

watch(() => [resultFilters.keyword, resultFilters.classification], resetPages)

async function pollAnalysisJob(jobId: string) {
  try {
    const job = await fetchGitChangeImpactJob(projectId.value, jobId)
    if (analysisJob.value?.jobId && analysisJob.value.jobId !== jobId) return
    analysisJob.value = job
    if (job.status === 'COMPLETED' && job.result) {
      result.value = job.result
      resetPages()
      loading.value = false
      stopAnalysisPolling()
      startLlmReviewPolling(job.result.report.id)
      toast.success('Git 变更影响分析完成')
    } else if (job.status === 'FAILED') {
      error.value = job.error || job.message || 'Git 影响分析失败'
      loading.value = false
      stopAnalysisPolling()
      toast.error(error.value)
    }
  } catch (err) {
    error.value = messageOf(err)
    loading.value = false
    stopAnalysisPolling()
    toast.error(error.value)
  }
}

function startAnalysisPolling(jobId: string) {
  stopAnalysisPolling()
  void pollAnalysisJob(jobId)
  analysisPollTimer = window.setInterval(() => void pollAnalysisJob(jobId), 1000)
}

function stopAnalysisPolling() {
  if (analysisPollTimer) window.clearInterval(analysisPollTimer)
  analysisPollTimer = undefined
}

async function pollLlmReview(reportId: string) {
  try {
    const progress = await fetchGitImpactLlmReview(projectId.value, reportId)
    if (result.value?.report.id !== reportId) return
    llmReview.value = progress
    result.value.report.llmJudgements = progress.judgements || []
    if (['COMPLETED', 'FAILED', 'UNAVAILABLE', 'NOT_FOUND'].includes(progress.status)) stopLlmReviewPolling()
  } catch (err) {
    stopLlmReviewPolling()
  }
}

function startLlmReviewPolling(reportId: string) {
  stopLlmReviewPolling()
  void pollLlmReview(reportId)
  llmPollTimer = window.setInterval(() => void pollLlmReview(reportId), 2500)
}

function stopLlmReviewPolling() {
  if (llmPollTimer) window.clearInterval(llmPollTimer)
  llmPollTimer = undefined
}

function resetPages() {
  pages.files = 1
  pages.direct = 1
  pages.candidates = 1
  pages.criteria = 1
  pages.testcases = 1
}

function activeSymbol(change: GitImpactReport['directChanges'][number]) {
  return change.newSymbol || change.oldSymbol
}

function changeKey(change: GitImpactReport['directChanges'][number]) {
  return change.symbolKey || change.newKey || change.oldKey || activeSymbol(change)?.key || ''
}

function badgeClass(value: string) {
  const normalized = value.toLowerCase()
  if (normalized.includes('delete') || normalized.includes('reject')) return 'danger'
  if (normalized.includes('add') || normalized.includes('confirm')) return 'success'
  if (normalized.includes('possible') || normalized.includes('unknown')) return 'warning'
  return 'neutral'
}

function candidateKey(candidate: GitImpactReport['candidates'][number]) {
  return `${candidate.seedSymbol}->${candidate.targetSymbol}:${candidate.direction}:${candidate.distance}`
}

function classificationClass(value: string) {
  if (value === 'TRANSITIVE') return 'success'
  if (value === 'POSSIBLE' || value === 'UNKNOWN') return 'warning'
  return 'neutral'
}

function classificationText(value: string) {
  return ({ DIRECT: '直接变更', TRANSITIVE: '确定传播', POSSIBLE: '可能影响', UNKNOWN: '待确认' } as Record<string, string>)[value] || value
}

function directionText(value: string) {
  return ({ UPSTREAM: '上游调用方', DOWNSTREAM: '下游依赖方', TRACEABILITY: '追溯映射' } as Record<string, string>)[value] || value
}

function facetText(value: string) {
  return ({
    BODY: '方法体',
    CONTROL_FLOW: '控制流',
    CALL: '调用关系',
    FIELD: '字段',
    ANNOTATION: '注解',
    VISIBILITY: '可见性',
    RETURN_TYPE: '返回值',
    PARAMETER: '参数',
    EXCEPTION: '异常',
    CONSTANT: '常量',
    SQL: 'SQL',
    CONFIG: '配置',
  } as Record<string, string>)[value] || value
}

function filePath(file: GitImpactReport['changeSet']['files'][number]) {
  if (file.changeType === 'RENAME' && file.oldPath && file.newPath) return `${file.oldPath} → ${file.newPath}`
  return file.newPath && file.newPath !== '/dev/null' ? file.newPath : file.oldPath || '未知文件'
}

function formatRanges(ranges?: Array<{ startLine: number; endLine: number }>) {
  if (!ranges?.length) return ''
  return ranges.map(rangeText).filter(Boolean).join(', ')
}

function llmJudgement(candidate: GitImpactReport['candidates'][number]) {
  const id = `${candidate.seedSymbol}->${candidate.targetSymbol}`
  return result.value?.report.llmJudgements.find(item => item.candidateId === id)
}

function llmText(value?: string) {
  return ({ CONFIRM: '确认', REJECT: '否决', UNCERTAIN: '不确定' } as Record<string, string>)[value || ''] || '未判定'
}

function matchesKeyword(values: Array<string | undefined | null>) {
  if (!keyword.value) return true
  return values.some(value => value?.toLowerCase().includes(keyword.value))
}

function paginate<T>(items: T[], page: number, pageSize: number) {
  const start = (Math.max(1, page) - 1) * Math.max(1, pageSize)
  return items.slice(start, start + Math.max(1, pageSize))
}

function percent(value: number) {
  return `${Math.round((value || 0) * 100)}%`
}

function rangeText(range?: { startLine: number; endLine: number }) {
  if (!range) return ''
  return range.startLine === range.endLine ? String(range.startLine) : `${range.startLine}-${range.endLine}`
}

function reasonText(value?: string) {
  return ({
    'Tree-sitter call-site candidate': '静态调用点候选',
    'golden-call': '静态调用关系',
  } as Record<string, string>)[value || ''] || value || '规则传播'
}

function snippetLines(change: GitImpactReport['directChanges'][number]) {
  const snippet = activeSymbol(change)?.snippet || ''
  const startLine = snippetStartLine(change)
  return snippet.split(/\r?\n/).map((text, index) => ({
    key: `${startLine + index}:${index}:${text}`,
    number: startLine + index,
    text,
  }))
}

function snippetStartLine(change: GitImpactReport['directChanges'][number]) {
  return change.evidenceRanges?.[0]?.startLine || activeSymbol(change)?.range?.startLine || 1
}

function shortCommit(value?: string) {
  return value ? value.slice(0, 8) : 'unknown'
}

function stageText(value: string) {
  return ({
    PENDING: '等待执行',
    STARTING: '启动任务',
    PREPARING: '准备分析',
    FETCHING_DIFF: '读取 Git Diff',
    READING_CONTENT: '读取文件内容',
    ANALYZING_FILES: '解析变更文件',
    PROPAGATING: '传播影响范围',
    SCHEDULING_LLM: '创建 LLM 任务',
    MAPPING_TRACEABILITY: '映射追溯关系',
    COMPLETED: '分析完成',
    FAILED: '分析失败',
  } as Record<string, string>)[value] || value
}

function symbolMeta(change: GitImpactReport['directChanges'][number]) {
  const symbol = activeSymbol(change)
  const parts = [symbol?.kind, symbol?.signature].filter(Boolean)
  return parts.length ? parts.join(' · ') : changeKey(change)
}

function symbolName(symbolKey?: string | null) {
  if (!symbolKey) return '未知符号'
  const cleaned = symbolKey.replace(/^java:\/\//, '')
  const memberIndex = cleaned.indexOf('#')
  if (memberIndex >= 0) {
    const typeName = cleaned.slice(0, memberIndex).split('.').pop()
    return `${typeName}.${cleaned.slice(memberIndex + 1)}`
  }
  return cleaned.split('.').pop() || cleaned
}

function typeText(value: string) {
  return ({ ADD: '新增', MODIFY: '修改', DELETE: '删除', RENAME: '重命名', COPY: '复制', MOVE: '移动', SIGNATURE_CHANGE: '签名变更' } as Record<string, string>)[value] || value
}

onMounted(loadOverview)
onBeforeUnmount(() => {
  stopAnalysisPolling()
  stopLlmReviewPolling()
})
</script>

<style scoped>
.git-impact-page { display: grid; gap: 18px; }
.page-header,
.header-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions {
  justify-content: flex-end;
}
@keyframes refresh-spin {
  to { transform: rotate(360deg); }
}
.form-stack { display: grid; gap: 14px; }
.analyze-action-row {
  display: flex;
  justify-content: flex-end;
}
.analyze-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-height: 48px;
  width: fit-content;
  min-width: 220px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-primary-hover));
  color: #fff;
  box-shadow: 0 10px 22px rgba(var(--oat-primary-rgb), .22);
  font-weight: 800;
  line-height: 1;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, opacity .16s ease;
}
.analyze-button:hover:not(:disabled) {
  box-shadow: 0 14px 28px rgba(var(--oat-primary-rgb), .30);
  transform: translateY(-2px);
}
.analyze-button:active:not(:disabled) {
  box-shadow: 0 5px 12px rgba(var(--oat-primary-rgb), .18);
  transform: translateY(0) scale(.98);
}
.analyze-button:focus-visible {
  outline: none;
  box-shadow: var(--oat-focus-ring), 0 10px 22px rgba(var(--oat-primary-rgb), .22);
}
.analyze-button:disabled {
  border-color: var(--oat-border);
  background: var(--oat-surface-soft);
  color: var(--oat-text-muted);
  box-shadow: none;
  opacity: 1;
}
.analyze-icon {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}
.analyze-icon.spinning {
  animation: refresh-spin .8s linear infinite;
}
.analyze-hint {
  margin-left: 3px;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
}
.analysis-progress { display: grid; gap: 8px; padding: 12px; border: 1px solid rgba(37, 99, 235, .18); border-radius: 8px; background: #eff6ff; }
.analysis-progress.completed { border-color: rgba(22, 163, 74, .22); background: #f0fdf4; }
.analysis-progress.failed { border-color: rgba(220, 38, 38, .2); background: #fef2f2; }
.progress-top,
.progress-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.progress-top strong { color: var(--oat-text); overflow-wrap: anywhere; }
.progress-top span { color: #2563eb; font-weight: 900; font-variant-numeric: tabular-nums; }
.analysis-progress.completed .progress-top span { color: #16a34a; }
.analysis-progress.failed .progress-top span { color: #dc2626; }
.progress-track { overflow: hidden; height: 8px; border-radius: 999px; background: rgba(37, 99, 235, .13); }
.progress-track div { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #2563eb, #0f766e); transition: width .25s ease; }
.progress-meta { color: var(--oat-text-muted); font-size: 12px; font-weight: 700; }
.inline-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.form-stack label { display: grid; gap: 7px; color: #475569; font-size: 13px; font-weight: 700; }
.form-stack input, .form-stack select { min-height: 42px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 10px; padding: 9px 11px; background: #fff; color: #1e293b; font: inherit; }
.result-panel { display: grid; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.summary-item { display: grid; gap: 2px; min-height: 72px; align-content: center; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
.summary-item strong { font-size: 24px; line-height: 1; color: var(--oat-text); }
.summary-item span { color: var(--oat-text-muted); font-size: 12px; font-weight: 700; }
.summary-help { width: fit-content; cursor: help; text-decoration: underline dotted; text-underline-offset: 3px; }
.commit-strip { display: grid; grid-template-columns: 1fr 1fr auto; gap: 10px; padding: 12px; border: 1px solid rgba(15, 118, 110, .14); border-radius: 8px; background: #f0fdfa; }
.commit-strip div { display: grid; gap: 4px; min-width: 0; }
.commit-strip span { color: #0f766e; font-size: 12px; font-weight: 800; }
.commit-strip code { overflow: hidden; color: #134e4a; text-overflow: ellipsis; white-space: nowrap; }
.llm-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; color: var(--oat-text-muted); font-size: 13px; }
.llm-status strong { color: var(--oat-text); }
.llm-status.running,
.llm-status.pending { border-color: rgba(37, 99, 235, .2); background: #eff6ff; }
.llm-status.completed { border-color: rgba(22, 163, 74, .22); background: #f0fdf4; }
.llm-status.failed,
.llm-status.unavailable,
.llm-status.not_found { border-color: rgba(217, 119, 6, .24); background: #fffbeb; }
.result-toolbar { display: grid; grid-template-columns: minmax(280px, 1fr) 180px; gap: 12px; }
.result-toolbar label { display: grid; gap: 6px; color: #475569; font-size: 12px; font-weight: 800; }
.result-toolbar input,
.result-toolbar select { min-height: 38px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 8px; padding: 8px 10px; background: #fff; color: #1e293b; font: inherit; }
.result-layout {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.result-block {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}
.files-block,
.trace-block { grid-column: span 12; }
.direct-block,
.candidates-block { grid-column: span 6; }
.block-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 9px; border-bottom: 1px solid rgba(15, 23, 42, .08); }
.block-head h3,
.trace-columns h4 { margin: 0; color: var(--oat-text); font-size: 15px; }
.block-head span { color: var(--oat-text-muted); font-size: 12px; }
.file-row,
.change-card,
.candidate-card,
.trace-card { display: grid; gap: 8px; min-width: 0; overflow: hidden; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
.result-block > .file-row,
.result-block > .change-card,
.result-block > .candidate-card,
.result-block .trace-card { background: #f8fafc; }
.file-row {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
}
.file-row .row-main { align-items: center; }
.file-row .meta-line {
  grid-column: 1 / -1;
  padding-left: 62px;
}
.row-main,
.change-title,
.candidate-top { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.row-main strong,
.change-title strong,
.candidate-top strong,
.trace-card strong { min-width: 0; overflow-wrap: anywhere; color: #0f766e; }
.change-title div { display: grid; gap: 2px; min-width: 0; }
.change-title div span,
.trace-card span { color: var(--oat-text-muted); font-size: 13px; overflow-wrap: anywhere; }
.badge { flex: 0 0 auto; min-width: 52px; border-radius: 999px; padding: 3px 8px; text-align: center; font-size: 12px; font-weight: 800; }
.badge.neutral { background: #eef2ff; color: #3730a3; }
.badge.success { background: #dcfce7; color: #166534; }
.badge.warning { background: #fef3c7; color: #92400e; }
.badge.danger { background: #fee2e2; color: #991b1b; }
.meta-line { display: flex; flex-wrap: wrap; gap: 7px 12px; color: var(--oat-text-muted); font-size: 12px; }
.meta-line span { overflow-wrap: anywhere; }
.facet-list { display: flex; flex-wrap: wrap; gap: 6px; }
.facet-list span { border-radius: 6px; padding: 3px 7px; background: var(--oat-surface-soft); color: #334155; font-size: 12px; font-weight: 700; }
.snippet-box { min-width: 0; overflow: hidden; border-top: 1px solid rgba(15, 23, 42, .08); padding-top: 8px; }
.snippet-box summary { color: #0f766e; cursor: pointer; font-size: 13px; font-weight: 800; }
.snippet-code {
  width: 100%;
  max-width: 100%;
  max-height: 260px;
  overflow: auto;
  box-sizing: border-box;
  margin: 8px 0 0;
  border-radius: 8px;
  padding: 10px 0;
  background: #0f172a;
  color: #e2e8f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.55;
}
.snippet-line {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 12px;
  min-width: max-content;
  padding: 0 12px 0 0;
}
.snippet-line-number {
  position: sticky;
  left: 0;
  padding: 0 10px;
  background: #0f172a;
  color: #64748b;
  text-align: right;
  user-select: none;
}
.snippet-line code {
  display: block;
  white-space: pre;
}
.confidence { margin-left: auto; color: #0f766e; font-weight: 800; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 9px; border-radius: 8px; background: #f8fafc; color: #334155; font-size: 12px; }
.path-chain span { overflow-wrap: anywhere; }
.path-chain b { color: #64748b; font-size: 11px; }
.llm-note { margin: 0; color: var(--oat-text-muted); font-size: 13px; }
.symbol-hit-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 10px;
  border: 1px solid rgba(15, 118, 110, .12);
  border-radius: 8px;
  background: #ecfeff;
}
.symbol-hit-strip span,
.symbol-hit-strip b {
  max-width: 100%;
  overflow: hidden;
  border: 1px solid rgba(15, 118, 110, .16);
  border-radius: 999px;
  padding: 3px 8px;
  background: #fff;
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-warning {
  padding: 10px 12px;
  border: 1px solid rgba(217, 119, 6, .18);
  border-radius: 8px;
  background: #fffbeb;
  color: #92400e;
}
.trace-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.trace-columns > div {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
  padding-top: 2px;
}
.trace-detail-panel {
  overflow: hidden;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: #fff;
}
.trace-detail-panel summary {
  padding: 11px 12px;
  color: #0f766e;
  cursor: pointer;
  font-size: 13px;
  font-weight: 900;
}
.trace-detail-panel[open] summary {
  border-bottom: 1px solid rgba(15, 23, 42, .08);
}
.trace-item-detail {
  align-content: start;
}
.trace-item-detail summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  cursor: pointer;
  list-style: none;
}
.trace-item-detail summary::-webkit-details-marker {
  display: none;
}
.trace-item-detail summary > span {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.trace-item-detail summary em {
  color: var(--oat-text-muted);
  font-size: 13px;
  font-style: normal;
  overflow-wrap: anywhere;
}
.trace-item-detail summary b {
  border-radius: 999px;
  padding: 2px 8px;
  background: #fff;
  color: #0f766e;
  font-size: 12px;
  font-weight: 900;
}
.trace-item-detail[open] summary {
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(15, 23, 42, .08);
}
.trace-inline-detail {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding-top: 2px;
}
.trace-inline-detail p,
.trace-inline-detail dd {
  margin: 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.trace-inline-detail dl {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
}
.trace-inline-detail dt {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
}
.trace-detail-grid {
  display: grid;
  gap: 14px;
  padding: 12px;
}
.trace-detail-grid section {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.trace-detail-grid h4 {
  margin: 0;
  color: var(--oat-text);
  font-size: 13px;
}
.detail-list {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}
.detail-list code {
  max-width: 100%;
  overflow: hidden;
  border-radius: 6px;
  padding: 4px 7px;
  background: #f1f5f9;
  color: #334155;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-card {
  display: grid;
  gap: 7px;
  min-width: 0;
  padding: 11px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: #f8fafc;
}
.detail-card strong {
  color: #0f766e;
  overflow-wrap: anywhere;
}
.detail-card p,
.detail-card dd {
  margin: 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.detail-card dl {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
}
.detail-card dt {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.detail-meta span {
  border-radius: 999px;
  padding: 2px 7px;
  background: #fff;
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}
.empty-inline { padding: 10px 0; color: #64748b; font-size: 13px; }
@media (max-width: 1120px) {
  .direct-block,
  .candidates-block { grid-column: span 12; }
}
@media (max-width: 760px) {
  .inline-grid { grid-template-columns: 1fr; }
  .summary-grid,
  .commit-strip,
  .result-toolbar,
  .result-layout,
  .trace-columns { grid-template-columns: 1fr; }
  .files-block,
  .trace-block,
  .direct-block,
  .candidates-block { grid-column: auto; }
  .analyze-action-row { justify-content: stretch; }
  .analyze-button { width: 100%; }
  .file-row .meta-line { padding-left: 0; }
  .detail-card dl,
  .trace-inline-detail dl { grid-template-columns: 1fr; }
  .candidate-top { display: grid; }
  .confidence { margin-left: 0; }
}
</style>
