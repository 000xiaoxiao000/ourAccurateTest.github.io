<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 事实图谱</div>
        <h1>图谱工作台</h1>
        <p class="subtext">投影静态/动态/融合事实图，查看融合三态、断言一致性、预聚合读模型，并执行增量与失效传播。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadGraph" />
        <RouterLink :to="`/p/${projectId}/verification/orchestration?baselineId=${baselineId}`" class="secondary-button-link">分析编排</RouterLink>
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <!-- Projection toolbar -->
    <div class="toolbar">
      <span class="toolbar-label">投影事实图</span>
      <button v-for="p in projections" :key="p.key" type="button" class="chip-button"
              :disabled="busyKey === p.key" @click="runProjection(p.key)">
        {{ busyKey === p.key ? '投影中...' : p.label }}
      </button>
    </div>

    <!-- Summary strip -->
    <div v-if="graph" class="summary-strip">
      <span class="summary-chip" :class="{ on: graph.summary.staticReady }">静态 {{ graph.summary.staticReady ? '就绪' : '缺失' }}</span>
      <span class="summary-chip" :class="{ on: graph.summary.runtimeReady }">动态 {{ graph.summary.runtimeReady ? '就绪' : '缺失' }}</span>
      <span class="summary-chip" :class="{ on: graph.summary.traceabilityReady }">追溯 {{ graph.summary.traceabilityReady ? '就绪' : '缺失' }}</span>
      <span class="summary-state">融合状态：{{ fusionStateText(graph.summary.fusionState) }}</span>
    </div>

    <!-- Tabs -->
    <nav class="tabs">
      <button v-for="t in tabs" :key="t.key" type="button" class="tab" :class="{ active: activeTab === t.key }"
              @click="activeTab = t.key">{{ t.label }}</button>
    </nav>

    <!-- GRAPH QUERY TAB -->
    <div v-show="activeTab === 'graph'" class="tab-panel">
      <div class="query-bar">
        <label class="field-inline">
          <span>聚焦符号ID</span>
          <input v-model.trim="focusId" type="text" placeholder="留空=全量（受节点上限约束）" />
        </label>
        <label class="field-inline">
          <span>深度</span>
          <input v-model.number="depth" type="number" min="1" max="6" />
        </label>
        <label class="field-inline">
          <span>节点上限</span>
          <input v-model.number="maxNodes" type="number" min="1" max="2000" />
        </label>
        <label class="field-inline">
          <span>边上限</span>
          <input v-model.number="maxEdges" type="number" min="1" max="4000" />
        </label>
        <button type="button" class="primary-button" :disabled="loading" @click="loadGraph">查询</button>
      </div>

      <div v-if="graph" class="graph-result">
        <div v-if="graph.clipReasons.length" class="clip-banner">
          <strong>裁剪提示</strong>
          <ul><li v-for="(r, i) in graph.clipReasons" :key="i">{{ r }}</li></ul>
          <small v-if="graph.expandHint">{{ graph.expandHint }}</small>
        </div>
        <div class="graph-stats">
          <span>节点 {{ graph.nodes.length }}<template v-if="graph.totalActiveNodes"> / 活跃 {{ graph.totalActiveNodes }}</template></span>
          <span>边 {{ graph.edges.length }}<template v-if="graph.edgesInScope"> / 范围内 {{ graph.edgesInScope }}</template></span>
          <span>深度 {{ graph.depth }}</span>
        </div>
        <div class="node-kind-legend">
          <span v-for="(count, kind) in nodeKindCounts" :key="kind" class="kind-chip" :class="`k-${kind}`">
            {{ nodeKindText(kind) }} {{ count }}
          </span>
          <span class="view-toggle">
            <button type="button" :class="{ active: graphViewMode === 'graph' }" @click="graphViewMode = 'graph'">图形</button>
            <button type="button" :class="{ active: graphViewMode === 'table' }" @click="graphViewMode = 'table'">表格</button>
          </span>
        </div>

        <div v-if="graphViewMode === 'graph'" class="svg-canvas-wrap">
          <svg v-if="svgLayout.nodes.length" :viewBox="`0 0 ${SVG_W} ${SVG_H}`" class="svg-canvas" preserveAspectRatio="xMidYMid meet">
            <line v-for="(e, i) in svgLayout.edges" :key="`e${i}`"
                  :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" class="svg-edge" :class="`et-${e.type}`" />
            <g v-for="n in svgLayout.nodes" :key="n.id" :transform="`translate(${n.x},${n.y})`" class="svg-node-g"
               @mouseenter="hoverNode = n.id" @mouseleave="hoverNode = ''">
              <circle :r="hoverNode === n.id ? 9 : 6" class="svg-node" :class="`k-${n.kind}`" />
              <text v-if="hoverNode === n.id || svgLayout.nodes.length <= 40" :y="-11" class="svg-label">{{ n.label }}</text>
            </g>
          </svg>
          <div
            v-if="hoverGraphNode && hoverSvgNode"
            class="svg-node-tooltip"
            :class="{ 'near-right': hoverSvgNode.x > SVG_W * 0.62, 'near-bottom': hoverSvgNode.y > SVG_H * 0.58 }"
            :style="{ left: `${(hoverSvgNode.x / SVG_W) * 100}%`, top: `${(hoverSvgNode.y / SVG_H) * 100}%` }"
            role="tooltip"
          >
            <strong>{{ hoverGraphNode.displayName || hoverGraphNode.id }}</strong>
            <dl>
              <template v-for="item in nodeTooltipRows(hoverGraphNode)" :key="item.label">
                <dt>{{ item.label }}</dt>
                <dd>{{ item.value }}</dd>
              </template>
            </dl>
          </div>
          <p v-if="!svgLayout.nodes.length" class="table-note">当前范围没有可绘制的边关系，请调整聚焦符号或深度。</p>
          <p v-if="svgLayout.truncated" class="table-note">图形仅绘制前 {{ SVG_MAX_NODES }} 个节点，完整数据见表格视图。</p>
        </div>

        <table v-else class="data-table">
          <thead><tr><th>类型</th><th>名称</th><th>符号/定位</th></tr></thead>
          <tbody>
            <tr v-for="n in graph.nodes.slice(0, 200)" :key="n.id">
              <td><span class="kind-tag" :class="`k-${n.kind}`">{{ nodeKindText(n.kind) }}</span></td>
              <td class="mono">{{ n.displayName }}</td>
              <td class="mono muted">{{ n.stableSymbolId || n.locator || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="graph.nodes.length > 200" class="table-note">仅展示前 200 个节点，其余请用聚焦符号缩小范围。</p>
      </div>
      <div v-else-if="!loading" class="empty-state"><strong>暂无图谱数据</strong><span>请先投影事实图，再执行查询。</span></div>
    </div>

    <!-- FUSION TAB -->
    <div v-show="activeTab === 'fusion'" class="tab-panel">
      <button type="button" class="secondary-button" :disabled="fusionLoading" @click="loadFusion">
        {{ fusionLoading ? '加载中...' : '加载融合三态' }}
      </button>
      <div v-if="fusion" class="fusion-result">
        <div class="fusion-summary">
          <div class="fusion-card confirmed"><span class="num">{{ fusion.executedConfirmed }}</span><span>已执行确认</span></div>
          <div class="fusion-card reachable"><span class="num">{{ fusion.reachableNotExecuted }}</span><span>可达未执行</span></div>
          <div class="fusion-card observable"><span class="num">{{ fusion.notObservable }}</span><span>不可观测</span></div>
          <div class="fusion-card total"><span class="num">{{ fusion.totalMethods }}</span><span>方法总数</span></div>
        </div>
        <div v-if="fusion.clipped" class="notice warn">{{ fusion.clipReason }}</div>
        <table class="data-table">
          <thead><tr><th>状态</th><th>方法</th><th>符号</th></tr></thead>
          <tbody>
            <tr v-for="n in fusion.nodes.slice(0, 200)" :key="n.nodeId">
              <td><span class="fusion-tag" :class="n.fusionState.toLowerCase()">{{ fusionNodeText(n.fusionState) }}</span></td>
              <td class="mono">{{ n.displayName }}</td>
              <td class="mono muted">{{ n.stableSymbolId || n.locator || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ASSERTION TAB -->
    <div v-show="activeTab === 'assertion'" class="tab-panel">
      <div class="toolbar">
        <button type="button" class="secondary-button" :disabled="assertionLoading" @click="loadAssertion">刷新</button>
        <button type="button" class="primary-button" :disabled="assertionLoading" @click="runAssertion">重新校验</button>
      </div>
      <table v-if="assertions.length" class="data-table">
        <thead><tr><th>AC</th><th>结论</th><th>断言重合度</th><th>最佳用例</th></tr></thead>
        <tbody>
          <tr v-for="a in assertions" :key="a.criterionId">
            <td class="mono">{{ a.acKey }}</td>
            <td><span class="verdict-chip" :class="a.verdict === 'SUSPECTED_FALSE_PASS' ? 'failed' : 'passed'">{{ assertionVerdictText(a.verdict) }}</span></td>
            <td>{{ pct(a.assertionOverlap) }}</td>
            <td class="mono muted">{{ a.bestTestcaseKey || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="!assertionLoading" class="empty-state"><strong>暂无断言一致性结果</strong><span>点击「重新校验」生成。</span></div>
    </div>

    <!-- READ MODEL TAB -->
    <div v-show="activeTab === 'readModel'" class="tab-panel">
      <div class="toolbar">
        <button type="button" class="primary-button" :disabled="readModelBusy" @click="doRebuildReadModels">重建读模型</button>
        <select v-model="readModelKind" class="policy-select" @change="loadReadModel">
          <option value="RM_AC_COVERAGE_SUMMARY">AC 覆盖汇总</option>
          <option value="RM_SYMBOL_TEST_PROTECTION">符号测试保护</option>
          <option value="RM_HOT_CALL_CHAIN">热点调用链</option>
          <option value="RM_UNCOVERED_UNITS">未覆盖单元</option>
          <option value="RM_IMPACT_SUMMARY">影响面汇总</option>
          <option value="RM_QUALITY_GATE_SUMMARY">门禁指标汇总</option>
        </select>
      </div>
      <table v-if="readModelRows.length" class="data-table">
        <thead><tr><th>主体</th><th>载荷</th></tr></thead>
        <tbody>
          <tr v-for="r in readModelRows" :key="r.id">
            <td class="mono muted">{{ r.subjectId.slice(0, 12) }}</td>
            <td class="mono payload">{{ payloadPreview(r.payload) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="!readModelBusy" class="empty-state"><strong>暂无读模型数据</strong><span>先重建读模型，再选择类型查看。</span></div>
    </div>

    <!-- INCREMENTAL / DIFF TAB -->
    <div v-show="activeTab === 'ops'" class="tab-panel">
      <section class="ops-block">
        <h3 class="section-title">增量子图重算</h3>
        <p class="ops-hint">输入变更的符号（每行一个），只失效受影响子图并标记受影响 AC 需重跑。</p>
        <textarea v-model="changedSymbolsText" rows="4" class="ops-textarea" placeholder="com.example.OrderService#submit&#10;com.example.OrderService#validate" />
        <button type="button" class="primary-button" :disabled="incrementalBusy" @click="doIncremental">执行增量重算</button>
        <div v-if="incrementalResult" class="ops-result">
          受影响节点 {{ incrementalResult.affectedNodeCount }} · 受影响 AC {{ incrementalResult.affectedAcIds.length }} ·
          受影响用例 {{ incrementalResult.affectedTestcaseIds.length }} · 失效聚合 {{ incrementalResult.staleAggregates }}
        </div>
      </section>

      <section class="ops-block">
        <h3 class="section-title">六类 Diff 失效传播</h3>
        <div class="diff-grid">
          <button v-for="c in diffTypes" :key="c.key" type="button" class="chip-button"
                  :disabled="diffBusy" @click="doDiff(c.key)">{{ c.label }}</button>
        </div>
        <div v-if="diffResult" class="ops-result">
          变更类型 {{ diffResult.changeType }} · 失效快照 [{{ diffResult.invalidatedSnapshotKinds.join(', ') || '无' }}] ·
          失效聚合 [{{ diffResult.invalidatedAggregateKinds.join(', ') || '无' }}] ·
          基线过期：{{ diffResult.baselineMarkedStale ? '是' : '否' }}
        </div>
      </section>
    </div>

    <!-- BASELINE COMPARISON TAB -->
    <div v-show="activeTab === 'compare'" class="tab-panel">
      <section class="ops-block">
        <h3 class="section-title">新旧基线比较</h3>
        <p class="ops-hint">与另一个基线对比：哪些 AC 的证据闭合发生增减，哪些方法的融合三态发生迁移。当前基线作为目标（新），选择一个基准（旧）。</p>
        <label class="field-inline">
          <span>基准基线ID（旧）</span>
          <input v-model.trim="compareBaseId" type="text" placeholder="输入要对比的历史基线ID" />
        </label>
        <button type="button" class="primary-button" :disabled="compareBusy || !compareBaseId" @click="doCompare">执行比较</button>
      </section>

      <template v-if="comparison">
        <section class="ops-block">
          <h3 class="section-title">AC 证据变化 <span class="badge-count">{{ comparison.acDeltas.length }}</span></h3>
          <table v-if="comparison.acDeltas.length" class="data-table">
            <thead><tr><th>AC</th><th>变化</th><th>旧证据</th><th>新证据</th></tr></thead>
            <tbody>
              <tr v-for="d in comparison.acDeltas" :key="d.criterionId">
                <td class="mono">{{ d.acKey }}</td>
                <td><span class="verdict-chip" :class="acDeltaClass(d.changeType)">{{ acDeltaText(d.changeType) }}</span></td>
                <td class="mono muted">{{ d.beforeEvidence }}</td>
                <td class="mono">{{ d.afterEvidence }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table-note">两个基线的 AC 证据闭合没有差异。</p>
          <small class="ops-hint">证据码：T=用例 I=实现 C=覆盖率 E=执行；- 表示缺失。</small>
        </section>

        <section class="ops-block">
          <h3 class="section-title">融合三态迁移 <span class="badge-count">{{ comparison.fusionDeltas.length }}</span></h3>
          <table v-if="comparison.fusionDeltas.length" class="data-table">
            <thead><tr><th>符号</th><th>旧状态</th><th>新状态</th></tr></thead>
            <tbody>
              <tr v-for="(d, i) in comparison.fusionDeltas.slice(0, 200)" :key="i">
                <td class="mono">{{ d.symbol }}</td>
                <td><span class="fusion-tag" :class="d.beforeState.toLowerCase()">{{ fusionNodeText(d.beforeState) }}</span></td>
                <td><span class="fusion-tag" :class="d.afterState.toLowerCase()">{{ fusionNodeText(d.afterState) }}</span></td>
              </tr>
            </tbody>
          </table>
          <p v-else class="table-note">两个基线的融合三态没有差异。</p>
        </section>
      </template>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useToast } from '@/composables/useToast'
import {
  applyDiffInvalidation, compareBaselines, evaluateAssertionConsistency, fetchAssertionConsistency,
  fetchFusionView, fetchGraphView, fetchReadModel, incrementalRecompute,
  projectBranchCoverageGraph, projectControlFlowGraph, projectRuntimeCoverageGraph,
  projectStaticDependencyGraph, projectStaticGraph, projectTestExecutionGraph,
  projectTraceabilityGraph, rebuildReadModels,
  type AssertionConsistencyResult, type BaselineComparisonResult, type DiffChangeType,
  type DiffInvalidationResult, type FusionView, type GraphAggregate, type GraphNode, type GraphView,
  type IncrementalRecomputeResult, type ReadModelKind,
} from '@/api/graph'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))

const tabs = [
  { key: 'graph', label: '图谱查询' },
  { key: 'fusion', label: '融合三态' },
  { key: 'assertion', label: '断言一致性' },
  { key: 'readModel', label: '读模型' },
  { key: 'ops', label: '增量与失效' },
  { key: 'compare', label: '基线比较' },
] as const
const activeTab = ref<(typeof tabs)[number]['key']>('graph')

const projections = [
  { key: 'static', label: '静态图' },
  { key: 'cfg', label: '控制流图' },
  { key: 'dependency', label: '依赖图' },
  { key: 'coverage', label: '覆盖率' },
  { key: 'branch', label: '分支覆盖' },
  { key: 'testExec', label: '测试执行' },
  { key: 'traceability', label: '追溯图' },
] as const
type ProjectionKey = (typeof projections)[number]['key']

const diffTypes = [
  { key: 'REQUIREMENT', label: '需求变更' },
  { key: 'TESTCASE', label: '用例变更' },
  { key: 'CODE', label: '代码变更' },
  { key: 'CONFIG_SQL_API', label: '配置/SQL/API' },
  { key: 'COVERAGE', label: '覆盖率变更' },
  { key: 'EXECUTION_TRACE', label: '执行轨迹' },
  { key: 'PROMPT_MODEL_RULE', label: 'Prompt/模型/规则' },
  { key: 'ANALYZER_UPGRADE', label: '分析器升级' },
] as const

const loading = ref(false)
const error = ref('')
const busyKey = ref('')

const graph = ref<GraphView | null>(null)
const focusId = ref('')
const depth = ref(3)
const maxNodes = ref(500)
const maxEdges = ref(1000)

const fusion = ref<FusionView | null>(null)
const fusionLoading = ref(false)

const assertions = ref<AssertionConsistencyResult[]>([])
const assertionLoading = ref(false)

const readModelKind = ref<ReadModelKind>('RM_AC_COVERAGE_SUMMARY')
const readModelRows = ref<GraphAggregate[]>([])
const readModelBusy = ref(false)

const changedSymbolsText = ref('')
const incrementalResult = ref<IncrementalRecomputeResult | null>(null)
const incrementalBusy = ref(false)

const diffResult = ref<DiffInvalidationResult | null>(null)
const diffBusy = ref(false)

const graphViewMode = ref<'graph' | 'table'>('graph')
const hoverNode = ref('')
const SVG_W = 900
const SVG_H = 480
const SVG_MAX_NODES = 60

const compareBaseId = ref('')
const comparison = ref<BaselineComparisonResult | null>(null)
const compareBusy = ref(false)

const nodeKindCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const n of graph.value?.nodes ?? []) counts[n.kind] = (counts[n.kind] ?? 0) + 1
  return counts
})
const graphNodeById = computed(() => new Map((graph.value?.nodes ?? []).map((node) => [node.id, node])))
const hoverGraphNode = computed(() => hoverNode.value ? graphNodeById.value.get(hoverNode.value) : undefined)
const hoverSvgNode = computed(() => svgLayout.value.nodes.find((node) => node.id === hoverNode.value))

interface SvgNode { id: string; label: string; kind: string; x: number; y: number }
interface SvgEdge { x1: number; y1: number; x2: number; y2: number; type: string }

const svgLayout = computed<{ nodes: SvgNode[]; edges: SvgEdge[]; truncated: boolean }>(() => {
  const g = graph.value
  if (!g || !g.nodes.length) return { nodes: [], edges: [], truncated: false }
  const truncated = g.nodes.length > SVG_MAX_NODES
  const shown = g.nodes.slice(0, SVG_MAX_NODES)
  const cx = SVG_W / 2
  const cy = SVG_H / 2
  const radius = Math.min(SVG_W, SVG_H) / 2 - 40
  // Deterministic circular layout keyed by index; readable and dependency-free.
  const pos = new Map<string, SvgNode>()
  const count = shown.length
  shown.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / count - Math.PI / 2
    pos.set(n.id, {
      id: n.id,
      label: n.displayName.length > 24 ? `${n.displayName.slice(0, 24)}…` : n.displayName,
      kind: n.kind,
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
    })
  })
  const edges: SvgEdge[] = []
  for (const e of g.edges) {
    const s = pos.get(e.sourceNodeId)
    const t = pos.get(e.targetNodeId)
    if (s && t) edges.push({ x1: s.x, y1: s.y, x2: t.x, y2: t.y, type: e.type })
  }
  return { nodes: [...pos.values()], edges, truncated }
})

onMounted(loadGraph)

async function loadGraph() {
  if (loading.value || !baselineId.value) return
  loading.value = true; error.value = ''
  try {
    graph.value = await fetchGraphView(projectId.value, {
      baselineId: baselineId.value,
      focusId: focusId.value || undefined,
      depth: depth.value, maxNodes: maxNodes.value, maxEdges: maxEdges.value,
    })
  } catch (e) { error.value = msg(e) }
  finally { loading.value = false }
}

async function runProjection(key: ProjectionKey) {
  if (busyKey.value) return
  busyKey.value = key
  try {
    const pid = projectId.value, bid = baselineId.value
    if (key === 'static') await projectStaticGraph(pid, bid)
    else if (key === 'cfg') await projectControlFlowGraph(pid, bid)
    else if (key === 'dependency') await projectStaticDependencyGraph(pid, bid)
    else if (key === 'coverage') await projectRuntimeCoverageGraph(pid, bid)
    else if (key === 'branch') await projectBranchCoverageGraph(pid, bid)
    else if (key === 'testExec') await projectTestExecutionGraph(pid, bid)
    else if (key === 'traceability') await projectTraceabilityGraph(pid, bid)
    toast.success('投影完成')
    await loadGraph()
  } catch (e) { toast.error(msg(e)) }
  finally { busyKey.value = '' }
}

async function loadFusion() {
  if (fusionLoading.value) return
  fusionLoading.value = true
  try { fusion.value = await fetchFusionView(projectId.value, baselineId.value, 1000) }
  catch (e) { toast.error(msg(e)) }
  finally { fusionLoading.value = false }
}

async function loadAssertion() {
  if (assertionLoading.value) return
  assertionLoading.value = true
  try {
    const aggregates = await fetchAssertionConsistency(projectId.value, baselineId.value)
    assertions.value = aggregates.map(aggregateToAssertion)
  } catch (e) { toast.error(msg(e)) }
  finally { assertionLoading.value = false }
}

async function runAssertion() {
  if (assertionLoading.value) return
  assertionLoading.value = true
  try { assertions.value = await evaluateAssertionConsistency(projectId.value, baselineId.value) }
  catch (e) { toast.error(msg(e)) }
  finally { assertionLoading.value = false }
}

async function doRebuildReadModels() {
  if (readModelBusy.value) return
  readModelBusy.value = true
  try {
    const r = await rebuildReadModels(projectId.value, baselineId.value)
    toast.success(`读模型已重建：AC覆盖 ${r.acCoverageRows} · 保护 ${r.symbolProtectionRows} · 热点 ${r.hotCallChainRows} · 未覆盖 ${r.uncoveredUnitRows}`)
    await loadReadModel()
  } catch (e) { toast.error(msg(e)) }
  finally { readModelBusy.value = false }
}

async function loadReadModel() {
  if (readModelBusy.value) return
  readModelBusy.value = true
  try { readModelRows.value = await fetchReadModel(projectId.value, baselineId.value, readModelKind.value) }
  catch (e) { toast.error(msg(e)) }
  finally { readModelBusy.value = false }
}

async function doIncremental() {
  if (incrementalBusy.value) return
  const symbols = changedSymbolsText.value.split('\n').map(s => s.trim()).filter(Boolean)
  if (!symbols.length) { toast.error('请至少输入一个变更符号'); return }
  incrementalBusy.value = true
  try {
    incrementalResult.value = await incrementalRecompute(projectId.value, baselineId.value, symbols)
    toast.success('增量重算完成')
  } catch (e) { toast.error(msg(e)) }
  finally { incrementalBusy.value = false }
}

async function doDiff(changeType: DiffChangeType) {
  if (diffBusy.value) return
  diffBusy.value = true
  try {
    diffResult.value = await applyDiffInvalidation(projectId.value, baselineId.value, changeType)
    toast.success('失效传播已应用')
  } catch (e) { toast.error(msg(e)) }
  finally { diffBusy.value = false }
}

async function doCompare() {
  if (compareBusy.value || !compareBaseId.value) return
  compareBusy.value = true
  try {
    comparison.value = await compareBaselines(projectId.value, compareBaseId.value, baselineId.value)
    toast.success('基线比较完成')
  } catch (e) { toast.error(msg(e)) }
  finally { compareBusy.value = false }
}

function acDeltaText(t: string) {
  return t === 'ADDED' ? '新增' : t === 'REMOVED' ? '移除' : '证据变化'
}
function acDeltaClass(t: string) {
  return t === 'REMOVED' ? 'failed' : 'passed'
}

function aggregateToAssertion(a: GraphAggregate): AssertionConsistencyResult {
  const p = a.payload || {}
  return {
    criterionId: a.subjectId,
    acKey: String(p.acKey ?? ''),
    verdict: (p.verdict === 'SUSPECTED_FALSE_PASS' ? 'SUSPECTED_FALSE_PASS' : 'ASSERTION_ALIGNED'),
    assertionOverlap: Number(p.bestAssertionOverlap ?? 0),
    bestTestcaseKey: p.bestTestcaseKey ? String(p.bestTestcaseKey) : undefined,
  }
}

const NODE_KIND_TEXT: Record<string, string> = {
  REQUIREMENT: '需求', ACCEPTANCE_CRITERION: '验收标准', TESTCASE: '用例', TEST_EXECUTION: '测试执行',
  SOURCE_FILE: '文件', TYPE: '类型', METHOD: '方法', FIELD: '字段', ENDPOINT: '接口', CONFIG: '配置',
  SQL_STATEMENT: 'SQL', BASIC_BLOCK: '基本块', DECISION: '判定', BRANCH: '分支', RUNTIME_SPAN: '调用跨度',
  COVERAGE_UNIT: '覆盖单元',
}
function nodeKindText(kind: string) { return NODE_KIND_TEXT[kind] || kind }
function fusionNodeText(state: string) {
  return state === 'EXECUTED_CONFIRMED' ? '已执行确认' : state === 'REACHABLE_NOT_EXECUTED' ? '可达未执行' : '不可观测'
}
function fusionStateText(state: string) {
  const m: Record<string, string> = { FUSED: '已融合', STATIC_ONLY: '仅静态', DYNAMIC_ONLY: '仅动态', EMPTY: '空' }
  return m[state] || state
}
function assertionVerdictText(v: string) { return v === 'SUSPECTED_FALSE_PASS' ? '疑似假通过' : '断言对齐' }
function payloadPreview(payload: Record<string, unknown>) {
  return Object.entries(payload).slice(0, 4).map(([k, v]) => `${k}=${Array.isArray(v) ? `[${v.length}]` : String(v)}`).join(' · ')
}
function pct(v: number) { return `${Math.round((v || 0) * 1000) / 10}%` }
function msg(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
function nodeTooltipRows(node: GraphNode) {
  const rows = [
    { label: '类型', value: nodeKindText(node.kind) },
    { label: 'ID', value: node.id },
    { label: '符号', value: node.stableSymbolId },
    { label: '逻辑符号', value: node.logicalSymbolId },
    { label: '位置', value: node.locator },
    { label: '内容哈希', value: node.contentHash },
  ].filter((item): item is { label: string; value: string } => Boolean(item.value))

  const attributes = Object.entries(node.attributes || {})
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => ({ label: key, value: formatTooltipValue(value) }))
  return [...rows, ...attributes]
}
function formatTooltipValue(value: unknown) {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try { return JSON.stringify(value) }
  catch { return String(value) }
}
</script>
<style scoped>
.page-content { display: flex; flex-direction: column; gap: 16px; }
.page-header, .header-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.secondary-button, .secondary-button-link, .primary-button, .chip-button { display:inline-flex;align-items:center;min-height:34px;border-radius:8px;padding:6px 13px;font-weight:800;font-size:13px;text-decoration:none;cursor:pointer;border:1px solid var(--oat-border);background:var(--oat-surface-soft);color:var(--oat-text); }
.primary-button { border-color:var(--oat-primary);background:var(--oat-primary);color:#fff; }
.primary-button:disabled, .secondary-button:disabled, .chip-button:disabled { opacity:.45;cursor:not-allowed; }
.toolbar { display:flex;align-items:center;gap:8px;flex-wrap:wrap; }
.toolbar-label { font-size:12px;font-weight:700;color:var(--oat-text-muted); }
.summary-strip { display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 14px;border:1px solid var(--oat-border);border-radius:10px;background:#fff; }
.summary-chip { border-radius:999px;padding:3px 10px;font-size:12px;font-weight:800;background:rgba(100,116,139,.1);color:#64748b; }
.summary-chip.on { background:rgba(22,163,74,.12);color:#15803d; }
.summary-state { font-size:12px;color:var(--oat-text-muted);font-weight:700; }
.tabs { display:flex;gap:4px;border-bottom:2px solid var(--oat-border);flex-wrap:wrap; }
.tab { border:none;background:none;padding:8px 14px;font-size:14px;font-weight:800;color:var(--oat-text-muted);cursor:pointer;border-bottom:2px solid transparent;margin-bottom:-2px; }
.tab.active { color:var(--oat-primary);border-bottom-color:var(--oat-primary); }
.tab-panel { display:flex;flex-direction:column;gap:14px; }
.query-bar { display:flex;align-items:flex-end;gap:10px;flex-wrap:wrap; }
.field-inline { display:grid;gap:4px; }
.field-inline span { font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.field-inline input { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px;min-width:110px; }
.clip-banner { display:grid;gap:4px;padding:10px 14px;border:1px solid rgba(245,158,11,.3);border-radius:10px;background:rgba(245,158,11,.08); }
.clip-banner strong { color:#92400e;font-size:13px; }
.clip-banner ul { margin:0;padding-left:18px;font-size:12px;color:#92400e; }
.clip-banner small { font-size:11px;color:var(--oat-text-muted); }
.graph-stats { display:flex;gap:16px;font-size:13px;font-weight:700;color:var(--oat-text-secondary); }
.node-kind-legend { display:flex;gap:6px;flex-wrap:wrap;align-items:center; }
.view-toggle { display:inline-flex;margin-left:auto;border:1px solid var(--oat-border);border-radius:8px;overflow:hidden; }
.view-toggle button { border:none;background:#fff;padding:4px 12px;font-size:12px;font-weight:800;cursor:pointer;color:var(--oat-text-muted); }
.view-toggle button.active { background:var(--oat-primary);color:#fff; }
.svg-canvas-wrap { position:relative;border:1px solid var(--oat-border);border-radius:12px;background:#fff;padding:8px;overflow:hidden; }
.svg-canvas { width:100%;height:auto;display:block; }
.svg-edge { stroke:rgba(100,116,139,.35);stroke-width:1; }
.svg-edge.et-CALLS_RUNTIME { stroke:rgba(37,99,235,.5); }
.svg-edge.et-COVERED { stroke:rgba(22,163,74,.5); }
.svg-edge.et-TRUE, .svg-edge.et-FALSE { stroke:rgba(217,70,239,.45); }
.svg-node-g { cursor:pointer; }
.svg-node { fill:#94a3b8;stroke:#fff;stroke-width:1.5;transition:r .1s ease; }
.svg-node.k-METHOD { fill:#2563eb; }
.svg-node.k-BRANCH { fill:#a21caf; }
.svg-node.k-TYPE { fill:#0f766e; }
.svg-node.k-TEST_EXECUTION { fill:#f59e0b; }
.svg-node.k-DECISION { fill:#db2777; }
.svg-node.k-COVERAGE_UNIT { fill:#16a34a; }
.svg-label { font-size:10px;fill:var(--oat-text);text-anchor:middle;font-weight:700;paint-order:stroke;stroke:#fff;stroke-width:3px; }
.svg-node-tooltip {
  position:absolute;
  z-index:5;
  width:min(520px, calc(100% - 32px));
  max-height:min(360px, calc(100% - 32px));
  overflow:auto;
  transform:translate(12px, 12px);
  display:grid;
  gap:8px;
  padding:10px 12px;
  border:1px solid rgba(15,23,42,.14);
  border-radius:8px;
  background:rgba(255,255,255,.98);
  color:var(--oat-text);
  box-shadow:0 18px 42px rgba(15,23,42,.18);
  pointer-events:none;
}
.svg-node-tooltip.near-right { transform:translate(calc(-100% - 12px), 12px); }
.svg-node-tooltip.near-bottom { transform:translate(12px, calc(-100% - 12px)); }
.svg-node-tooltip.near-right.near-bottom { transform:translate(calc(-100% - 12px), calc(-100% - 12px)); }
.svg-node-tooltip strong { font-size:13px;line-height:1.35;overflow-wrap:anywhere; }
.svg-node-tooltip dl { display:grid;grid-template-columns:auto minmax(0, 1fr);gap:4px 8px;margin:0;font-size:12px;line-height:1.45; }
.svg-node-tooltip dt { color:var(--oat-text-muted);font-weight:800;white-space:nowrap; }
.svg-node-tooltip dd { margin:0;overflow-wrap:anywhere;white-space:pre-wrap; }
.badge-count { border-radius:999px;padding:1px 8px;font-size:11px;font-weight:900;background:rgba(37,99,235,.12);color:#1d4ed8; }
.kind-chip, .kind-tag { border-radius:6px;padding:2px 8px;font-size:11px;font-weight:800;background:rgba(100,116,139,.1);color:#475569; }
.kind-tag.k-METHOD, .kind-chip.k-METHOD { background:rgba(37,99,235,.12);color:#1d4ed8; }
.kind-tag.k-BRANCH, .kind-chip.k-BRANCH { background:rgba(217,70,239,.12);color:#a21caf; }
.kind-tag.k-TYPE, .kind-chip.k-TYPE { background:rgba(13,148,136,.12);color:#0f766e; }
.kind-tag.k-TEST_EXECUTION, .kind-chip.k-TEST_EXECUTION { background:rgba(245,158,11,.14);color:#92400e; }
.data-table { width:100%;border-collapse:collapse;font-size:13px; }
.data-table th { padding:8px 10px;border-bottom:2px solid var(--oat-border);text-align:left;font-size:12px;color:var(--oat-text-muted); }
.data-table td { padding:7px 10px;border-bottom:1px solid var(--oat-border);vertical-align:top; }
.mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px; }
.mono.muted, .muted { color:var(--oat-text-muted); }
.mono.payload { word-break:break-all; }
.table-note { font-size:12px;color:var(--oat-text-muted); }
.fusion-summary { display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px; }
.fusion-card { display:grid;gap:2px;padding:14px;border:1px solid var(--oat-border);border-radius:10px;text-align:center;background:#fff; }
.fusion-card .num { font-size:26px;font-weight:900; }
.fusion-card.confirmed { border-color:rgba(22,163,74,.3);color:#15803d; }
.fusion-card.reachable { border-color:rgba(245,158,11,.3);color:#92400e; }
.fusion-card.observable { border-color:rgba(100,116,139,.3);color:#475569; }
.fusion-card.total { border-color:rgba(37,99,235,.3);color:#1d4ed8; }
.fusion-tag, .verdict-chip { border-radius:999px;padding:2px 8px;font-size:11px;font-weight:800; }
.fusion-tag.executed_confirmed { background:rgba(22,163,74,.12);color:#15803d; }
.fusion-tag.reachable_not_executed { background:rgba(245,158,11,.14);color:#92400e; }
.fusion-tag.not_observable { background:rgba(100,116,139,.12);color:#475569; }
.verdict-chip.passed { background:rgba(22,163,74,.1);color:#15803d; }
.verdict-chip.failed { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.policy-select { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px; }
.ops-block { display:grid;gap:10px;padding:16px;border:1px solid var(--oat-border);border-radius:12px;background:#fff; }
.section-title { margin:0;font-size:15px; }
.ops-hint { margin:0;font-size:12px;color:var(--oat-text-muted); }
.ops-textarea { border:1px solid var(--oat-border);border-radius:8px;padding:8px 10px;font-size:13px;font-family:ui-monospace,monospace;resize:vertical; }
.ops-result { padding:10px 12px;border-radius:8px;background:var(--oat-surface-soft);font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.diff-grid { display:flex;gap:8px;flex-wrap:wrap; }
.notice.danger { border:1px solid rgba(220,38,38,.25);border-radius:10px;padding:10px 14px;color:var(--oat-danger);background:rgba(220,38,38,.06); }
.notice.warn { border:1px solid rgba(245,158,11,.3);border-radius:10px;padding:10px 14px;color:#92400e;background:rgba(245,158,11,.08);font-size:13px; }
.empty-state { display:grid;gap:8px;min-height:140px;place-content:center;text-align:center;border:1px dashed var(--oat-border);border-radius:12px;padding:24px; }
.empty-state strong { font-size:16px; }
.empty-state span { color:var(--oat-text-muted);font-size:13px; }
</style>
