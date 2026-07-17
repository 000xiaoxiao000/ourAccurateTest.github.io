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
    </section>

    <section v-if="result" class="panel-section result-panel">
      <div class="section-head">
        <h2>影响结果</h2>
        <span>{{ shortCommit(result.report.changeSet.baseCommit) }} → {{ shortCommit(result.report.changeSet.headCommit) }}</span>
      </div>

      <div class="summary-grid">
        <div class="summary-item"><strong>{{ result.report.changeSet.files.length }}</strong><span>变更文件</span></div>
        <div class="summary-item"><strong>{{ result.report.directChanges.length }}</strong><span>直接结构变更</span></div>
        <div class="summary-item"><strong>{{ transitiveCandidates.length }}</strong><span>传播候选</span></div>
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
        <section class="result-block">
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

        <section class="result-block">
          <div class="block-head">
            <h3>直接结构变更</h3>
            <span>{{ filteredDirectChanges.length }} / {{ result.report.directChanges.length }} 项</span>
          </div>
          <div v-if="!filteredDirectChanges.length" class="empty-inline">没有匹配的结构变更。</div>
          <article v-for="change in paginatedDirectChanges" :key="change.symbolKey" class="change-card">
            <div class="change-title">
              <span :class="['badge', badgeClass(change.changeType)]">{{ typeText(change.changeType) }}</span>
              <div>
                <strong>{{ symbolName(change.symbolKey) }}</strong>
                <span>{{ symbolMeta(change) }}</span>
              </div>
            </div>
            <div class="facet-list">
              <span v-for="facet in change.facets" :key="facet">{{ facetText(facet) }}</span>
            </div>
            <div class="meta-line">
              <span v-if="activeSymbol(change)?.path">{{ activeSymbol(change)?.path }}</span>
              <span v-if="rangeText(activeSymbol(change)?.range)">行 {{ rangeText(activeSymbol(change)?.range) }}</span>
              <span v-if="formatRanges(change.evidenceRanges)">证据 {{ formatRanges(change.evidenceRanges) }}</span>
            </div>
            <details v-if="activeSymbol(change)?.snippet" class="snippet-box">
              <summary>查看源码片段</summary>
              <pre>{{ activeSymbol(change)?.snippet }}</pre>
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

        <section class="result-block">
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
              <span>{{ candidate.reason || '规则传播' }}</span>
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

        <section class="result-block">
          <div class="block-head">
            <h3>业务追溯映射</h3>
            <span>{{ result.traceability.affectedSymbols.length }} 个命中符号</span>
          </div>
          <div class="trace-columns">
            <div>
              <h4>受影响验收标准</h4>
              <div v-if="!filteredCriteria.length" class="empty-inline">暂无匹配的验收标准。</div>
              <article v-for="criterion in paginatedCriteria" :key="criterion.id" class="trace-card">
                <strong>{{ criterion.requirementKey }} / {{ criterion.acKey }}</strong>
                <span>{{ criterion.title || criterion.content }}</span>
              </article>
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
              <article v-for="testcase in paginatedTestcases" :key="testcase.id" class="trace-card">
                <strong>{{ testcase.externalKey }}</strong>
                <span>{{ testcase.title }}</span>
              </article>
              <AppPagination
                v-model:page="pages.testcases"
                v-model:page-size="pageSizes.testcases"
                :total="filteredTestcases.length"
                item-name="用例"
                :page-sizes="[5, 10, 20]"
              />
            </div>
          </div>
        </section>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { analyzeGitChangeImpact, fetchGitImpactLlmReview, fetchVerificationOverview, type GitChangeImpactResponse, type GitImpactLlmReviewProgress, type GitImpactReport, type VerificationOverview } from '@/api/verification'
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
const llmReview = ref<GitImpactLlmReviewProgress | null>(null)
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
const keyword = computed(() => resultFilters.keyword.toLowerCase())
const filteredFiles = computed(() => (result.value?.report.changeSet.files || []).filter(file => matchesKeyword([filePath(file), file.changeType, file.language])))
const filteredDirectChanges = computed(() => (result.value?.report.directChanges || []).filter(change => matchesKeyword([
  change.symbolKey,
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

async function loadOverview() {
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
  if (!canAnalyze.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await analyzeGitChangeImpact(projectId.value, form.baselineId, {
      appId: form.appId, baseCommit: form.baseCommit, headCommit: form.headCommit,
    })
    llmReview.value = null
    resetPages()
    startLlmReviewPolling(result.value.report.id)
    toast.success('Git 变更影响分析完成')
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    loading.value = false
  }
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '请求失败，请稍后重试'
}

watch(() => [resultFilters.keyword, resultFilters.classification], resetPages)

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

function matchesKeyword(values: Array<string | undefined>) {
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

function shortCommit(value?: string) {
  return value ? value.slice(0, 8) : 'unknown'
}

function symbolMeta(change: GitImpactReport['directChanges'][number]) {
  const symbol = activeSymbol(change)
  const parts = [symbol?.kind, symbol?.signature].filter(Boolean)
  return parts.length ? parts.join(' · ') : change.symbolKey
}

function symbolName(symbolKey: string) {
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
onBeforeUnmount(stopLlmReviewPolling)
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
.inline-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.form-stack label { display: grid; gap: 7px; color: #475569; font-size: 13px; font-weight: 700; }
.form-stack input, .form-stack select { min-height: 42px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 10px; padding: 9px 11px; background: #fff; color: #1e293b; font: inherit; }
.result-panel { display: grid; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.summary-item { display: grid; gap: 2px; min-height: 72px; align-content: center; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
.summary-item strong { font-size: 24px; line-height: 1; color: var(--oat-text); }
.summary-item span { color: var(--oat-text-muted); font-size: 12px; font-weight: 700; }
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
.result-layout { display: grid; gap: 18px; }
.result-block { display: grid; gap: 10px; }
.block-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 8px; border-bottom: 1px solid rgba(15, 23, 42, .08); }
.block-head h3,
.trace-columns h4 { margin: 0; color: var(--oat-text); font-size: 15px; }
.block-head span { color: var(--oat-text-muted); font-size: 12px; }
.file-row,
.change-card,
.candidate-card,
.trace-card { display: grid; gap: 8px; padding: 12px; border: 1px solid rgba(15, 23, 42, .08); border-radius: 8px; background: #fff; }
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
.snippet-box { border-top: 1px solid rgba(15, 23, 42, .08); padding-top: 8px; }
.snippet-box summary { color: #0f766e; cursor: pointer; font-size: 13px; font-weight: 800; }
.snippet-box pre { max-height: 260px; overflow: auto; margin: 8px 0 0; border-radius: 8px; padding: 10px; background: #0f172a; color: #e2e8f0; font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.confidence { margin-left: auto; color: #0f766e; font-weight: 800; }
.path-chain { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 9px; border-radius: 8px; background: var(--oat-surface-soft); color: #334155; font-size: 12px; }
.path-chain span { overflow-wrap: anywhere; }
.path-chain b { color: #64748b; font-size: 11px; }
.llm-note { margin: 0; color: var(--oat-text-muted); font-size: 13px; }
.trace-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.trace-columns > div { display: grid; align-content: start; gap: 8px; }
.empty-inline { padding: 10px 0; color: #64748b; font-size: 13px; }
@media (max-width: 760px) {
  .inline-grid { grid-template-columns: 1fr; }
  .summary-grid,
  .commit-strip,
  .result-toolbar,
  .trace-columns { grid-template-columns: 1fr; }
  .analyze-action-row { justify-content: stretch; }
  .analyze-button { width: 100%; }
  .candidate-top { display: grid; }
  .confidence { margin-left: 0; }
}
</style>
