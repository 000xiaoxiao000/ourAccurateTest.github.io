<template>
  <section>
    <div class="home-map-toolbar trace-map-toolbar">
      <div>
        <strong>{{ selectedNode ? selectedNode.label || selectedNode.id : '双向追溯链路地图' }}</strong>
        <span>{{ selectedNode ? selectedTypeText : baselineStatusText }}</span>
      </div>
      <div class="toolbar-actions">
        <label v-if="overview.baselines.length" class="baseline-select">
          <span>分析基线</span>
          <select v-model="activeBaselineId" :disabled="loading">
            <option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">
              {{ baseline.name || baseline.id }} · {{ baseline.status }}
            </option>
          </select>
        </label>
        <button type="button" :class="{ active: showRelationLabels }" @click="showRelationLabels = !showRelationLabels">关系标签</button>
        <button type="button" :class="{ active: highlightRelated }" @click="highlightRelated = !highlightRelated">高亮追溯</button>
        <button type="button" @click="openVerificationWorkspace">打开 AI 验证</button>
        <button type="button" :disabled="loading" @click="load">刷新追溯图</button>
      </div>
      <div v-if="!overview.baselines.length && !loading && !error" class="layer-status">
        暂无分析基线。请先在 AI 验证页面导入需求、用例、源码并执行分析，链路地图会展示需求、用例、Bug、源码之间的双向追溯关系。
      </div>
    </div>

    <div class="trace-summary-grid">
      <article class="trace-stat requirement">
        <span>需求/验收点</span>
        <strong>{{ traceStats.requirements }}</strong>
      </article>
      <article class="trace-stat testcase">
        <span>测试用例</span>
        <strong>{{ traceStats.testcases }}</strong>
      </article>
      <article class="trace-stat source">
        <span>源码符号</span>
        <strong>{{ traceStats.sources }}</strong>
      </article>
      <article class="trace-stat bug">
        <span>Bug/问题</span>
        <strong>{{ traceStats.bugs }}</strong>
      </article>
    </div>

    <RelationBoard
      eyebrow="Traceability Map"
      title="需求双向追溯地图"
      subtext="围绕人工标注基准集和 AI 分析基线，展示需求、测试用例、Bug/问题、源码实现之间的可追溯关系。"
      :loading="loading"
      :error="error"
      :nodes="nodes"
      :edges="edges"
      :context-actions="contextActions"
      :show-edge-labels="showRelationLabels"
      :highlight-related="highlightRelated"
      @node-select="handleNodeSelect"
      @context-action="handleContextAction"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import RelationBoard from '@/components/map/RelationBoard.vue'
import { fetchBaselineDetail, fetchVerificationOverview } from '@/api/verification'
import type {
  AcceptanceCriterion,
  BaselineDetail,
  TraceLink,
  VerificationBaseline,
  VerificationFinding,
  VerificationOverview,
} from '@/api/verification'
import type { RelationEdge, RelationNode } from '@/features/map/types'

interface RelationNodeSelection {
  id: string
  label?: string
  type?: string
  raw?: Record<string, unknown>
  classes?: string[]
}

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId || ''))
const loading = ref(false)
const error = ref('')
const selectedNode = ref<RelationNodeSelection | null>(null)
const overview = ref<VerificationOverview>(emptyOverview())
const detail = ref<BaselineDetail | null>(null)
const activeBaselineId = ref('')
const showRelationLabels = ref(true)
const highlightRelated = ref(true)

const selectedClasses = computed(() => selectedNode.value?.classes || selectedNode.value?.type?.split(/\s+/).filter(Boolean) || [])
const selectedTypeText = computed(() => selectedClasses.value.length ? `类型：${selectedClasses.value.join(' / ')}` : '节点')
const activeBaseline = computed(() => overview.value.baselines.find((item) => item.id === activeBaselineId.value) || null)
const baselineStatusText = computed(() => {
  if (!overview.value.baselines.length) return '尚未生成 AI 验证分析基线'
  if (!activeBaseline.value) return '请选择分析基线'
  return `当前基线：${activeBaseline.value.name || activeBaseline.value.id} · ${activeBaseline.value.status} · ${activeBaseline.value.freshness}`
})
const contextActions = computed(() => [
  { id: 'refresh-map', label: '刷新追溯图', target: 'canvas' as const, disabled: loading.value },
  { id: 'open-verification', label: '打开 AI 验证', target: 'canvas' as const },
])

const traceGraph = computed(() => buildTraceGraph(detail.value))
const nodes = computed(() => traceGraph.value.nodes)
const edges = computed(() => traceGraph.value.edges)
const traceStats = computed(() => ({
  requirements: detail.value?.criteria.length || 0,
  testcases: detail.value?.testcases.length || 0,
  sources: nodes.value.filter((node) => node.type?.includes('source')).length,
  bugs: detail.value?.findings.length || 0,
}))

watch(activeBaselineId, async (value, oldValue) => {
  if (!value || value === oldValue) return
  await loadBaselineDetail(value)
})

function handleNodeSelect(node: RelationNodeSelection | null) {
  selectedNode.value = node
}

function handleContextAction(actionId: string, node: RelationNodeSelection | null) {
  selectedNode.value = node
  if (actionId === 'open-verification') {
    openVerificationWorkspace()
    return
  }
  if (actionId === 'refresh-map') {
    load()
  }
}

function openVerificationWorkspace() {
  router.push(`/p/${projectId.value}/verification`)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchVerificationOverview(projectId.value)
    const preferredBaselineId = pickBaselineId(overview.value.baselines, activeBaselineId.value)
    activeBaselineId.value = preferredBaselineId
    detail.value = null
    if (preferredBaselineId) {
      await loadBaselineDetail(preferredBaselineId)
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载双向追溯地图失败'
  } finally {
    loading.value = false
  }
}

async function loadBaselineDetail(baselineId: string) {
  if (!baselineId) {
    detail.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    detail.value = await fetchBaselineDetail(projectId.value, baselineId)
    selectedNode.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载分析基线失败'
  } finally {
    loading.value = false
  }
}

function pickBaselineId(baselines: VerificationBaseline[], current: string) {
  if (current && baselines.some((item) => item.id === current)) return current
  return baselines.find((item) => ['WAITING_REVIEW', 'COMPLETED'].includes(item.status))?.id || baselines[0]?.id || ''
}

function buildTraceGraph(current: BaselineDetail | null): { nodes: RelationNode[]; edges: RelationEdge[] } {
  if (!current) return { nodes: [], edges: [] }
  const graphNodes = new Map<string, RelationNode>()
  const graphEdges = new Map<string, RelationEdge>()
  const criteriaById = new Map(current.criteria.map((item) => [item.id, item]))
  const testcasesById = new Map(current.testcases.map((item) => [item.id, item]))
  const testcasesByExternalKey = new Map(current.testcases.map((item) => [item.externalKey, item]))

  current.criteria.forEach((criterion) => {
    const id = requirementNodeId(criterion.id)
    graphNodes.set(id, {
      id,
      label: `${criterion.requirementKey}/${criterion.acKey}`,
      type: 'requirement',
      description: criterion.title || criterion.content,
      classes: ['requirement'],
      raw: criterion as unknown as Record<string, unknown>,
      meta: [
        criterion.priority ? `优先级 ${criterion.priority}` : '',
        criterion.testable ? '可测试' : '不可测试',
        criterion.ambiguity ? '存在歧义' : '',
        `置信度 ${Math.round(criterion.confidence * 100)}%`,
      ].filter(Boolean),
    })
  })

  current.testcases.forEach((testcase) => {
    const id = testcaseNodeId(testcase.id)
    graphNodes.set(id, {
      id,
      label: testcase.externalKey || testcase.title || testcase.id,
      type: 'testcase',
      description: testcase.title || testcase.expected || testcase.steps,
      classes: ['testcase'],
      raw: testcase as unknown as Record<string, unknown>,
      meta: [
        testcase.requirementRefs ? `引用需求 ${testcase.requirementRefs}` : '',
        testcase.expected ? '含预期结果' : '缺少预期结果',
        testcase.sourceLocator || '',
      ].filter(Boolean),
    })
  })

  current.traceLinks.forEach((link) => {
    const sourceId = resolveTraceNodeId(link.sourceType, link.sourceId)
    const targetId = ensureTraceTargetNode(graphNodes, link, testcasesById)
    if (!sourceId || !targetId || !graphNodes.has(sourceId) || !graphNodes.has(targetId)) return
    const label = relationLabel(link)
    graphEdges.set(`trace:${link.id}`, {
      id: `trace:${link.id}`,
      source: sourceId,
      target: targetId,
      label,
      action: link.relationType,
      sourceLabel: graphNodes.get(sourceId)?.label,
      targetLabel: graphNodes.get(targetId)?.label,
    })
  })

  current.findings.forEach((finding) => {
    const bugId = bugNodeId(finding.id)
    graphNodes.set(bugId, {
      id: bugId,
      label: finding.title || finding.findingType,
      type: `bug ${finding.severity.toLowerCase()}`,
      description: finding.description,
      classes: ['bug', finding.severity.toLowerCase(), finding.perspective.toLowerCase()],
      raw: finding as unknown as Record<string, unknown>,
      meta: [
        `严重级别 ${severityText(finding.severity)}`,
        `视角 ${perspectiveText(finding.perspective)}`,
        `结论 ${verdictText(finding.verdict)}`,
        `评审 ${reviewStatusText(finding.reviewStatus)}`,
        `置信度 ${Math.round(finding.confidence * 100)}%`,
      ],
    })
    if (finding.acId && criteriaById.has(finding.acId)) {
      const requirementId = requirementNodeId(finding.acId)
      graphEdges.set(`finding:${finding.id}`, {
        id: `finding:${finding.id}`,
        source: requirementId,
        target: bugId,
        label: '需求↔Bug',
        action: finding.severity,
        sourceLabel: graphNodes.get(requirementId)?.label,
        targetLabel: graphNodes.get(bugId)?.label,
      })
    } else {
      const testcase = testcaseFromFindingEvidence(finding, testcasesByExternalKey)
      if (testcase) {
        const testcaseId = testcaseNodeId(testcase.id)
        graphEdges.set(`finding:${finding.id}`, {
          id: `finding:${finding.id}`,
          source: testcaseId,
          target: bugId,
          label: '用例↔Bug',
          action: finding.severity,
          sourceLabel: graphNodes.get(testcaseId)?.label,
          targetLabel: graphNodes.get(bugId)?.label,
        })
      }
    }
  })

  return { nodes: Array.from(graphNodes.values()), edges: Array.from(graphEdges.values()) }
}

function ensureTraceTargetNode(nodesMap: Map<string, RelationNode>, link: TraceLink, testcasesById: Map<string, { title: string; externalKey: string }>) {
  if (link.targetType === 'TESTCASE') {
    return testcaseNodeId(link.targetId)
  }
  if (['SOURCE_SYMBOL', 'SOURCE', 'CODE', 'METHOD', 'CLASS'].includes(link.targetType)) {
    const id = sourceNodeId(link.targetId)
    if (!nodesMap.has(id)) {
      nodesMap.set(id, {
        id,
        label: sourceLabel(link.targetId),
        type: 'source code',
        description: evidenceText(link.evidence) || '源码实现符号',
        classes: ['source', 'code'],
        raw: link.evidence || {},
        meta: [
          `来源 ${link.targetType}`,
          `证据 ${link.evidenceLevel}`,
          `置信度 ${Math.round(link.confidence * 100)}%`,
        ],
      })
    }
    return id
  }
  if (testcasesById.has(link.targetId)) return testcaseNodeId(link.targetId)
  return ''
}

function resolveTraceNodeId(sourceType: string, sourceId: string) {
  if (sourceType === 'AC' || sourceType === 'REQUIREMENT') return requirementNodeId(sourceId)
  if (sourceType === 'TESTCASE') return testcaseNodeId(sourceId)
  if (sourceType.includes('SOURCE') || sourceType === 'CODE') return sourceNodeId(sourceId)
  return ''
}

function relationLabel(link: TraceLink) {
  if (link.targetType === 'TESTCASE') return '需求↔用例'
  if (link.targetType.includes('SOURCE') || ['SOURCE', 'CODE', 'METHOD', 'CLASS'].includes(link.targetType)) return '需求↔源码'
  return link.relationType || '双向追溯'
}

function requirementNodeId(id: string) { return `req:${id}` }
function testcaseNodeId(id: string) { return `tc:${id}` }
function sourceNodeId(id: string) { return `src:${id}` }
function bugNodeId(id: string) { return `bug:${id}` }

function sourceLabel(value: string) {
  return value.replace(/^SOURCE_ASSET:/, '源码资产 ').split(/[/.#]/).filter(Boolean).slice(-2).join('.') || value
}

function evidenceText(evidence?: Record<string, unknown>) {
  if (!evidence) return ''
  return String(evidence.method || evidence.className || evidence.locator || evidence.reason || '')
}

function testcaseFromFindingEvidence(finding: VerificationFinding, testcasesByExternalKey: Map<string, { id: string }>) {
  const evidence = finding.evidence || []
  for (const item of evidence) {
    const externalKey = String(item.testcaseId || '')
    const testcase = externalKey ? testcasesByExternalKey.get(externalKey) : undefined
    if (testcase) return testcase
  }
  return null
}

function emptyOverview(): VerificationOverview {
  return { requirements: [], testcases: [], sources: [], executions: [], coverages: [], baselines: [] }
}

function severityText(value: VerificationFinding['severity']) {
  return ({ CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低', INFO: '提示' } as Record<string, string>)[value] || value
}

function perspectiveText(value: VerificationFinding['perspective']) {
  return ({ PRODUCT: '产品', TEST: '测试', DEVELOPMENT: '开发', CROSS: '交叉验证' } as Record<string, string>)[value] || value
}

function verdictText(value: VerificationFinding['verdict']) {
  return ({
    SATISFIED: '满足',
    STATICALLY_CONSISTENT: '静态一致',
    PARTIAL: '部分满足',
    NOT_SATISFIED: '不满足',
    AMBIGUOUS: '需求歧义',
    NOT_VERIFIABLE: '不可验证',
    EXEMPTED: '已豁免',
    STALE: '已过期',
  } as Record<string, string>)[value] || value
}

function reviewStatusText(value: VerificationFinding['reviewStatus']) {
  return ({ PENDING: '待确认', CONFIRMED: '已确认', REJECTED: '已驳回', WRITTEN_BACK: '已回写', STALE: '已过期', EXEMPTED: '已豁免' } as Record<string, string>)[value] || value
}

onMounted(load)
</script>

<style scoped>
.home-map-toolbar {
  display: grid;
  grid-template-columns: minmax(260px, .55fr) 1fr;
  gap: 12px;
  align-items: center;
  margin-bottom: 14px;
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.home-map-toolbar > div:first-child {
  display: grid;
  gap: 4px;
}

.home-map-toolbar span,
.layer-status {
  color: #64748b;
}

.toolbar-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
}

.toolbar-actions button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  background: rgba(15, 23, 42, 0.08);
  cursor: pointer;
  font-weight: 800;
  transition: transform .12s ease, background .12s ease, color .12s ease, box-shadow .12s ease;
}

.toolbar-actions button.active,
.toolbar-actions button:hover:not(:disabled) {
  background: #0f172a;
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(15, 23, 42, .16);
}

.toolbar-actions button:active:not(:disabled) {
  transform: translateY(0);
}

.toolbar-actions button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.toolbar-actions .danger {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.baseline-select {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  border: 1px solid rgba(15, 23, 42, .10);
  border-radius: 999px;
  padding: 4px 10px 4px 14px;
  background: rgba(255, 255, 255, .9);
  color: #64748b;
  font-size: 13px;
  font-weight: 800;
}

.baseline-select select {
  max-width: min(360px, 42vw);
  border: 0;
  background: transparent;
  color: #172033;
  font: inherit;
  outline: none;
}

.trace-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.trace-stat {
  padding: 14px 16px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 12px 30px rgba(15, 23, 42, .05);
}

.trace-stat span {
  color: #64748b;
  font-size: 13px;
  font-weight: 800;
}

.trace-stat strong {
  display: block;
  margin-top: 6px;
  color: #0f172a;
  font-size: 26px;
  line-height: 1;
}

.trace-stat.requirement { box-shadow: inset 4px 0 0 #0f766e, 0 12px 30px rgba(15, 23, 42, .05); }
.trace-stat.testcase { box-shadow: inset 4px 0 0 #2563eb, 0 12px 30px rgba(15, 23, 42, .05); }
.trace-stat.source { box-shadow: inset 4px 0 0 #7c3aed, 0 12px 30px rgba(15, 23, 42, .05); }
.trace-stat.bug { box-shadow: inset 4px 0 0 #dc2626, 0 12px 30px rgba(15, 23, 42, .05); }

.layer-status {
  grid-column: 1 / -1;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(15, 118, 110, 0.08);
  font-weight: 700;
}

.layer-status.error {
  color: #b91c1c;
  background: rgba(220, 38, 38, 0.08);
}

@media (max-width: 860px) {
  .home-map-toolbar {
    grid-template-columns: 1fr;
  }

  .toolbar-actions {
    justify-content: flex-start;
  }

  .trace-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .trace-summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
