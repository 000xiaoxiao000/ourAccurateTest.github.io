<template>
  <section class="trace-workspace">
    <header class="workspace-toolbar">
      <div>
        <span class="eyebrow">Traceability Workspace</span>
        <strong>需求 · 测试 · 代码追溯</strong>
        <span>{{ selectedNode ? selectedNode.label || selectedNode.id : baselineStatusText }}</span>
      </div>
      <div class="toolbar-actions">
        <label v-if="overview.baselines.length" class="baseline-select"><span>分析基线</span><select v-model="activeBaselineId" :disabled="loading"><option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">{{ baseline.name || baseline.id }} · {{ baseline.status }}</option></select></label>
        <button type="button" :class="{ active: showRelationLabels }" @click="showRelationLabels = !showRelationLabels">关系标签</button>
        <button type="button" :class="{ active: highlightRelated }" @click="highlightRelated = !highlightRelated">高亮追溯</button>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>
    <div v-if="!overview.baselines.length && !loading && !error" class="workspace-notice">暂无分析基线。请先在 AI 验证页面导入需求、用例和源码并执行分析。</div>
    <div class="trace-columns">
      <aside class="asset-pane">
        <div class="pane-head"><strong>业务资产</strong><span>{{ traceStats.requirements }} 需求 · {{ traceStats.testcases }} 用例</span></div>
        <section class="asset-group"><div class="group-head"><strong>需求</strong><span>{{ traceStats.requirements }}</span></div><button v-for="item in detail?.criteria || []" :key="item.id" :class="['asset-card', 'requirement', { active: selectedNode?.id === requirementNodeId(item.id) }]" @click="selectAsset(requirementNodeId(item.id))"><b>{{ item.requirementKey }}/{{ item.acKey }}</b><strong>{{ item.title || item.content }}</strong><small>{{ item.testable ? '可测试' : '待澄清' }} · {{ requirementTestCount(item.id) }} 个关联用例</small></button></section>
        <section class="asset-group testcase-group"><div class="group-head"><strong>测试用例</strong><span>{{ traceStats.testcases }}</span></div><button v-for="item in detail?.testcases || []" :key="item.id" :class="['asset-card', 'testcase', { active: selectedNode?.id === testcaseNodeId(item.id) }]" @click="selectAsset(testcaseNodeId(item.id))"><b>{{ item.externalKey || item.id }}</b><strong>{{ item.title || '未命名测试用例' }}</strong><small>{{ testcaseRequirementCount(item.id) }} 个关联需求 · {{ testcaseSourceCount(item.id) }} 个关联代码</small></button></section>
      </aside>
      <main class="map-pane"><RelationBoard compact hide-lists eyebrow="Traceability Map" title="追溯关系画布" :loading="loading" :error="error" :nodes="nodes" :edges="edges" :selected-node-id="selectedNode?.id" :show-edge-labels="showRelationLabels" :highlight-related="highlightRelated" @node-select="handleNodeSelect" /></main>
      <aside class="code-pane"><div class="pane-head"><strong>代码树</strong><span>{{ traceStats.sources }} 个符号</span></div><div v-if="!sourceNodes.length" class="empty-card">暂无已导入源码。请先导入源码或连接代码仓库。</div><div v-else class="code-tree"><section v-if="staticSourceNodes.length" class="code-group"><div class="code-group-head">▾ 静态代码 <small>{{ staticSourceNodes.length }}</small></div><button v-for="node in staticSourceNodes" :key="node.id" :class="['code-item', { active: selectedNode?.id === node.id }]" @click="selectAsset(node.id)"><i>●</i><span><strong>{{ node.label }}</strong><small>静态源码实现</small></span><em>{{ sourceLinkCount(node.id) }}</em></button></section><section v-if="dynamicSourceNodes.length" class="code-group"><div class="code-group-head">▾ 动态调用链 <small>{{ dynamicSourceNodes.length }}</small></div><button v-for="node in dynamicSourceNodes" :key="node.id" :class="['code-item', 'dynamic', { active: selectedNode?.id === node.id }]" @click="selectAsset(node.id)"><i>◌</i><span><strong>{{ node.label }}</strong><small>动态运行命中</small></span><em>{{ sourceLinkCount(node.id) }}</em></button></section></div></aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import RelationBoard from '@/components/map/RelationBoard.vue'
import { fetchMapSourceTree, fetchProjectApps } from '@/api/bootstrap'
import type { SourceTreeClass } from '@/api/bootstrap'
import { fetchBaselineDetail, fetchVerificationOverview } from '@/api/verification'
import type {
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
const sourceTree = ref<SourceTreeClass[]>([])

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
const sourceNodes = computed(() => {
  if (sourceTree.value.length) {
    return sourceTree.value.flatMap((sourceClass) => {
      const classNode: RelationNode = {
        id: sourceNodeId(`class:${sourceClass.id}`),
        label: sourceClass.className,
        type: 'source code class',
        classes: ['source', 'static', 'class'],
        description: `${sourceClass.methods.length} 个方法`,
      }
      const methodNodes: RelationNode[] = sourceClass.methods.map((method, index) => ({
        id: sourceNodeId(`method:${sourceClass.id}:${index}`),
        label: `${method.methodName}${method.lineNumber ? ` · L${method.lineNumber}` : ''}`,
        type: 'source code method',
        classes: ['source', 'static', 'method'],
        description: method.methodDesc || sourceClass.className,
        raw: { className: sourceClass.className, ...method },
      }))
      return [classNode, ...methodNodes]
    })
  }
  const linkedSources = nodes.value.filter((node) => node.id.startsWith('src:'))
  if (linkedSources.length) return linkedSources
  return overview.value.sources.map((source) => ({
    id: sourceNodeId(source.id),
    label: source.fileName || source.externalId || '已导入源码资产',
    type: 'source code',
    classes: ['source', 'static'],
    description: source.contentPreview || '该源码资产尚未生成符号级追溯链接',
    meta: [source.sourceVersion ? `版本 ${source.sourceVersion}` : '', source.freshness ? `数据 ${source.freshness}` : ''].filter(Boolean),
  }))
})
const staticSourceNodes = computed(() => sourceNodes.value.filter((node) => !node.type?.includes('dynamic')))
const dynamicSourceNodes = computed(() => sourceNodes.value.filter((node) => node.type?.includes('dynamic')))
const traceStats = computed(() => ({
  requirements: detail.value?.criteria.length || 0,
  testcases: detail.value?.testcases.length || 0,
  sources: sourceNodes.value.length,
  bugs: detail.value?.findings.length || 0,
}))

watch(activeBaselineId, async (value, oldValue) => {
  if (!value || value === oldValue) return
  await loadBaselineDetail(value)
  await loadSourceTree()
})

function selectAsset(nodeId: string) {
  const node = nodes.value.find((item) => item.id === nodeId) || sourceNodes.value.find((item) => item.id === nodeId) || null
  handleNodeSelect(node)
}

function requirementTestCount(criterionId: string) {
  return edges.value.filter((edge) => edge.source === requirementNodeId(criterionId) && edge.target.startsWith('tc:')).length
}

function testcaseRequirementCount(testcaseId: string) {
  return edges.value.filter((edge) => edge.target === testcaseNodeId(testcaseId) && edge.source.startsWith('req:')).length
}

function testcaseSourceCount(testcaseId: string) {
  const requirementIds = new Set(edges.value.filter((edge) => edge.target === testcaseNodeId(testcaseId)).map((edge) => edge.source))
  return edges.value.filter((edge) => requirementIds.has(edge.source) && edge.target.startsWith('src:')).length
}

function sourceLinkCount(nodeId: string) {
  return edges.value.filter((edge) => edge.target === nodeId || edge.source === nodeId).length
}

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
    await loadSourceTree()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载双向追溯地图失败'
  } finally {
    loading.value = false
  }
}

async function loadSourceTree() {
  const apps = await fetchProjectApps(projectId.value)
  const preferredAppId = activeBaseline.value?.sourceAppId
  const sourceAssetId = activeBaseline.value?.sourceAssetId
  const orderedApps = preferredAppId
    ? [...apps.filter((app) => app.id === preferredAppId), ...apps.filter((app) => app.id !== preferredAppId)]
    : apps
  const trees = await Promise.all(orderedApps.map(async (app) => {
    try {
      return await fetchMapSourceTree(projectId.value, app.id)
    } catch {
      return []
    }
  }))
  sourceTree.value = trees.flat()
  if (!sourceTree.value.length && sourceAssetId) {
    try {
      sourceTree.value = await fetchMapSourceTree(projectId.value, undefined, sourceAssetId)
    } catch {
      sourceTree.value = []
    }
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

  current.traceLinks.forEach((link) => addNormalizedTraceLink(graphNodes, graphEdges, link))
  addDerivedTestcaseCodeLinks(graphNodes, graphEdges)
  addSourceAssetFallbacks(graphNodes, current)

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

function addNormalizedTraceLink(nodesMap: Map<string, RelationNode>, edgesMap: Map<string, RelationEdge>, link: TraceLink) {
  const sourceId = resolveTraceNodeId(link.sourceType, link.sourceId)
  const targetId = resolveTraceNodeId(link.targetType, link.targetId)
  if (!sourceId || !targetId) return
  ensureTraceNode(nodesMap, sourceId, link.sourceType, link.sourceId, link)
  ensureTraceNode(nodesMap, targetId, link.targetType, link.targetId, link)
  if (!nodesMap.has(sourceId) || !nodesMap.has(targetId)) return
  const pair = normalizeTracePair(sourceId, targetId)
  if (!pair) return
  edgesMap.set(`trace:${link.id}`, {
    id: `trace:${link.id}`,
    source: pair.source,
    target: pair.target,
    label: pair.label,
    action: pair.label,
    sourceLabel: nodesMap.get(pair.source)?.label,
    targetLabel: nodesMap.get(pair.target)?.label,
  })
}

function ensureTraceNode(nodesMap: Map<string, RelationNode>, id: string, type: string, rawId: string, link: TraceLink) {
  if (nodesMap.has(id) || !id.startsWith('src:')) return
  const dynamic = /EXECUTION|RUNTIME|DYNAMIC/.test(`${type} ${link.relationType} ${JSON.stringify(link.evidence || {})}`.toUpperCase())
  nodesMap.set(id, {
    id,
    label: sourceLabel(rawId),
    type: dynamic ? 'source dynamic' : 'source code',
    classes: ['source', dynamic ? 'dynamic' : 'static'],
    description: evidenceText(link.evidence) || (dynamic ? '动态执行链路' : '静态源码实现符号'),
    raw: link.evidence || {},
    meta: [dynamic ? '动态运行证据' : '静态源码实现', `置信度 ${Math.round(link.confidence * 100)}%`],
  })
}

function normalizeTracePair(first: string, second: string) {
  if (first.startsWith('req:') && second.startsWith('tc:')) return { source: first, target: second, label: '验收覆盖' }
  if (first.startsWith('tc:') && second.startsWith('req:')) return { source: second, target: first, label: '验收覆盖' }
  if (first.startsWith('req:') && second.startsWith('src:')) return { source: first, target: second, label: '需求实现' }
  if (first.startsWith('src:') && second.startsWith('req:')) return { source: second, target: first, label: '需求实现' }
  if (first.startsWith('tc:') && second.startsWith('src:')) return { source: first, target: second, label: '测试覆盖' }
  if (first.startsWith('src:') && second.startsWith('tc:')) return { source: second, target: first, label: '测试覆盖' }
  if (first.startsWith('src:') && second.startsWith('src:')) return { source: first, target: second, label: '调用' }
  return null
}

function addDerivedTestcaseCodeLinks(nodesMap: Map<string, RelationNode>, edgesMap: Map<string, RelationEdge>) {
  const requirementsToTests = new Map<string, string[]>()
  const requirementsToSources = new Map<string, string[]>()
  edgesMap.forEach((edge) => {
    if (edge.source.startsWith('req:') && edge.target.startsWith('tc:')) requirementsToTests.set(edge.source, [...(requirementsToTests.get(edge.source) || []), edge.target])
    if (edge.source.startsWith('req:') && edge.target.startsWith('src:')) requirementsToSources.set(edge.source, [...(requirementsToSources.get(edge.source) || []), edge.target])
  })
  requirementsToTests.forEach((testcases, requirementId) => (requirementsToSources.get(requirementId) || []).forEach((sourceId) => testcases.forEach((testcaseId) => {
    const id = `derived:${testcaseId}:${sourceId}`
    if (edgesMap.has(id)) return
    edgesMap.set(id, { id, source: testcaseId, target: sourceId, label: '测试覆盖', action: '测试覆盖', sourceLabel: nodesMap.get(testcaseId)?.label, targetLabel: nodesMap.get(sourceId)?.label })
  })))
}

function addSourceAssetFallbacks(nodesMap: Map<string, RelationNode>, current: BaselineDetail) {
  if (nodesMap.size === current.criteria.length + current.testcases.length && current.baseline.sourceAssetId) {
    nodesMap.set(sourceNodeId(current.baseline.sourceAssetId), { id: sourceNodeId(current.baseline.sourceAssetId), label: '源码资产', type: 'source code', classes: ['source', 'static'], description: '已导入源码，但尚未生成方法级追溯链接', meta: ['静态源码实现'] })
  }
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
  if (['SOURCE_SYMBOL', 'SOURCE', 'CODE', 'METHOD', 'CLASS', 'FILE', 'EXECUTION', 'RUNTIME'].includes(sourceType) || sourceType.includes('SOURCE')) return sourceNodeId(sourceId)
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
  return { requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] }
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
.trace-workspace { min-width: 0; }
.workspace-toolbar { display:flex; justify-content:space-between; gap:16px; align-items:center; margin-bottom:14px; padding:15px 17px; border:1px solid rgba(15,23,42,.08); border-radius:22px; background:rgba(255,255,255,.94); }
.workspace-toolbar > div:first-child { display:grid; gap:4px; }.workspace-toolbar > div:first-child > strong { color:#172033; font-size:19px; }.workspace-toolbar span { color:#64748b; font-size:13px; }.eyebrow { color:#0f766e !important; font-size:11px !important; font-weight:900; letter-spacing:.12em; text-transform:uppercase; }
.toolbar-actions { display:flex; gap:8px; flex-wrap:wrap; justify-content:flex-end; }.toolbar-actions button { border:0; border-radius:999px; padding:9px 13px; background:#eef2f5; font-weight:800; cursor:pointer; }.toolbar-actions button.active,.toolbar-actions button:hover:not(:disabled) { background:#0f766e; color:#fff; }.toolbar-actions button:disabled { opacity:.5; cursor:not-allowed; }.baseline-select { display:flex; align-items:center; gap:7px; padding:5px 10px; border:1px solid #e2e8f0; border-radius:999px; font-weight:800; }.baseline-select select { max-width:260px; border:0; background:transparent; outline:0; }
.workspace-notice { margin-bottom:14px; padding:11px 14px; border-radius:14px; background:#f0fdfa; color:#0f766e; font-weight:700; }.trace-columns { display:grid; grid-template-columns:minmax(220px,.7fr) minmax(450px,1.65fr) minmax(230px,.76fr); gap:14px; min-height:calc(100vh - 245px); }.asset-pane,.code-pane { overflow:hidden; border:1px solid rgba(15,23,42,.08); border-radius:22px; background:rgba(255,255,255,.94); box-shadow:0 14px 36px rgba(15,23,42,.05); }.asset-pane { display:flex; flex-direction:column; }.pane-head { display:flex; justify-content:space-between; gap:8px; padding:15px; border-bottom:1px solid #eef2f5; color:#172033; }.pane-head span { color:#64748b; font-size:11px; font-weight:700; }.asset-group { display:grid; gap:8px; padding:12px; min-height:0; overflow:auto; }.testcase-group { flex:1; border-top:1px solid #eef2f5; }.group-head { display:flex; justify-content:space-between; align-items:center; color:#334155; font-size:14px; }.group-head span { display:grid; place-items:center; min-width:22px; height:22px; border-radius:999px; background:#eff6ff; color:#2563eb; font-size:12px; }.asset-card { display:grid; gap:4px; border:1px solid transparent; border-radius:13px; padding:10px; background:#f8fafc; color:#172033; text-align:left; cursor:pointer; }.asset-card:hover,.asset-card.active { border-color:rgba(15,118,110,.4); background:#f0fdfa; }.asset-card b { color:#0f766e; font-size:11px; }.asset-card.testcase b { color:#2563eb; }.asset-card strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }.asset-card small { color:#64748b; font-size:11px; }.map-pane { min-width:0; }.map-pane :deep(.compact-board .graph-panel) { min-height:calc(100vh - 245px); }.map-pane :deep(.compact-board .relation-graph) { min-height:490px; }.code-tree { display:grid; gap:10px; padding:10px; }.code-group { display:grid; gap:4px; }.code-group-head { padding:6px 4px; color:#475569; font-size:12px; font-weight:900; }.code-group-head small { float:right; color:#94a3b8; }.code-item { display:flex; align-items:center; gap:8px; width:100%; border:1px solid transparent; border-radius:10px; padding:9px; background:transparent; text-align:left; cursor:pointer; }.code-item:hover,.code-item.active { border-color:#c4b5fd; background:#f5f3ff; }.code-item i { color:#7c3aed; font-style:normal; }.code-item span { display:grid; min-width:0; gap:2px; }.code-item strong,.code-item small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.code-item strong { color:#172033; font-size:12px; }.code-item small { color:#64748b; font-size:11px; }.code-item em { margin-left:auto; border-radius:999px; padding:2px 6px; background:#ede9fe; color:#6d28d9; font-size:10px; font-style:normal; font-weight:900; }.empty-card { padding:18px; color:#94a3b8; text-align:center; font-size:13px; }
@media(max-width:1100px) { .trace-columns { grid-template-columns:minmax(210px,.7fr) minmax(400px,1.5fr); }.code-pane { grid-column:1/-1; }.code-tree { grid-template-columns:repeat(2,minmax(0,1fr)); } } @media(max-width:760px) { .workspace-toolbar { align-items:flex-start; flex-direction:column; }.toolbar-actions { justify-content:flex-start; }.trace-columns { grid-template-columns:1fr; }.code-pane { grid-column:auto; }.code-tree { grid-template-columns:1fr; }.asset-pane { max-height:500px; } }
</style>
