<template>
  <section class="verification-page">
    <header class="page-header plain-header verification-header">
      <div>
        <div class="eyebrow">AI Requirement Verification</div>
        <h1>AI 需求一致性验证</h1>
        <p class="subtext">按基线冻结需求、用例、源码和运行证据，生成可审核的双向追溯矩阵。</p>
      </div>
      <div class="header-actions">
        <button type="button" class="secondary-button" :disabled="loading" @click="loadOverview">刷新</button>
        <button type="button" class="secondary-button" :disabled="!selectedBaselineId" @click="markStale">标记过期</button>
        <button type="button" class="primary-button" :disabled="!selectedBaselineId || analyzing" @click="runAnalysis">
          {{ analyzing ? '分析中...' : '运行 AI 分析' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <section class="workspace-grid">
      <aside class="control-panel">
        <section class="panel-section">
          <div class="section-head">
            <h2>1. 接入快照</h2>
            <span>文件 / 粘贴 / 标准视图</span>
          </div>
          <div class="asset-import-grid">
            <article v-for="asset in assetInputs" :key="asset.type" class="asset-import">
              <div class="asset-title">
                <strong>{{ asset.label }}</strong>
                <span>{{ asset.hint }}</span>
              </div>
              <input type="file" @change="onFileChange(asset.type, $event)" />
              <textarea v-model="pasteInputs[asset.type]" :placeholder="asset.placeholder"></textarea>
              <input v-model.trim="sourceVersions[asset.type]" type="text" placeholder="外部版本 / Commit / 批次号（可选）" />
              <button type="button" :disabled="importing === asset.type || !hasImportInput(asset.type)" @click="importAsset(asset.type)">
                {{ importing === asset.type ? '导入中...' : '导入快照' }}
              </button>
            </article>
          </div>
          <div class="git-import">
            <div class="section-head">
              <h3>Git 源码快照</h3>
              <span>应用仓库或手填仓库</span>
            </div>
            <div class="inline-grid">
              <label>
                <span>应用</span>
                <select v-model="gitForm.appId">
                  <option value="">不使用应用配置</option>
                  <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
                </select>
              </label>
              <label>
                <span>分支</span>
                <input v-model.trim="gitForm.branch" type="text" placeholder="main / release" />
              </label>
            </div>
            <label>
              <span>仓库地址</span>
              <input v-model.trim="gitForm.repositoryUrl" type="text" placeholder="未选择应用时填写 Git URL" />
            </label>
            <div class="inline-grid">
              <label>
                <span>用户名</span>
                <input v-model.trim="gitForm.username" type="text" autocomplete="off" />
              </label>
              <label>
                <span>密码 / Token</span>
                <input v-model.trim="gitForm.password" type="password" autocomplete="off" />
              </label>
            </div>
            <div class="inline-grid">
              <label>
                <span>Commit</span>
                <input v-model.trim="gitForm.commit" type="text" placeholder="留空取分支最新" />
              </label>
              <label>
                <span>最大 Java 文件数</span>
                <input v-model.number="gitForm.maxFiles" type="number" min="1" max="500" />
              </label>
            </div>
            <button type="button" class="secondary-button full" :disabled="gitImporting" @click="importGitSource">
              {{ gitImporting ? '拉取中...' : '导入 Git 源码快照' }}
            </button>
          </div>
        </section>

        <section class="panel-section">
          <div class="section-head">
            <h2>2. 冻结基线</h2>
            <span>不可变分析输入</span>
          </div>
          <div class="form-stack">
            <label>
              <span>基线名称</span>
              <input v-model.trim="baselineForm.name" type="text" placeholder="例如：登录模块 V2.0 发布前验证" />
            </label>
            <label>
              <span>需求快照</span>
              <select v-model="baselineForm.requirementAssetId">
                <option value="">请选择</option>
                <option v-for="asset in overview.requirements" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
              </select>
            </label>
            <label>
              <span>用例快照</span>
              <select v-model="baselineForm.testcaseAssetId">
                <option value="">请选择</option>
                <option v-for="asset in overview.testcases" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
              </select>
            </label>
            <label>
              <span>源码快照（可选）</span>
              <select v-model="baselineForm.sourceAssetId">
                <option value="">使用应用静态索引或暂不选择</option>
                <option v-for="asset in overview.sources" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
              </select>
            </label>
            <label>
              <span>应用静态索引（可选）</span>
              <select v-model="baselineForm.sourceAppId">
                <option value="">不绑定应用</option>
                <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
              </select>
            </label>
            <div class="inline-grid">
              <label>
                <span>执行证据</span>
                <select v-model="baselineForm.executionAssetId">
                  <option value="">未选择</option>
                  <option v-for="asset in overview.executions" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
                </select>
              </label>
              <label>
                <span>覆盖率证据</span>
                <select v-model="baselineForm.coverageAssetId">
                  <option value="">未选择</option>
                  <option v-for="asset in overview.coverages" :key="asset.id" :value="asset.id">{{ assetLabel(asset) }}</option>
                </select>
              </label>
            </div>
            <div class="inline-grid">
              <label>
                <span>分支</span>
                <input v-model.trim="baselineForm.sourceBranch" type="text" placeholder="main / release-2.0" />
              </label>
              <label>
                <span>Commit</span>
                <input v-model.trim="baselineForm.sourceCommit" type="text" placeholder="完整 commit SHA" />
              </label>
            </div>
            <button type="button" class="primary-button full" :disabled="creatingBaseline" @click="createBaseline">
              {{ creatingBaseline ? '创建中...' : '创建分析基线' }}
            </button>
          </div>
        </section>

        <section class="panel-section baseline-list">
          <div class="section-head">
            <h2>分析基线</h2>
            <span>{{ overview.baselines.length }} 个</span>
          </div>
          <button
            v-for="baseline in overview.baselines"
            :key="baseline.id"
            type="button"
            class="baseline-item"
            :class="{ active: baseline.id === selectedBaselineId }"
            @click="selectBaseline(baseline.id)"
          >
            <strong>{{ baseline.name }}</strong>
            <span>{{ baseline.status }} · {{ baseline.freshness }} · {{ formatTime(baseline.createTime) }}</span>
          </button>
        </section>
      </aside>

      <main class="result-panel">
        <div v-if="!detail" class="empty-state">
          <strong>选择或创建一个分析基线</strong>
          <span>平台只保存分析快照、证据、AI 发现和人工裁决；需求、用例和 Bug 仍回到外部事实源处理。</span>
        </div>
        <template v-else>
          <section class="metrics-strip">
            <article>
              <span>AC 总数</span>
              <strong>{{ detail.metrics.totalCriteria }}</strong>
            </article>
            <article>
              <span>用例覆盖</span>
              <strong>{{ percent(detail.metrics.testcaseCoverageRate) }}</strong>
            </article>
            <article>
              <span>实现证据</span>
              <strong>{{ percent(detail.metrics.implementationCoverageRate) }}</strong>
            </article>
            <article>
              <span>执行证据</span>
              <strong>{{ percent(detail.metrics.executionEvidenceRate) }}</strong>
            </article>
            <article>
              <span>运行覆盖</span>
              <strong>{{ percent(detail.metrics.runtimeCoverageRate) }}</strong>
            </article>
            <article>
              <span>开放问题</span>
              <strong>{{ detail.metrics.openFindings }}</strong>
            </article>
          </section>

          <section class="gate-band" :class="gateResult?.status.toLowerCase()">
            <div>
              <strong>质量门禁：{{ gateResult?.status || '未计算' }}</strong>
              <span v-if="gateResult?.reasons.length">{{ gateResult.reasons.join('；') }}</span>
              <span v-else>默认门禁要求核心 AC 用例与静态实现闭环，高风险问题为 0；动态证据作为可选增强。</span>
            </div>
            <button type="button" class="secondary-button" :disabled="gating" @click="evaluateGate">
              {{ gating ? '计算中...' : '计算门禁' }}
            </button>
          </section>

          <nav class="tabs" aria-label="验证视图">
            <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">
              {{ tab.label }}
            </button>
          </nav>

          <section v-if="activeTab === 'matrix'" class="matrix-table">
            <div class="table-row table-head">
              <span>验收标准</span>
              <span>测试用例</span>
              <span>代码/运行证据</span>
              <span>结论</span>
            </div>
            <article v-for="row in matrix" :key="row.criterion.id" class="table-row">
              <div>
                <strong>{{ row.criterion.requirementKey }} / {{ row.criterion.acKey }}</strong>
                <p>{{ row.criterion.content }}</p>
              </div>
              <div class="chips">
                <span v-for="testcase in row.testcases" :key="testcase.id">{{ testcase.externalKey }}</span>
                <em v-if="!row.testcases.length">未覆盖</em>
              </div>
              <div class="trace-list">
                <button
                  v-for="link in evidenceLinks(row.criterion.id)"
                  :key="link.id"
                  type="button"
                  :title="JSON.stringify(link.evidence || {})"
                  @click="confirmTrace(link.id)"
                >
                  {{ link.targetType }} · {{ link.evidenceLevel }} · {{ link.reviewStatus }}
                </button>
              </div>
              <div>
                <span class="verdict" :class="row.verdict.toLowerCase()">{{ verdictText(row.verdict) }}</span>
                <small>{{ row.evidenceLevel }}</small>
              </div>
            </article>
          </section>

          <section v-else-if="activeTab === 'findings'" class="finding-list">
            <div class="finding-toolbar">
              <select v-model="findingPerspective">
                <option value="">全部视角</option>
                <option value="PRODUCT">产品</option>
                <option value="TEST">测试</option>
                <option value="DEVELOPMENT">开发</option>
                <option value="CROSS">交叉</option>
              </select>
            </div>
            <article v-for="finding in filteredFindings" :key="finding.id" class="finding-card" :class="finding.severity.toLowerCase()">
              <div class="finding-main">
                <span>{{ finding.perspective }} · {{ finding.severity }} · {{ finding.reviewStatus }}</span>
                <h3>{{ finding.title }}</h3>
                <p>{{ finding.description }}</p>
                <small v-if="finding.suggestion">建议：{{ finding.suggestion }}</small>
                <a v-if="finding.externalWorkItemUrl" :href="finding.externalWorkItemUrl" target="_blank" rel="noreferrer">外部事项</a>
              </div>
              <div class="finding-actions">
                <button type="button" @click="reviewFinding(finding.id, 'CONFIRMED')">确认</button>
                <button type="button" @click="reviewFinding(finding.id, 'REJECTED')">驳回</button>
                <button type="button" @click="reviewFinding(finding.id, 'EXEMPTED')">豁免</button>
                <button type="button" @click="writeBackFinding(finding.id)">标记回写</button>
              </div>
            </article>
            <div v-if="!filteredFindings.length" class="empty-state compact">当前筛选下没有问题。</div>
          </section>

          <section v-else class="evidence-panel">
            <article>
              <strong>输入新鲜度</strong>
              <span>{{ detail.baseline.freshness }} · {{ detail.baseline.analyzerVersion }}</span>
            </article>
            <article>
              <strong>源代码版本</strong>
              <span>{{ detail.baseline.sourceBranch || '-' }} / {{ detail.baseline.sourceCommit || '-' }}</span>
            </article>
            <article>
              <strong>结论口径</strong>
              <span>未接入执行或覆盖率证据时，只能输出 STATICALLY_CONSISTENT，不能宣称 SATISFIED。</span>
            </article>
            <article v-for="action in writeBacks" :key="action.id">
              <strong>回写记录：{{ action.connectorType }} / {{ action.status }}</strong>
              <span>{{ action.message || '-' }} · {{ formatTime(action.createTime) }}</span>
              <a v-if="action.externalUrl" :href="action.externalUrl" target="_blank" rel="noreferrer">{{ action.externalUrl }}</a>
            </article>
          </section>
        </template>
      </main>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import {
  analyzeBaseline,
  createVerificationBaseline,
  evaluateQualityGate,
  fetchWriteBackActions,
  fetchBaselineDetail,
  fetchTraceMatrix,
  fetchVerificationOverview,
  importGitSourceAsset,
  importVerificationAsset,
  markVerificationBaselineStale,
  reviewTraceLink,
  reviewVerificationFinding,
  writeBackVerificationFinding,
  type AssetType,
  type BaselineDetail,
  type GateResult,
  type MatrixRow,
  type ReviewStatus,
  type TraceLink,
  type VerificationAsset,
  type VerificationOverview,
  type Verdict,
  type WriteBackAction,
} from '@/api/verification'
import { useToast } from '@/composables/useToast'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const toast = useToast()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.contextByProjectId[projectId.value]?.apps || [])

const emptyOverview: VerificationOverview = { requirements: [], testcases: [], sources: [], executions: [], coverages: [], baselines: [] }
const overview = ref<VerificationOverview>(emptyOverview)
const detail = ref<BaselineDetail | null>(null)
const matrix = ref<MatrixRow[]>([])
const gateResult = ref<GateResult | null>(null)
const writeBacks = ref<WriteBackAction[]>([])
const selectedBaselineId = ref('')
const activeTab = ref<'matrix' | 'findings' | 'evidence'>('matrix')
const findingPerspective = ref('')
const loading = ref(false)
const analyzing = ref(false)
const gating = ref(false)
const creatingBaseline = ref(false)
const importing = ref<AssetType | ''>('')
const gitImporting = ref(false)
const error = ref('')
const files = reactive<Partial<Record<AssetType, File>>>({})
const pasteInputs = reactive<Record<AssetType, string>>({ REQUIREMENT: '', TESTCASE: '', SOURCE: '', EXECUTION: '', COVERAGE: '', DEFECT: '' })
const sourceVersions = reactive<Record<AssetType, string>>({ REQUIREMENT: '', TESTCASE: '', SOURCE: '', EXECUTION: '', COVERAGE: '', DEFECT: '' })
const baselineForm = reactive({
  name: '',
  requirementAssetId: '',
  testcaseAssetId: '',
  sourceAssetId: '',
  executionAssetId: '',
  coverageAssetId: '',
  sourceAppId: '',
  sourceBranch: '',
  sourceCommit: '',
})
const gitForm = reactive({
  appId: '',
  repositoryUrl: '',
  username: '',
  password: '',
  branch: '',
  commit: '',
  maxFiles: 120,
})

const assetInputs: Array<{ type: AssetType; label: string; hint: string; placeholder: string }> = [
  { type: 'REQUIREMENT', label: '需求', hint: 'Markdown / CSV / 文本', placeholder: '粘贴需求功能点或验收标准...' },
  { type: 'TESTCASE', label: '测试用例', hint: 'Excel / CSV / JSON', placeholder: '粘贴用例ID、步骤、预期结果...' },
  { type: 'SOURCE', label: '源码', hint: '源码片段 / 静态快照', placeholder: '粘贴 Controller / Service / 核心逻辑...' },
  { type: 'EXECUTION', label: '执行报告', hint: '可选', placeholder: '粘贴测试执行结果，包含用例ID和状态...' },
  { type: 'COVERAGE', label: '覆盖率', hint: '可选', placeholder: '粘贴 JaCoCo / Istanbul / CI 覆盖率摘要...' },
]

const tabs = [
  { key: 'matrix', label: '追溯矩阵' },
  { key: 'findings', label: 'AI 发现' },
  { key: 'evidence', label: '证据与口径' },
] as const

const filteredFindings = computed(() => {
  const findings = detail.value?.findings || []
  return findings.filter((finding) => !findingPerspective.value || finding.perspective === findingPerspective.value)
})

onMounted(async () => {
  await projectStore.loadProjectContext(projectId.value).catch(() => undefined)
  await loadOverview()
})

async function loadOverview() {
  if (!projectId.value) return
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchVerificationOverview(projectId.value)
    if (!selectedBaselineId.value && overview.value.baselines[0]) {
      await selectBaseline(overview.value.baselines[0].id)
    }
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

function onFileChange(type: AssetType, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) files[type] = file
  else delete files[type]
}

function hasImportInput(type: AssetType) {
  return !!files[type] || !!pasteInputs[type]?.trim()
}

async function importAsset(type: AssetType) {
  if (!hasImportInput(type)) {
    toast.warning('请先选择文件或粘贴内容')
    return
  }
  importing.value = type
  error.value = ''
  try {
    const asset = await importVerificationAsset(projectId.value, type, files[type], pasteInputs[type], sourceVersions[type])
    toast.success(`${asset.assetType} 快照已导入`)
    pasteInputs[type] = ''
    files[type] = undefined
    await loadOverview()
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    importing.value = ''
  }
}

async function createBaseline() {
  if (!baselineForm.requirementAssetId || !baselineForm.testcaseAssetId) {
    toast.warning('需求快照和用例快照不能为空')
    return
  }
  creatingBaseline.value = true
  try {
    const baseline = await createVerificationBaseline(projectId.value, { ...baselineForm })
    selectedBaselineId.value = baseline.id
    toast.success('分析基线已创建')
    await loadOverview()
    await selectBaseline(baseline.id)
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    creatingBaseline.value = false
  }
}

async function importGitSource() {
  if (!gitForm.appId && !gitForm.repositoryUrl) {
    toast.warning('请选择应用或填写仓库地址')
    return
  }
  gitImporting.value = true
  error.value = ''
  try {
    const asset = await importGitSourceAsset(projectId.value, { ...gitForm, maxBytes: 300000 })
    baselineForm.sourceAssetId = asset.id
    if (gitForm.appId) baselineForm.sourceAppId = gitForm.appId
    baselineForm.sourceBranch = gitForm.branch
    baselineForm.sourceCommit = asset.sourceVersion || gitForm.commit
    toast.success('Git 源码快照已导入')
    await loadOverview()
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    gitImporting.value = false
  }
}

async function selectBaseline(id: string) {
  selectedBaselineId.value = id
  gateResult.value = null
  detail.value = await fetchBaselineDetail(projectId.value, id)
  matrix.value = await fetchTraceMatrix(projectId.value, id)
  writeBacks.value = await fetchWriteBackActions(projectId.value, id)
}

async function runAnalysis() {
  if (!selectedBaselineId.value) return
  analyzing.value = true
  try {
    detail.value = await analyzeBaseline(projectId.value, selectedBaselineId.value)
    matrix.value = await fetchTraceMatrix(projectId.value, selectedBaselineId.value)
    await loadOverview()
    toast.success('AI 一致性分析完成')
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    analyzing.value = false
  }
}

async function evaluateGate() {
  if (!selectedBaselineId.value) return
  gating.value = true
  try {
    gateResult.value = await evaluateQualityGate(projectId.value, selectedBaselineId.value)
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    gating.value = false
  }
}

async function markStale() {
  if (!selectedBaselineId.value) return
  await markVerificationBaselineStale(projectId.value, selectedBaselineId.value)
  toast.warning('基线已标记过期，需重新导入或重新创建基线')
  await loadOverview()
  await selectBaseline(selectedBaselineId.value)
}

async function reviewFinding(id: string, status: ReviewStatus) {
  await reviewVerificationFinding(projectId.value, id, { status, reason: '前端工作台审核' })
  toast.success('审核状态已更新')
  await selectBaseline(selectedBaselineId.value)
}

async function writeBackFinding(id: string) {
  const externalWorkItemUrl = window.prompt('外部 Bug/任务/评论链接')
  if (!externalWorkItemUrl) return
  await writeBackVerificationFinding(projectId.value, id, {
    connectorType: 'link-only',
    externalUrl: externalWorkItemUrl,
    message: '已在外部事实源处理',
  })
  toast.success('已记录外部回写链接')
  await selectBaseline(selectedBaselineId.value)
}

async function confirmTrace(id: string) {
  await reviewTraceLink(projectId.value, id, 'CONFIRMED')
  toast.success('追溯关系已确认')
  await selectBaseline(selectedBaselineId.value)
}

function evidenceLinks(acId: string): TraceLink[] {
  return detail.value?.traceLinks.filter((link) => link.sourceId === acId) || []
}

function assetLabel(asset: VerificationAsset) {
  return `${asset.fileName || asset.externalId || asset.id.slice(0, 8)} · ${asset.freshness} · ${asset.sourceVersion || asset.contentHash.slice(0, 8)}`
}

function percent(value: number) {
  return `${Math.round((value || 0) * 1000) / 10}%`
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function verdictText(value: Verdict) {
  const map: Record<Verdict, string> = {
    SATISFIED: '已满足',
    STATICALLY_CONSISTENT: '静态一致',
    PARTIAL: '部分满足',
    NOT_SATISFIED: '不满足',
    AMBIGUOUS: '需求模糊',
    NOT_VERIFIABLE: '不可验证',
    EXEMPTED: '已豁免',
    STALE: '已过期',
  }
  return map[value] || value
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '操作失败'
}
</script>

<style scoped>
.verification-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.verification-header,
.header-actions,
.section-head,
.gate-band,
.finding-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.header-actions {
  flex-wrap: wrap;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.control-panel,
.result-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-section,
.result-panel,
.notice,
.empty-state,
.gate-band {
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  background: rgba(255, 255, 255, .92);
}

.panel-section,
.result-panel,
.notice,
.empty-state,
.gate-band {
  padding: 14px;
}

.section-head h2 {
  margin: 0;
  font-size: 16px;
}

.section-head span,
.asset-title span,
.baseline-item span,
.empty-state span,
.gate-band span,
.finding-card small,
.evidence-panel span {
  color: var(--oat-text-muted);
  font-size: 12px;
}

.asset-import-grid,
.form-stack,
.baseline-list,
.finding-list,
.evidence-panel {
  display: grid;
  gap: 10px;
}

.asset-import {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: var(--oat-surface-soft);
}

.asset-title {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

textarea,
input,
select {
  width: 100%;
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  padding: 8px 10px;
  background: #fff;
  color: var(--oat-text);
}

textarea {
  min-height: 78px;
  resize: vertical;
}

label {
  display: grid;
  gap: 5px;
}

label span {
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.inline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

button,
.primary-button,
.secondary-button {
  min-height: 36px;
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  padding: 7px 11px;
  background: #fff;
  color: var(--oat-text);
  font-weight: 800;
}

.primary-button {
  border-color: var(--oat-primary);
  background: var(--oat-primary);
  color: #fff;
}

.secondary-button {
  background: var(--oat-surface-soft);
}

.full {
  width: 100%;
}

.baseline-item {
  display: grid;
  gap: 4px;
  text-align: left;
}

.baseline-item.active {
  border-color: rgba(var(--oat-primary-rgb), .55);
  background: rgba(var(--oat-primary-rgb), .08);
}

.metrics-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.metrics-strip article,
.evidence-panel article {
  display: grid;
  gap: 3px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 8px;
  background: var(--oat-surface-soft);
}

.metrics-strip span {
  color: var(--oat-text-muted);
  font-size: 12px;
}

.metrics-strip strong {
  font-size: 20px;
}

.gate-band.warning {
  border-color: rgba(217, 119, 6, .35);
  background: rgba(245, 158, 11, .08);
}

.gate-band.failed {
  border-color: rgba(220, 38, 38, .35);
  background: rgba(220, 38, 38, .07);
}

.gate-band.passed {
  border-color: rgba(22, 163, 74, .35);
  background: rgba(22, 163, 74, .07);
}

.tabs {
  display: flex;
  gap: 6px;
  border-bottom: 1px solid var(--oat-border);
}

.tabs button {
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.tabs button.active {
  border-color: var(--oat-primary);
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-primary-dark);
}

.matrix-table {
  display: grid;
  gap: 0;
  border: 1px solid var(--oat-border);
  border-radius: 8px;
  overflow: hidden;
}

.table-row {
  display: grid;
  grid-template-columns: 1.4fr .8fr 1fr .55fr;
  gap: 10px;
  padding: 10px;
  border-top: 1px solid var(--oat-border);
  background: #fff;
}

.table-row:first-child {
  border-top: 0;
}

.table-head {
  background: var(--oat-surface-soft);
  color: var(--oat-text-secondary);
  font-size: 12px;
  font-weight: 800;
}

.table-row p,
.finding-card p {
  margin: 4px 0;
  color: var(--oat-text-secondary);
}

.chips,
.trace-list,
.finding-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: flex-start;
}

.chips span,
.chips em,
.verdict {
  border-radius: 999px;
  padding: 3px 8px;
  background: rgba(var(--oat-primary-rgb), .08);
  color: var(--oat-primary-dark);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}

.trace-list button {
  min-height: 28px;
  padding: 4px 7px;
  font-size: 12px;
}

.verdict.not_satisfied,
.verdict.not_verifiable,
.verdict.ambiguous {
  background: rgba(220, 38, 38, .08);
  color: var(--oat-danger);
}

.verdict.statically_consistent,
.verdict.satisfied {
  background: rgba(22, 163, 74, .08);
  color: var(--oat-success);
}

.finding-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--oat-border);
  border-left: 4px solid var(--oat-warning);
  border-radius: 8px;
  background: #fff;
}

.finding-card.critical,
.finding-card.high {
  border-left-color: var(--oat-danger);
}

.finding-card.low,
.finding-card.info {
  border-left-color: var(--oat-info);
}

.finding-card h3 {
  margin: 4px 0;
  font-size: 16px;
}

.finding-actions {
  justify-content: flex-end;
  max-width: 220px;
}

.notice.danger {
  border-color: rgba(220, 38, 38, .25);
  color: var(--oat-danger);
  background: rgba(220, 38, 38, .06);
}

.empty-state {
  display: grid;
  gap: 5px;
  min-height: 180px;
  place-content: center;
  text-align: center;
}

.empty-state.compact {
  min-height: 90px;
}

@media (max-width: 1100px) {
  .workspace-grid {
    grid-template-columns: 1fr;
  }

  .metrics-strip,
  .table-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .verification-header,
  .gate-band,
  .finding-card {
    align-items: stretch;
    flex-direction: column;
    grid-template-columns: 1fr;
  }

  .metrics-strip,
  .inline-grid,
  .table-row {
    grid-template-columns: 1fr;
  }
}
</style>
