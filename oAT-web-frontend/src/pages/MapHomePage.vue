<template>
  <section class="trace-workspace">
    <header class="workspace-toolbar">
      <div>
        <span class="eyebrow">Traceability Workspace</span>
        <strong>需求 · 测试 · 代码追溯</strong>
        <span>{{ baselineStatusText }}</span>
      </div>
      <div class="toolbar-actions">
        <label v-if="baselines.length" class="baseline-select">
          <span>分析基线</span>
          <select v-model="selectedBaselineId" :disabled="map.loading.value" @change="reloadBaseline">
            <option v-for="baseline in baselines" :key="baseline.id" :value="baseline.id">
              {{ baseline.name || baseline.id }} · {{ baseline.status }}
            </option>
          </select>
        </label>
        <AppRefreshButton :loading="map.loading.value" @click="reload" />
      </div>
    </header>

    <div v-if="!baselines.length && !map.response.value && !map.loading.value && !map.error.value" class="workspace-notice">
      暂无分析基线。三层追溯需在 AI 验证页创建基线；代码调用链路图可基于已导入源码展示。
    </div>
    <div v-if="map.error.value" class="workspace-notice error">{{ map.error.value }}</div>
    <div v-for="warning in map.response.value?.warnings || []" :key="warning" class="workspace-notice warning">
      {{ warning }}
    </div>

    <section v-if="map.response.value" class="summary-strip">
      <div><strong>{{ percent(map.response.value.summary.completeChainRate) }}</strong><span>完整链路率</span></div>
      <div><strong>{{ map.response.value.summary.requirementCount }}</strong><span>需求</span></div>
      <div><strong>{{ map.response.value.summary.testcaseCount }}</strong><span>测试用例</span></div>
      <div><strong>{{ map.response.value.summary.codeCount }}</strong><span>代码节点</span></div>
      <div><strong>{{ map.response.value.summary.dynamicEvidenceCount }}</strong><span>动态证据</span></div>
      <div><strong>{{ map.response.value.summary.brokenRequirementCount + map.response.value.summary.brokenTestcaseCount }}</strong><span>断链</span></div>
    </section>

    <div class="trace-controls">
      <label class="search-box">
        <input ref="searchInput" v-model.trim="map.keyword.value" placeholder="搜索需求、用例、文件、类、方法" />
      </label>
      <div class="filter-tabs">
        <button v-for="item in filters" :key="item.value" :class="{ active: map.filter.value === item.value }" type="button" @click="map.filter.value = item.value">
          {{ item.label }}
        </button>
      </div>
    </div>

    <div class="workspace-tabs" role="tablist" aria-label="链路地图视图">
      <button type="button" :class="{ active: activeTab === 'trace' }" @click="activeTab = 'trace'">三层追溯关系图</button>
      <button type="button" :class="{ active: activeTab === 'calls' }" @click="activeTab = 'calls'">代码间调用链路图</button>
    </div>

    <section v-if="activeTab === 'trace'" class="tab-page trace-tab">
      <main class="map-pane trace-map-pane">
        <div class="pane-head map-head">
          <div>
            <strong>三层追溯关系图</strong>
            <span>正向为需求驱动下层，反向为下层溯源上层</span>
          </div>
          <div class="map-legend compact">
            <span><i class="legend req"></i>需求层</span>
            <span><i class="legend tc"></i>测试用例层</span>
            <span><i class="legend code"></i>代码层</span>
            <span><i class="line-sample solid"></i>正向</span>
            <span><i class="line-sample derived"></i>反向/推断</span>
          </div>
        </div>
        <div v-if="map.loading.value" class="trace-empty"><span class="spin">◌</span><span>加载中…</span></div>
        <div v-else-if="!traceGraph.nodes.length" class="trace-empty">暂无可展示的三层追溯关系。</div>
        <div v-else class="trace-map-scroll">
          <svg class="trace-map-svg" :viewBox="`0 0 ${traceGraph.width} ${traceGraph.height}`" role="img" aria-label="三层追溯关系图" @click="clearTraceGraphSelection">
            <defs>
              <marker id="trace-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L9,3 z" fill="#64748b" />
              </marker>
            </defs>
            <g v-for="lane in traceGraph.lanes" :key="lane.key">
              <rect class="lane-band" :x="lane.x" :y="lane.y" :width="lane.width" :height="lane.height" />
              <text class="lane-title" :x="lane.x + lane.width / 2" :y="lane.y + 18">{{ lane.title }}</text>
            </g>
            <g class="trace-node-layer">
              <g
                v-for="node in traceGraph.nodes"
                :key="node.id"
                :class="['trace-svg-node', node.tone, { active: traceGraph.selectedNodeId === node.id, linked: traceGraph.relatedNodeIds.has(node.id), dimmed: traceGraph.hasSelection && !traceGraph.relatedNodeIds.has(node.id) }]"
                @click.stop="selectTraceNode(node.id)"
              >
                <title>{{ traceNodeTitle(node.raw) }}</title>
                <rect :x="node.x" :y="node.y" :width="node.width" :height="node.height" rx="3" />
                <text class="node-title" :x="node.x + node.width / 2" :y="node.y + 22">{{ node.shortLabel }}</text>
                <text class="node-subtitle" :x="node.x + node.width / 2" :y="node.y + 40">{{ node.shortDesc }}</text>
              </g>
            </g>
            <g class="trace-edge-layer">
              <g v-for="edge in traceGraph.edges" :key="edge.id" :class="['trace-svg-edge', evidenceTone(edge.raw), { active: traceGraph.relatedEdgeIds.has(edge.id), dimmed: traceGraph.hasSelection && !traceGraph.relatedEdgeIds.has(edge.id) }]">
                <title>{{ traceEdgeTitle(edge.raw) }}</title>
                <path :d="edge.path" marker-end="url(#trace-arrow)" />
                <text :x="edge.labelX" :y="edge.labelY">{{ relationLabel(edge.raw.relation) }}</text>
              </g>
            </g>
          </svg>
        </div>
      </main>

      <section v-if="selectedTraceNode" class="node-detail-card" :class="nodeTone(selectedTraceNode)">
        <div>
          <span>{{ nodeKindLabel(selectedTraceNode.kind) }}</span>
          <strong>{{ selectedTraceNode.label }}</strong>
          <small>{{ selectedTraceNode.locator || selectedTraceNode.symbol || selectedTraceNode.id }}</small>
        </div>
        <p>{{ selectedTraceNode.description || '暂无描述' }}</p>
        <div class="node-detail-actions">
          <button v-if="selectedTraceNode.kind.startsWith('CODE_')" type="button" @click="locateTraceCodeNode(selectedTraceNode.id)">在代码树中定位</button>
          <button v-if="selectedTraceNode.kind.startsWith('CODE_')" type="button" @click="openTraceCodeContext(selectedTraceNode.id)">查看调用上下文</button>
          <button type="button" @click="selectedTraceId = ''">关闭</button>
        </div>
      </section>

      <div class="trace-reference-grid">
        <section class="reference-panel">
          <h2>图例说明</h2>
          <table>
            <thead><tr><th>符号</th><th>含义</th></tr></thead>
            <tbody>
              <tr><td><span class="line-cell solid"></span>实线箭头</td><td><strong>正向追溯：</strong>上层定义/驱动下层</td></tr>
              <tr><td><span class="line-cell dashed"></span>虚线箭头</td><td><strong>反向追溯：</strong>下层溯源/服务于上层</td></tr>
              <tr><td><span class="dot blue"></span>蓝色框</td><td>需求层（业务功能）</td></tr>
              <tr><td><span class="dot orange"></span>橙色框</td><td>测试用例层（验证场景）</td></tr>
              <tr><td><span class="dot green"></span>绿色框</td><td>代码层（源码实现）</td></tr>
            </tbody>
          </table>
        </section>

        <section class="reference-panel relation-summary">
          <h2>追溯关系汇总表</h2>
          <table>
            <thead>
              <tr><th>追溯方向</th><th>从 → 到</th><th>箭头类型</th><th>含义</th><th>示例</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in relationSummaryRows" :key="row.key">
                <td>{{ row.direction }}</td>
                <td>{{ row.fromTo }}</td>
                <td>{{ row.arrow }}</td>
                <td>{{ row.meaning }}</td>
                <td>{{ row.example }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>
    </section>

    <section v-else class="tab-page calls-tab">
      <main class="call-graph-pane">
        <div class="call-head">
          <div class="call-head-main">
            <div>
              <strong>{{ callViewMeta.title }}</strong>
              <span>{{ callViewMeta.description }}</span>
            </div>
            <span class="call-count">{{ callViewMeta.count }}</span>
          </div>
          <div class="call-toolbar">
            <div class="segmented-control mode-control">
              <button type="button" :class="{ active: callViewMode === 'graph' }" @click="callViewMode = 'graph'">方法调用图</button>
              <button type="button" :class="{ active: callViewMode === 'dependency' }" @click="callViewMode = 'dependency'">依赖关系图</button>
              <button type="button" :class="{ active: callViewMode === 'control' }" @click="callViewMode = 'control'">控制流图</button>
              <button type="button" :class="{ active: callViewMode === 'coverage' }" @click="callViewMode = 'coverage'">覆盖率数据</button>
            </div>
            <div v-if="callViewMode === 'graph'" class="segmented-control compact-control">
              <button type="button" :class="{ active: callGraphScope === 'overview' }" @click="callGraphScope = 'overview'">全局</button>
              <button type="button" :class="{ active: callGraphScope === 'impact' }" :disabled="!map.focusId.value" @click="callGraphScope = 'impact'">影响范围</button>
              <button type="button" :class="{ active: callGraphScope === 'context' }" :disabled="!map.focusId.value" @click="callGraphScope = 'context'">上下文</button>
            </div>
            <button
              v-if="callViewMode === 'graph'"
              type="button"
              :class="['ai-call-toggle', { active: aiCallAnalysisEnabled }]"
              :disabled="map.loading.value"
              title="基于源码方法体补推候选调用关系，源码较多时会更慢"
              @click="toggleAiCallAnalysis"
            >
              AI 调用分析
            </button>
          </div>
        </div>
        <div v-if="callViewMode === 'graph'" class="call-graph-legend">
          <span><i class="call-color controller"></i>Controller/入口</span>
          <span><i class="call-color method"></i>普通方法</span>
          <span><i class="call-color private"></i>私有方法</span>
          <span><i class="call-color static"></i>静态方法</span>
          <span><i class="call-color recursive"></i>递归</span>
          <span><i class="line-sample derived"></i>虚线：结构包含 / 源码候选</span>
        </div>
        <div v-if="map.loading.value" class="graph-loading-state">
          <span class="spin">◌</span>
          <strong>{{ slowLoading ? '源码调用关系仍在解析' : '正在加载代码图谱' }}</strong>
          <small>{{ slowLoading ? '源码文件较多时需要更长时间；可等待完成，或稍后刷新当前基线。' : '正在读取当前基线的源码树、调用边、依赖和控制流。' }}</small>
        </div>
        <div v-else-if="callViewMode === 'coverage'" class="coverage-data-panel">
          <div v-if="!coverageRows.length" class="empty-card">暂无可展示的覆盖率数据。请在分析基线中选择覆盖率或执行资产。</div>
          <table v-else class="coverage-table">
            <thead>
              <tr><th>代码节点</th><th>证据状态</th><th>覆盖摘要</th><th>定位</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in coverageRows" :key="row.id">
                <td><button type="button" @click="selectCodeNode(row.id)">{{ row.label }}</button></td>
                <td><span :class="['coverage-pill', row.state.toLowerCase()]">{{ row.state }}</span></td>
                <td>{{ row.summary }}</td>
                <td>{{ row.locator }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="callViewMode === 'dependency'" class="code-analysis-panel">
          <div v-if="!dependencyGraph.nodes.length" class="empty-card">暂无源码依赖数据。请确认当前基线已绑定源码，且源码快照包含可解析的 import 信息。</div>
          <svg v-else class="mini-code-graph" :viewBox="`0 0 ${dependencyGraph.width} ${dependencyGraph.height}`" role="img" aria-label="代码依赖关系图" @click="clearMiniGraphSelection">
            <defs><marker id="dependency-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#94a3b8" /></marker></defs>
            <g
              v-for="edge in dependencyGraph.edges"
              :key="edge.id"
              :class="['mini-graph-edge', { active: miniGraphHighlight.relatedEdgeIds.has(edge.id), dimmed: miniGraphHighlight.hasSelection && !miniGraphHighlight.relatedEdgeIds.has(edge.id) }]"
            >
              <path :d="edge.path" marker-end="url(#dependency-arrow)" />
            </g>
            <g
              v-for="node in dependencyGraph.nodes"
              :key="node.id"
              :class="['mini-graph-node', node.tone, { active: miniGraphHighlight.activeNodeId === node.id, linked: miniGraphHighlight.relatedNodeIds.has(node.id), dimmed: miniGraphHighlight.hasSelection && !miniGraphHighlight.relatedNodeIds.has(node.id) }]"
              @click.stop="selectMiniGraphNode(node)"
            >
              <title>{{ miniNodeTitle(node) }}</title>
              <rect :x="node.x" :y="node.y" :width="node.width" :height="node.height" rx="5" />
              <text :x="node.x + node.width / 2" :y="node.y + 24">{{ node.label }}</text>
            </g>
          </svg>
        </div>
        <div v-else-if="callViewMode === 'control'" class="code-analysis-panel">
          <div v-if="!controlFlowGraph.nodes.length" class="empty-card">暂无控制流数据。请确认当前基线已绑定源码，且源码快照包含可解析的方法体。</div>
          <svg v-else class="mini-code-graph" :viewBox="`0 0 ${controlFlowGraph.width} ${controlFlowGraph.height}`" role="img" aria-label="代码控制流图" @click="clearMiniGraphSelection">
            <defs><marker id="control-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#94a3b8" /></marker></defs>
            <g
              v-for="edge in controlFlowGraph.edges"
              :key="edge.id"
              :class="['mini-graph-edge', { active: miniGraphHighlight.relatedEdgeIds.has(edge.id), dimmed: miniGraphHighlight.hasSelection && !miniGraphHighlight.relatedEdgeIds.has(edge.id) }]"
            >
              <path :d="edge.path" marker-end="url(#control-arrow)" />
            </g>
            <g
              v-for="node in controlFlowGraph.nodes"
              :key="node.id"
              :class="['mini-graph-node', node.tone, { active: miniGraphHighlight.activeNodeId === node.id, linked: miniGraphHighlight.relatedNodeIds.has(node.id), dimmed: miniGraphHighlight.hasSelection && !miniGraphHighlight.relatedNodeIds.has(node.id) }]"
              @click.stop="selectMiniGraphNode(node)"
            >
              <title>{{ miniNodeTitle(node) }}</title>
              <rect :x="node.x" :y="node.y" :width="node.width" :height="node.height" rx="5" />
              <text :x="node.x + node.width / 2" :y="node.y + 20">{{ node.label }}</text>
              <text class="mini-node-subtitle" :x="node.x + node.width / 2" :y="node.y + 37">{{ node.subtitle }}</text>
            </g>
          </svg>
        </div>
        <div v-else-if="!callGraph.nodes.length" class="empty-card">暂无代码调用关系。请确认当前基线已绑定源码，且源码快照包含可解析的类、方法和调用信息。</div>
        <div v-else class="call-map-scroll large" :class="{ fullscreen: callGraphFullscreen }">
          <div class="call-graph-toolbar">
            <span class="graph-mode-label">调用关系图</span>
            <button type="button" title="缩小" @click="callZoom = Math.max(.5, callZoom - .1)">−</button>
            <span>{{ Math.round(callZoom * 100) }}%</span>
            <button type="button" title="放大" @click="callZoom = Math.min(2, callZoom + .1)">＋</button>
            <button type="button" title="重置缩放" @click="callZoom = 1">1:1</button>
            <button type="button" title="下载调用图" @click="downloadCallGraph">⇩ 下载</button>
            <button type="button" :title="callGraphFullscreen ? '退出全屏' : '全屏查看'" @click="toggleCallGraphFullscreen">
              {{ callGraphFullscreen ? '退出' : '全屏' }}
            </button>
          </div>
          <div class="call-graph-viewport">
          <svg class="call-map-svg" :width="callGraph.width * callZoom" :height="callGraph.height * callZoom" :viewBox="`0 0 ${callGraph.width} ${callGraph.height}`" role="img" aria-label="代码调用链关系图" @click="clearCallGraphSelection">
            <defs>
              <marker id="call-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L9,3 z" fill="#64748b" />
              </marker>
            </defs>
            <g
              v-for="edge in callGraph.edges"
              :key="edge.id"
              :class="['call-svg-edge', evidenceTone(edge.raw), { structure: edge.raw.generationMethod === 'CODE_STRUCTURE', inferred: edge.raw.generationMethod === 'SOURCE_EXPRESSION', recursive: edge.source === edge.target, active: callGraph.relatedEdgeIds.has(edge.id), dimmed: callGraph.hasSelection && !callGraph.relatedEdgeIds.has(edge.id) }]"
            >
              <title>{{ callEdgeTitle(edge.raw) }}</title>
              <path :d="edge.path" marker-end="url(#call-arrow)" />
              <template v-if="edge.raw.generationMethod !== 'CODE_STRUCTURE'">
                <rect class="edge-label-bg" :x="edge.labelX - edge.labelWidth / 2" :y="edge.labelY - 13" :width="edge.labelWidth" height="19" rx="4" />
                <text :x="edge.labelX" :y="edge.labelY">{{ edge.label }}</text>
              </template>
            </g>
            <g
              v-for="node in callGraph.nodes"
              :key="node.id"
              :class="['call-svg-node', `call-${callNodeTone(node.raw)}`, { active: callGraph.selectedNodeId === node.id, linked: callGraph.relatedNodeIds.has(node.id), dimmed: callGraph.hasSelection && !callGraph.relatedNodeIds.has(node.id), recursive: Boolean(node.raw.metadata?.recursive) }]"
              @click.stop="selectCallGraphNode(node.id)"
            >
              <title>{{ traceNodeTitle(node.raw) }}</title>
              <rect :x="node.x" :y="node.y" :width="node.width" :height="node.height" rx="3" />
              <text :x="node.x + node.width / 2" :y="node.y + 20">{{ node.shortLabel }}</text>
              <text class="call-node-subtitle" :x="node.x + node.width / 2" :y="node.y + 36">{{ node.shortDesc }}</text>
            </g>
          </svg>
          </div>
        </div>
      </main>

      <aside class="code-side-pane">
        <div class="pane-head">
          <div>
            <strong>代码树</strong>
            <span>{{ map.response.value?.summary.codeCount || 0 }} 个符号</span>
          </div>
          <div class="tree-actions">
            <button type="button" @click="expandTree">展开</button>
            <button type="button" @click="collapseTree">折叠</button>
          </div>
        </div>
        <section v-if="map.selectedNode.value" class="code-node-detail" :class="nodeTone(map.selectedNode.value)">
          <span>{{ nodeKindLabel(map.selectedNode.value.kind) }}</span>
          <strong>{{ map.selectedNode.value.label }}</strong>
          <small>{{ map.selectedNode.value.locator || map.selectedNode.value.symbol || map.selectedNode.value.id }}</small>
          <p>{{ selectedCodeContextText }}</p>
        </section>
        <div class="legend-row">
          <span><i class="legend static"></i>静态</span>
          <span><i class="legend dynamic"></i>动态</span>
          <span><i class="legend both"></i>BOTH</span>
        </div>
        <div v-if="!treeNodes.length" class="empty-card">暂无源码树。请导入源码或生成静态源码索引。</div>
        <div v-else class="tree-scroll">
          <TreeNodeRow
            v-for="node in treeNodes"
            :key="node.key"
            :node="node"
            :depth="0"
            :open-dirs="displayOpenDirs"
            :open-classes="displayOpenClasses"
            :selected-id="map.focusId.value"
            :linked-ids="map.linkedNodeIds.value"
            :get-link-count="map.linkCount"
            @toggle-dir="toggleDir"
            @toggle-class="toggleClass"
            @select="selectCodeNode"
          />
        </div>
      </aside>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TreeNodeRow from '@/components/map/TreeNodeRow.vue'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { fetchVerificationOverview } from '@/api/verification'
import type { VerificationBaseline } from '@/api/verification'
import type { CodeTreeNode, TraceabilityEdge, TraceabilityNode, TraceRelation } from '@/api/traceabilityMap'
import { useTraceabilityMap } from '@/features/map/composables/useTraceabilityMap'
import type { TraceFilter } from '@/features/map/composables/useTraceabilityMap'

interface TreeNode {
  key: string
  name: string
  displayName: string
  isDir: boolean
  children: TreeNode[]
  file?: { id: string; nodeId: string; name: string; methods: Array<{ nodeId: string; name: string; line?: number }> }
}
interface SvgNode {
  id: string
  x: number
  y: number
  width: number
  height: number
  tone: string
  shortLabel: string
  shortDesc: string
  raw: TraceabilityNode
}
interface SvgEdge {
  id: string
  source: string
  target: string
  path: string
  labelX: number
  labelY: number
  label: string
  labelWidth: number
  raw: TraceabilityEdge
}
interface MiniGraphNode { id: string; x: number; y: number; width: number; height: number; label: string; subtitle?: string; tone: string; nodeId?: string }
interface MiniGraphEdge { id: string; source: string; target: string; path: string }

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const map = useTraceabilityMap(() => projectId.value)
const baselines = ref<VerificationBaseline[]>([])
const selectedBaselineId = ref('')
const openDirs = ref<Set<string>>(new Set())
const openClasses = ref<Set<string>>(new Set())
const activeTab = ref<'trace' | 'calls'>('trace')
const callViewMode = ref<'graph' | 'dependency' | 'control' | 'coverage'>('graph')
const callGraphScope = ref<'overview' | 'impact' | 'context'>('overview')
const selectedTraceId = ref('')
const selectedCallGraphId = ref('')
const selectedMiniGraphId = ref('')
const miniGraphFocusHighlightDisabled = ref(false)
const callZoom = ref(1)
const callGraphFullscreen = ref(false)
const slowLoading = ref(false)
const loadedCodeDataKey = ref('')
const loadingCodeDataKey = ref('')
const aiCallAnalysisEnabled = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)
let slowLoadingTimer: number | undefined

watch([activeTab, callViewMode], () => {
  exitCallGraphFullscreen()
  selectedMiniGraphId.value = ''
  miniGraphFocusHighlightDisabled.value = false
  if (activeTab.value === 'calls') {
    void ensureCodeDataLoaded()
  }
})

watch(() => map.loading.value, (loading) => {
  if (slowLoadingTimer !== undefined) {
    window.clearTimeout(slowLoadingTimer)
    slowLoadingTimer = undefined
  }
  slowLoading.value = false
  if (loading) {
    slowLoadingTimer = window.setTimeout(() => {
      slowLoading.value = true
    }, 8000)
  } else if (activeTab.value === 'calls') {
    void ensureCodeDataLoaded()
  }
})

const filters: Array<{ value: TraceFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'COMPLETE', label: '完整链' },
  { value: 'BROKEN', label: '断链' },
  { value: 'DYNAMIC_CONFIRMED', label: '动态确认' },
  { value: 'STATIC_BRIDGED', label: '静态补全' },
  { value: 'PENDING_REVIEW', label: '待审核' },
]

const baselineStatusText = computed(() => {
  const baseline = map.response.value?.baseline
  if (!baseline) return '尚未加载分析基线'
  const version = [baseline.sourceBranch, baseline.sourceCommit].filter(Boolean).join(' · ')
  return `当前基线：${baseline.name || baseline.id}${version ? ` · ${version}` : ''}`
})

const codeKeyword = computed(() => normalizeSearch(map.keyword.value))
const treeNodes = computed(() => filterCodeTreeNodes(map.codeTree.value || [], codeKeyword.value).map(toTreeNode))
const displayOpenDirs = computed(() => {
  if (!codeKeyword.value) return openDirs.value
  const dirs = new Set<string>()
  treeNodes.value.forEach((node) => collectTreeOpenKeys(node, dirs, new Set()))
  return dirs
})
const displayOpenClasses = computed(() => {
  if (!codeKeyword.value) return openClasses.value
  const classes = new Set<string>()
  treeNodes.value.forEach((node) => collectTreeOpenKeys(node, new Set(), classes))
  return classes
})
const selectedTraceNode = computed(() => selectedTraceId.value ? map.nodeById.value.get(selectedTraceId.value) || null : null)
const traceGraph = computed(() => buildTraceGraph(map.nodes.value, map.filteredEdges.value, selectedTraceId.value))
const callGraph = computed(() => buildCallGraph(map.nodes.value, map.edges.value, map.response.value?.codeGraph?.controlFlows || [], map.focusId.value, selectedCallGraphId.value, callGraphScope.value, codeKeyword.value))
const dependencyGraph = computed(() => buildDependencyGraph(map.response.value?.codeGraph?.dependencies || [], codeKeyword.value, map.nodes.value, map.focusId.value))
const controlFlowGraph = computed(() => buildControlFlowGraph(map.response.value?.codeGraph?.controlFlows || [], map.focusId.value, codeKeyword.value, map.nodes.value))
const visibleMiniGraph = computed(() => callViewMode.value === 'dependency' ? dependencyGraph.value : callViewMode.value === 'control' ? controlFlowGraph.value : null)
const miniGraphHighlight = computed(() => buildMiniGraphHighlight(visibleMiniGraph.value, selectedMiniGraphId.value, miniGraphFocusHighlightDisabled.value ? '' : map.focusId.value))
const callViewMeta = computed(() => {
  if (callViewMode.value === 'dependency') {
    return {
      title: '依赖关系图',
      description: '按当前代码节点范围展示源码 import 和类依赖关系',
      count: `${dependencyGraph.value.edges.length} 条依赖`,
    }
  }
  if (callViewMode.value === 'control') {
    return {
      title: '控制流图',
      description: '按当前方法、类或文件范围展示条件、循环、返回等执行结构',
      count: `${controlFlowGraph.value.nodes.length} 个步骤`,
    }
  }
  if (callViewMode.value === 'coverage') {
    return {
      title: '覆盖率数据',
      description: '展示覆盖/执行证据匹配到的代码节点',
      count: `${coverageRows.value.length} 条数据`,
    }
  }
  return {
    title: '代码间调用链路图',
    description: '按调用边展示代码节点关系，动态确认、静态补全、静态调用分层标识',
    count: `${callGraph.value.edges.length} 条调用`,
  }
})
const selectedCodeContextText = computed(() => {
  const node = map.selectedNode.value
  if (!node) return ''
  const outgoing = (map.outgoingByNode.value.get(node.id) || []).filter((edge) => edge.relation === 'CALLS').length
  const incoming = (map.incomingByNode.value.get(node.id) || []).filter((edge) => edge.relation === 'CALLS').length
  return `上游 ${incoming} 个，下游 ${outgoing} 个，追溯关系 ${map.linkCount(node.id)} 条`
})
const coverageRows = computed(() => {
  const edgeNodeIds = new Set<string>()
  map.edges.value.forEach((edge) => {
    if (edge.evidenceType === 'COVERAGE' || edge.evidenceType === 'EXECUTION_TRACE' || edge.callEvidence === 'DYNAMIC_CONFIRMED' || edge.callEvidence === 'STATIC_BRIDGED') {
      edgeNodeIds.add(edge.source)
      edgeNodeIds.add(edge.target)
    }
  })
  return map.nodes.value
    .filter((node) => node.kind.startsWith('CODE_'))
    .filter((node) => codeNodeInCurrentScope(node, map.nodes.value, map.focusId.value))
    .filter((node) => node.evidenceState === 'DYNAMIC' || node.evidenceState === 'BOTH' || edgeNodeIds.has(node.id))
    .filter((node) => !codeKeyword.value || searchableCodeNode(node).includes(codeKeyword.value))
    .map((node) => ({
      id: node.id,
      label: node.label || node.symbol || node.id,
      state: node.evidenceState,
      summary: coverageSummaryText(node),
      locator: node.locator || node.symbol || '-',
    }))
})
const relationSummaryRows = computed(() => {
  const req = map.nodes.value.find((node) => node.kind === 'REQUIREMENT')
  const tc = map.nodes.value.find((node) => node.kind === 'TESTCASE')
  const code = map.nodes.value.find((node) => node.kind.startsWith('CODE_'))
  const reqLabel = req?.label || 'REQ-001'
  const tcLabel = tc?.label || 'TC-001'
  const codeLabel = code?.label || 'LoginController'
  return [
    { key: 'req-tc', direction: '正向', fromTo: '需求 → 测试用例', arrow: '实线', meaning: '需求被哪些测试用例验证', example: `${reqLabel} → ${tcLabel}` },
    { key: 'tc-code', direction: '正向', fromTo: '测试用例 → 代码', arrow: '实线', meaning: '测试用例覆盖了哪些代码', example: `${tcLabel} → ${codeLabel}` },
    { key: 'req-code', direction: '正向', fromTo: '需求 → 代码', arrow: '实线', meaning: '需求由哪些代码直接实现', example: `${reqLabel} → ${codeLabel}` },
    { key: 'code-tc', direction: '反向', fromTo: '代码 → 测试用例', arrow: '虚线', meaning: '这段代码被哪些测试覆盖', example: `${codeLabel} ⇢ ${tcLabel}` },
    { key: 'tc-req', direction: '反向', fromTo: '测试用例 → 需求', arrow: '虚线', meaning: '测试用例验证了哪个需求', example: `${tcLabel} ⇢ ${reqLabel}` },
    { key: 'code-req', direction: '反向', fromTo: '代码 → 需求', arrow: '虚线', meaning: '代码实现了哪个需求', example: `${codeLabel} ⇢ ${reqLabel}` },
  ]
})

async function loadOverview() {
  const overview = await fetchVerificationOverview(projectId.value)
  baselines.value = overview.baselines
  selectedBaselineId.value = selectedBaselineId.value || overview.baselines.find((b) => ['WAITING_REVIEW', 'COMPLETED'].includes(b.status))?.id || overview.baselines[0]?.id || ''
}

async function reload() {
  try {
    await loadOverview()
  } catch (err) {
    map.error.value = err instanceof Error ? err.message : '加载分析基线失败'
    baselines.value = []
    selectedBaselineId.value = ''
    return
  }
  loadedCodeDataKey.value = ''
  loadingCodeDataKey.value = ''
  if (activeTab.value === 'calls') {
    await loadCodeData()
  } else {
    await loadTraceData()
  }
  if (map.activeBaselineId.value) {
    selectedBaselineId.value = map.activeBaselineId.value
  }
}

async function reloadBaseline() {
  map.focusId.value = ''
  selectedTraceId.value = ''
  selectedCallGraphId.value = ''
  loadedCodeDataKey.value = ''
  loadingCodeDataKey.value = ''
  if (activeTab.value === 'calls') {
    await loadCodeData()
    return
  }
  await loadTraceData()
}

async function loadTraceData() {
  map.includeAiCalls.value = false
  await map.load({ baselineId: selectedBaselineId.value, view: 'trace' })
}

async function loadCodeData(focusId = map.focusId.value) {
  const key = codeDataKey(focusId)
  if (loadedCodeDataKey.value === key || loadingCodeDataKey.value === key) return
  loadingCodeDataKey.value = key
  map.includeAiCalls.value = aiCallAnalysisEnabled.value
  try {
    await map.load({ baselineId: selectedBaselineId.value, focusId, view: 'calls' })
    loadedCodeDataKey.value = key
  } finally {
    if (loadingCodeDataKey.value === key) loadingCodeDataKey.value = ''
  }
}

async function ensureCodeDataLoaded() {
  const key = codeDataKey(map.focusId.value)
  if (!selectedBaselineId.value || loadedCodeDataKey.value === key || loadingCodeDataKey.value === key || map.loading.value) return
  await loadCodeData(map.focusId.value)
}

function codeDataKey(focusId = '') {
  return `${selectedBaselineId.value || map.activeBaselineId.value}|${focusId || ''}|ai:${aiCallAnalysisEnabled.value}`
}

async function toggleAiCallAnalysis() {
  aiCallAnalysisEnabled.value = true
  loadedCodeDataKey.value = ''
  loadingCodeDataKey.value = ''
  if (activeTab.value === 'calls') {
    await loadCodeData(map.focusId.value)
  }
}

function selectTraceNode(id: string) {
  selectedTraceId.value = selectedTraceId.value === id ? '' : id
}

function clearTraceGraphSelection() {
  selectedTraceId.value = ''
}

async function locateTraceCodeNode(id: string) {
  activeTab.value = 'calls'
  callViewMode.value = 'graph'
  selectedCallGraphId.value = id
  map.select(id)
  await loadCodeData(id)
  openAncestors(id, map.codeTree.value)
}

async function openTraceCodeContext(id: string) {
  callGraphScope.value = 'context'
  await locateTraceCodeNode(id)
}

function selectCallGraphNode(id: string) {
  const nextId = selectedCallGraphId.value === id ? '' : id
  selectedCallGraphId.value = nextId
  selectedMiniGraphId.value = ''
  miniGraphFocusHighlightDisabled.value = false
  map.select(nextId)
  if (nextId) openAncestors(nextId, map.codeTree.value)
}

function selectMiniGraphNode(node: MiniGraphNode) {
  selectedMiniGraphId.value = selectedMiniGraphId.value === node.id ? '' : node.id
  miniGraphFocusHighlightDisabled.value = !selectedMiniGraphId.value
  if (node.nodeId) {
    selectedCallGraphId.value = node.nodeId
    map.select(node.nodeId)
    openAncestors(node.nodeId, map.codeTree.value)
  }
}

function clearMiniGraphSelection() {
  selectedMiniGraphId.value = ''
  selectedCallGraphId.value = ''
  miniGraphFocusHighlightDisabled.value = true
}

function clearCallGraphSelection() {
  selectedCallGraphId.value = ''
}

async function selectCodeNode(id: string) {
  selectedCallGraphId.value = id
  selectedMiniGraphId.value = ''
  miniGraphFocusHighlightDisabled.value = false
  map.select(id)
  openAncestors(id, map.codeTree.value)
  if (selectedBaselineId.value) {
    await loadCodeData(id)
    openAncestors(id, map.codeTree.value)
  }
}

function toggleDir(path: string) {
  const next = new Set(openDirs.value)
  next.has(path) ? next.delete(path) : next.add(path)
  openDirs.value = next
}

function toggleClass(id: string) {
  const next = new Set(openClasses.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openClasses.value = next
}

function expandTree() {
  const dirs = new Set<string>()
  const classes = new Set<string>()
  treeNodes.value.forEach((node) => collectTreeOpenKeys(node, dirs, classes))
  openDirs.value = dirs
  openClasses.value = classes
}

function collapseTree() {
  openDirs.value = new Set()
  openClasses.value = new Set()
}

function collectTreeOpenKeys(node: TreeNode, dirs: Set<string>, classes: Set<string>) {
  if (node.isDir) {
    dirs.add(node.key)
    node.children.forEach((child) => collectTreeOpenKeys(child, dirs, classes))
    return
  }
  if (node.file) classes.add(node.file.id)
}

function openAncestors(id: string, nodes: CodeTreeNode[], parents: string[] = []) {
  for (const node of nodes) {
    if (node.id === id) {
      const dirs = new Set(openDirs.value)
      parents.forEach((parent) => dirs.add(parent))
      openDirs.value = dirs
      if (node.kind === 'CLASS' || node.kind === 'METHOD') {
        const classes = new Set(openClasses.value)
        classes.add(node.kind === 'CLASS' ? node.id : node.parentId || '')
        openClasses.value = classes
      }
      return true
    }
    const nextParents = node.kind === 'DIRECTORY' ? [...parents, node.id] : parents
    if (openAncestors(id, node.children || [], nextParents)) return true
  }
  return false
}

function toTreeNode(node: CodeTreeNode): TreeNode {
  if (node.kind === 'DIRECTORY') {
    return { key: node.id, name: node.label, displayName: node.label, isDir: true, children: node.children.map(toTreeNode) }
  }
  if (node.kind === 'FILE' || node.kind === 'CLASS') {
    const methodNodes = flattenMethods(node.children)
    return {
      key: node.id,
      name: node.label,
      displayName: node.label,
      isDir: false,
      children: [],
      file: { id: node.id, nodeId: node.id, name: node.label, methods: methodNodes },
    }
  }
  return { key: node.id, name: node.label, displayName: node.label, isDir: false, children: [] }
}

function filterCodeTreeNodes(nodes: CodeTreeNode[], keyword: string): CodeTreeNode[] {
  if (!keyword) return nodes
  return nodes
    .map((node) => {
      const children = filterCodeTreeNodes(node.children || [], keyword)
      if (children.length || searchableCodeTreeNode(node).includes(keyword)) {
        return { ...node, children }
      }
      return null
    })
    .filter((node): node is CodeTreeNode => Boolean(node))
}

function searchableCodeTreeNode(node: CodeTreeNode) {
  return normalizeSearch([node.id, node.label, node.path, node.parentId, node.language, node.kind].join(' '))
}

function flattenMethods(nodes: CodeTreeNode[]): Array<{ nodeId: string; name: string; line?: number }> {
  return nodes.flatMap((node) => {
    if (node.kind === 'METHOD') return [{ nodeId: node.id, name: node.label, line: lineFromLocator(node.path) }]
    return flattenMethods(node.children || [])
  })
}

function lineFromLocator(locator?: string) {
  const match = String(locator || '').match(/:(\d+)$/)
  return match ? Number(match[1]) : undefined
}

function relationCount(nodeId: string, relation: TraceRelation) {
  return map.filteredEdges.value.filter((edge) => edge.relation === relation && (edge.source === nodeId || edge.target === nodeId)).length
}

function coverageSummaryText(node: TraceabilityNode) {
  const coverage = node.coverage
  if (!coverage) return node.evidenceState === 'STATIC' || node.evidenceState === 'NONE' ? '无覆盖摘要' : '已匹配动态证据'
  const line = coverage.lineRate !== undefined ? `行覆盖 ${Math.round(coverage.lineRate * 100)}%` : ''
  const branch = coverage.branchRate !== undefined ? `分支覆盖 ${Math.round(coverage.branchRate * 100)}%` : ''
  return [line, branch].filter(Boolean).join(' · ') || '已匹配覆盖证据'
}

function buildTraceGraph(nodes: TraceabilityNode[], edges: TraceabilityEdge[], selectedNodeId: string) {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const traceEdges = collapseTraceEdges(edges, nodeMap)
  const edgeIds = new Set<string>()
  traceEdges.forEach((edge) => {
    edgeIds.add(edge.source)
    edgeIds.add(edge.target)
  })

  const requirements = pickLayerNodes(nodes, 'REQUIREMENT', edgeIds, 18)
  const testcases = pickLayerNodes(nodes, 'TESTCASE', edgeIds, 24)
  const code = pickLayerNodes(nodes.filter((node) => node.kind === 'CODE_FILE'), 'CODE_', edgeIds, 24)
  const rawLayers = [
    { key: 'requirements', title: layerTitle('需求层 (Requirements)', requirements), nodes: requirements.nodes, tone: 'req' },
    { key: 'testcases', title: layerTitle('测试用例层 (Test Cases)', testcases), nodes: testcases.nodes, tone: 'tc' },
    { key: 'code', title: layerTitle('代码层 (Source Code)', code), nodes: code.nodes, tone: 'code' },
  ]
  const width = 1180
  const nodeWidth = 154
  const nodeHeight = 54
  const gapX = 28
  const gapY = 18
  const maxColumns = 6
  const lanePaddingTop = 34
  const lanePaddingBottom = 24
  const laneGap = 28
  const positioned = new Map<string, SvgNode>()
  const lanes: Array<{ key: string; title: string; x: number; y: number; width: number; height: number }> = []
  let currentY = 28

  const layers = rawLayers.map((layer) => {
    const rows = Math.max(1, Math.ceil(layer.nodes.length / maxColumns))
    const height = lanePaddingTop + rows * nodeHeight + Math.max(0, rows - 1) * gapY + lanePaddingBottom
    const y = currentY
    currentY += height + laneGap
    return { ...layer, y, height, rows }
  })

  layers.forEach((layer) => {
    lanes.push({ key: layer.key, title: layer.title, x: 24, y: layer.y, width: width - 48, height: layer.height })
    layer.nodes.forEach((node, index) => {
      const row = Math.floor(index / maxColumns)
      const column = index % maxColumns
      const nodesInRow = Math.min(maxColumns, layer.nodes.length - row * maxColumns)
      const rowWidth = nodesInRow * nodeWidth + Math.max(0, nodesInRow - 1) * gapX
      const startX = (width - rowWidth) / 2
      positioned.set(node.id, {
        id: node.id,
        x: startX + column * (nodeWidth + gapX),
        y: layer.y + lanePaddingTop + row * (nodeHeight + gapY),
        width: nodeWidth,
        height: nodeHeight,
        tone: layer.tone,
        shortLabel: shorten(node.label || node.id, 18),
        shortDesc: shorten(node.description || node.symbol || '', 16),
        raw: node,
      })
    })
  })
  const height = Math.max(560, currentY + 4)
  const graphEdges = traceEdges
    .filter((edge) => positioned.has(edge.source) && positioned.has(edge.target))
    .slice(0, 96)
    .map((edge): SvgEdge => {
      const source = positioned.get(edge.source)!
      const target = positioned.get(edge.target)!
      const start = { x: source.x + source.width / 2, y: source.y + source.height }
      const end = { x: target.x + target.width / 2, y: target.y }
      const midY = (start.y + end.y) / 2
      return {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        path: `M ${start.x} ${start.y} C ${start.x} ${midY}, ${end.x} ${midY}, ${end.x} ${end.y}`,
        labelX: (start.x + end.x) / 2,
        labelY: midY - 4,
        label: relationLabel(edge.relation),
        labelWidth: edgeLabelWidth(relationLabel(edge.relation)),
        raw: edge,
      }
    })
  const relatedEdgeIds = traceGraphRelatedEdgeIds(selectedNodeId, graphEdges)
  const relatedNodeIds = traceGraphRelatedNodeIds(selectedNodeId, graphEdges)
  return {
    width,
    height,
    lanes,
    nodes: [...positioned.values()],
    edges: graphEdges,
    linkedIds: edgeIds,
    relatedNodeIds,
    relatedEdgeIds,
    hasSelection: Boolean(selectedNodeId && positioned.has(selectedNodeId)),
    selectedNodeId,
  }
}

function traceGraphRelatedNodeIds(focusId: string, edges: SvgEdge[]) {
  const ids = new Set<string>()
  if (!focusId) return ids
  ids.add(focusId)
  edges.forEach((edge) => {
    if (edge.source === focusId) ids.add(edge.target)
    if (edge.target === focusId) ids.add(edge.source)
  })
  return ids
}

function traceGraphRelatedEdgeIds(focusId: string, edges: SvgEdge[]) {
  const ids = new Set<string>()
  if (!focusId) return ids
  edges.forEach((edge) => {
    if (edge.source === focusId || edge.target === focusId) ids.add(edge.id)
  })
  return ids
}

function collapseTraceEdges(edges: TraceabilityEdge[], nodeMap: Map<string, TraceabilityNode>) {
  const collapsed = new Map<string, TraceabilityEdge>()
  edges.filter((edge) => edge.relation !== 'CALLS' && nodeMap.has(edge.source) && nodeMap.has(edge.target)).forEach((edge) => {
    const source = traceLayerNodeId(edge.source, nodeMap)
    const target = traceLayerNodeId(edge.target, nodeMap)
    if (!source || !target || source === target) return
    const key = `${source}|${target}|${edge.relation}`
    if (!collapsed.has(key)) {
      collapsed.set(key, { ...edge, id: `trace-collapsed:${key}`, source, target })
    }
  })
  return [...collapsed.values()]
}

function traceLayerNodeId(id: string, nodeMap: Map<string, TraceabilityNode>) {
  let node = nodeMap.get(id)
  const visited = new Set<string>()
  while (node && node.kind.startsWith('CODE_') && node.kind !== 'CODE_FILE' && node.parentId && !visited.has(node.id)) {
    visited.add(node.id)
    node = nodeMap.get(node.parentId)
  }
  return node?.kind === 'CODE_FILE' ? node.id : id
}

function pickLayerNodes(nodes: TraceabilityNode[], kindPrefix: string, edgeIds: Set<string>, limit: number) {
  const matches = nodes.filter((node) => node.kind === kindPrefix || node.kind.startsWith(kindPrefix))
  const linked = matches.filter((node) => edgeIds.has(node.id))
  return { nodes: linked.slice(0, limit), total: linked.length }
}

function layerTitle(title: string, layer: { nodes: TraceabilityNode[]; total: number }) {
  return layer.total > layer.nodes.length ? `${title} · ${layer.nodes.length}/${layer.total}` : title
}

function dependencyNodeResolver(nodes: TraceabilityNode[]) {
  const exact = new Map<string, string>()
  nodes
    .filter((node) => node.kind === 'CODE_CLASS' || node.kind === 'CODE_FILE')
    .forEach((node) => {
      ;[node.symbol, node.description, node.locator, node.label, node.id]
        .filter(Boolean)
        .forEach((value) => exact.set(normalizeSearch(String(value)), node.id))
    })
  return (label: string) => {
    const normalized = normalizeSearch(label)
    if (exact.has(normalized)) return exact.get(normalized)
    const simple = normalized.split(/[./#]/).filter(Boolean).pop() || normalized
    return nodes.find((node) =>
      (node.kind === 'CODE_CLASS' || node.kind === 'CODE_FILE') &&
      [node.symbol, node.description, node.locator, node.label, node.id]
        .filter(Boolean)
        .some((value) => {
          const text = normalizeSearch(String(value))
          return text === simple || text.endsWith(`.${simple}`) || normalized.endsWith(text)
        }),
    )?.id
  }
}

function buildDependencyGraph(dependencies: Array<{ source: string; target: string; kind: string }>, keyword: string, allNodes: TraceabilityNode[], focusId: string) {
  const unique = new Map<string, { source: string; target: string; kind: string }>()
  const dependencyScope = dependencyScopeLabels(allNodes, focusId)
  const dependencyNodeIds = dependencyNodeResolver(allNodes)
  dependencies
    .filter((item) => !dependencyScope.size || dependencyScope.has(normalizeSearch(item.source)) || [...dependencyScope].some((label) => normalizeSearch(item.source).endsWith(`.${label}`) || normalizeSearch(item.source).includes(label)))
    .filter((item) => !keyword || normalizeSearch([item.source, item.target, item.kind].join(' ')).includes(keyword))
    .forEach((item) => unique.set(`${item.source}|${item.target}`, item))
  const items = [...unique.values()].slice(0, 160)
  const sources = [...new Set(items.map((item) => item.source))]
  const targets = [...new Set(items.map((item) => item.target))]
  const graphNodes: MiniGraphNode[] = []
  const sourceWidth = 230
  const targetWidth = 300
  sources.forEach((label, index) => graphNodes.push({ id: `source:${label}`, x: 40, y: 40 + index * 76, width: sourceWidth, height: 42, label: shorten(label, 28), tone: 'source', nodeId: dependencyNodeIds(label) }))
  targets.forEach((label, index) => graphNodes.push({ id: `target:${label}`, x: 520, y: 40 + index * 58, width: targetWidth, height: 42, label: shorten(label, 38), tone: 'target', nodeId: dependencyNodeIds(label) }))
  const nodeMap = new Map(graphNodes.map((node) => [node.id, node]))
  const edges: MiniGraphEdge[] = items.map((item, index) => {
    const source = nodeMap.get(`source:${item.source}`)!
    const target = nodeMap.get(`target:${item.target}`)!
    const startX = source.x + source.width
    const startY = source.y + source.height / 2
    const endX = target.x
    const endY = target.y + target.height / 2
    const midX = (startX + endX) / 2
    return { id: `dependency:${index}`, source: source.id, target: target.id, path: `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}` }
  })
  return { width: 860, height: Math.max(420, Math.max(sources.length * 76, targets.length * 58) + 80), nodes: graphNodes, edges }
}

function buildControlFlowGraph(steps: Array<{ methodId: string; methodLabel: string; kind: string; expression: string; order: number }>, focusId: string, keyword: string, allNodes: TraceabilityNode[]) {
  const scopedMethodIds = codeMethodScopeIds(allNodes, focusId)
  const selected = focusId ? steps.filter((step) => step.methodId === focusId) : steps
  const source = scopedMethodIds.size ? steps.filter((step) => scopedMethodIds.has(step.methodId)) : keyword ? steps : selected.length ? selected : steps
  const visible = source
    .filter((step) => !keyword || normalizeSearch([step.methodId, step.methodLabel, step.kind, step.expression].join(' ')).includes(keyword))
    .slice(0, 80)
  const graphNodes: MiniGraphNode[] = visible.map((step, index) => ({
    id: `${step.methodId}:${step.order}`,
    x: 100 + (index % 2) * 390,
    y: 42 + Math.floor(index / 2) * 78,
    width: 320,
    height: 50,
    label: `${step.kind} · ${shorten(step.methodLabel, 22)}`,
    subtitle: shorten(step.expression || '代码块', 38),
    tone: step.kind === 'IF' || step.kind === 'ELSE IF' ? 'branch' : step.kind === 'RETURN' || step.kind === 'THROW' ? 'exit' : 'flow',
    nodeId: step.methodId,
  }))
  const edges: MiniGraphEdge[] = []
  for (let index = 1; index < graphNodes.length; index++) {
    const source = graphNodes[index - 1]
    const target = graphNodes[index]
    edges.push({ id: `control:${index}`, source: source.id, target: target.id, path: `M ${source.x + source.width / 2} ${source.y + source.height} C ${source.x + source.width / 2} ${source.y + source.height + 24}, ${target.x + target.width / 2} ${target.y - 24}, ${target.x + target.width / 2} ${target.y}` })
  }
  return { width: 860, height: Math.max(420, Math.ceil(graphNodes.length / 2) * 78 + 70), nodes: graphNodes, edges }
}

function buildMiniGraphHighlight(graph: { nodes: MiniGraphNode[]; edges: MiniGraphEdge[] } | null, selectedId: string, focusId: string) {
  const activeNodeId = selectedId || graph?.nodes.find((node) => node.nodeId && node.nodeId === focusId)?.id || ''
  const relatedNodeIds = new Set<string>()
  const relatedEdgeIds = new Set<string>()
  if (!graph || !activeNodeId) {
    return { activeNodeId, relatedNodeIds, relatedEdgeIds, hasSelection: false }
  }
  relatedNodeIds.add(activeNodeId)
  graph.edges.forEach((edge) => {
    if (edge.source !== activeNodeId && edge.target !== activeNodeId) return
    relatedEdgeIds.add(edge.id)
    relatedNodeIds.add(edge.source)
    relatedNodeIds.add(edge.target)
  })
  return { activeNodeId, relatedNodeIds, relatedEdgeIds, hasSelection: true }
}

function buildCallGraph(nodes: TraceabilityNode[], edges: TraceabilityEdge[], controlFlows: Array<{ methodId: string; methodLabel: string; kind: string; expression: string; order: number }>, focusId: string, selectedNodeId: string, scope: 'overview' | 'impact' | 'context', keyword: string) {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const selectedNode = focusId ? nodeMap.get(focusId) : null
  const selectedIsCode = Boolean(selectedNode?.kind.startsWith('CODE_'))
  const graphScopeIds = selectedIsCode ? collectCallScopeIds(nodes, edges, focusId, scope) : new Set<string>()
  const strictScope = selectedIsCode && (scope !== 'overview' || selectedNode?.kind === 'CODE_FILE' || selectedNode?.kind === 'CODE_CLASS')
  const matchedNodeIds = keyword
    ? new Set(nodes.filter((node) => node.kind.startsWith('CODE_') && searchableCodeNode(node).includes(keyword)).map((node) => node.id))
    : new Set<string>()
  const callEdges = edges
    .filter((edge) => edge.relation === 'CALLS' && nodeMap.has(edge.source) && nodeMap.has(edge.target))
    .filter((edge) => {
      if (!selectedIsCode) return true
      if (scope === 'overview' && (selectedNode?.kind === 'CODE_FILE' || selectedNode?.kind === 'CODE_CLASS')) {
        return graphScopeIds.has(edge.source) && graphScopeIds.has(edge.target)
      }
      return true
    })
    .filter((edge) => !strictScope || graphScopeIds.has(edge.source) || graphScopeIds.has(edge.target))
    .filter((edge) => !keyword || matchedNodeIds.has(edge.source) || matchedNodeIds.has(edge.target) || searchableCallEdge(edge).includes(keyword))
    .sort((left, right) => callEdgePriority(left, graphScopeIds) - callEdgePriority(right, graphScopeIds))
    .slice(0, 64)
  const inferredExpressionEdges = inferControlFlowCallEdges(nodes, callEdges, controlFlows, selectedIsCode ? graphScopeIds : new Set<string>(), keyword)
  const visibleCallEdges = [...callEdges, ...inferredExpressionEdges]
  const ids = new Set<string>()
  if (selectedIsCode) {
    nodes
      .filter((node) => graphScopeIds.has(node.id) && node.kind.startsWith('CODE_'))
      .sort((left, right) => codeNodePriority(left, focusId) - codeNodePriority(right, focusId))
      .slice(0, 28)
      .forEach((node) => ids.add(node.id))
  }
  visibleCallEdges.forEach((edge) => {
    ids.add(edge.source)
    ids.add(edge.target)
  })
  if (selectedIsCode) ids.add(focusId)
  const graphNodes = [...ids].map((id) => nodeMap.get(id)).filter((node): node is TraceabilityNode => Boolean(node)).slice(0, 56)
  if (!graphNodes.length) {
    const fallbackNodes = selectedIsCode
      ? nodes
        .filter((node) => graphScopeIds.has(node.id) && (node.kind === 'CODE_METHOD' || node.kind === 'CODE_CLASS'))
        .filter((node) => !keyword || searchableCodeNode(node).includes(keyword))
        .slice(0, 24)
      : nodes
        .filter((node) => node.kind === 'CODE_METHOD' || node.kind === 'CODE_CLASS')
        .filter((node) => !keyword || searchableCodeNode(node).includes(keyword))
        .slice(0, 24)
    graphNodes.push(...fallbackNodes)
  }
  const structuralEdges = graphNodes
    .filter((node) => node.parentId && nodeMap.has(node.parentId))
    .filter((node) => node.kind === 'CODE_METHOD' || node.kind === 'CODE_CLASS')
    .map((node) => structuralCallEdge(nodeMap.get(node.parentId!)!, node))
  const layoutEdges = [...visibleCallEdges, ...structuralEdges]
  const nodeWidth = 184
  const nodeHeight = 58
  const levels = callGraphLevels(graphNodes, layoutEdges)
  const columns = Math.max(1, Math.min(6, Math.max(...levels.values(), 0) + 1))
  const rowsByLevel = new Map<number, number>()
  levels.forEach((level) => rowsByLevel.set(level, (rowsByLevel.get(level) || 0) + 1))
  const maxRows = Math.max(1, ...rowsByLevel.values())
  const columnGap = 280
  const rowGap = 138
  const width = Math.max(860, columns * columnGap + 80)
  const height = Math.max(360, maxRows * rowGap + 120)
  const positioned = new Map<string, SvgNode>()
  const usedRows = new Map<number, number>()
  graphNodes.forEach((node) => {
    const column = Math.min(columns - 1, levels.get(node.id) || 0)
    const row = usedRows.get(column) || 0
    usedRows.set(column, row + 1)
    positioned.set(node.id, {
      id: node.id,
      x: 40 + column * columnGap,
      y: 76 + row * rowGap,
      width: nodeWidth,
      height: nodeHeight,
      tone: 'code',
      shortLabel: shorten(node.label || node.id, 18),
      shortDesc: shorten(node.symbol || node.locator || nodeKindLabel(node.kind), 28),
      raw: node,
    })
  })
  const graphEdges = layoutEdges
    .filter((edge) => positioned.has(edge.source) && positioned.has(edge.target))
    .map((edge): SvgEdge => {
      const source = positioned.get(edge.source)!
      const target = positioned.get(edge.target)!
      if (edge.source === edge.target) {
        const x = source.x + source.width / 2
        const topY = source.y - 32
        const label = '递归调用'
        return {
          id: edge.id,
          source: edge.source,
          target: edge.target,
          path: `M ${source.x + source.width - 8} ${source.y + 18} C ${source.x + source.width + 86} ${topY}, ${source.x - 86} ${topY}, ${source.x + 8} ${source.y + 18}`,
          labelX: x,
          labelY: topY - 10,
          label,
          labelWidth: edgeLabelWidth(label),
          raw: edge,
        }
      }
      const forward = target.x >= source.x
      const start = { x: source.x + (forward ? source.width : 0), y: source.y + source.height / 2 }
      const end = { x: target.x + (forward ? 0 : target.width), y: target.y + target.height / 2 }
      const midX = (start.x + end.x) / 2
      const label = callEvidenceLabel(edge, nodeMap.get(edge.target))
      return {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        path: `M ${start.x} ${start.y} C ${midX} ${start.y}, ${midX} ${end.y}, ${end.x} ${end.y}`,
        labelX: midX,
        labelY: (start.y + end.y) / 2 - 16,
        label,
        labelWidth: edgeLabelWidth(label),
        raw: edge,
      }
    })
  return {
    width,
    height,
    nodes: [...positioned.values()],
    edges: graphEdges,
    relatedNodeIds: callGraphRelatedNodeIds(selectedNodeId, graphEdges),
    relatedEdgeIds: callGraphRelatedEdgeIds(selectedNodeId, graphEdges),
    hasSelection: Boolean(selectedNodeId && positioned.has(selectedNodeId)),
    selectedNodeId,
  }
}

function callGraphRelatedNodeIds(focusId: string, edges: SvgEdge[]) {
  const ids = new Set<string>()
  if (!focusId) return ids
  ids.add(focusId)
  edges.forEach((edge) => {
    if (edge.source === focusId) ids.add(edge.target)
    if (edge.target === focusId) ids.add(edge.source)
  })
  return ids
}

function callGraphRelatedEdgeIds(focusId: string, edges: SvgEdge[]) {
  if (!focusId) return new Set<string>()
  return new Set(edges
    .filter((edge) => edge.source === focusId || edge.target === focusId)
    .map((edge) => edge.id))
}

function inferControlFlowCallEdges(nodes: TraceabilityNode[], existingEdges: TraceabilityEdge[], controlFlows: Array<{ methodId: string; methodLabel: string; kind: string; expression: string; order: number }>, scopedIds: Set<string>, keyword: string) {
  if (!controlFlows.length) return []
  const existing = new Set(existingEdges.map((edge) => `${edge.source}->${edge.target}`))
  const methods = nodes.filter((node) => node.kind === 'CODE_METHOD')
  const methodsByName = new Map<string, TraceabilityNode[]>()
  methods.forEach((node) => {
    const name = methodSimpleName(node)
    if (name) methodsByName.set(name, [...(methodsByName.get(name) || []), node])
  })
  const result: TraceabilityEdge[] = []
  const flows = controlFlows.filter((step) => !scopedIds.size || scopedIds.has(step.methodId))
  for (const step of flows) {
    const caller = methods.find((node) => node.id === step.methodId)
    if (!caller || !step.expression) continue
    for (const [name, targets] of methodsByName.entries()) {
      if (!containsMethodCallExpression(step.expression, name)) continue
      for (const target of targets) {
        if (target.parentId !== caller.parentId && !sameCodeFile(nodes, caller, target)) continue
        const key = `${caller.id}->${target.id}`
        if (existing.has(key)) continue
        if (keyword && !normalizeSearch([caller.label, target.label, caller.symbol, target.symbol, step.expression].join(' ')).includes(keyword)) continue
        existing.add(key)
        result.push({
          id: `call:expr:${stableClientId(key + step.order)}`,
          source: caller.id,
          target: target.id,
          relation: 'CALLS',
          direction: 'FORWARD',
          evidenceType: 'DERIVED',
          callEvidence: 'STATIC_BRIDGED',
          confidence: 0.58,
          evidenceLevel: 'E2',
          generationMethod: 'SOURCE_EXPRESSION',
          reviewStatus: 'PENDING',
          evidence: [{ reason: `源码表达式命中 ${name}()`, locator: caller.locator, line: lineFromLocator(caller.locator), metadata: { expression: step.expression } }],
        })
      }
    }
  }
  return result.slice(0, 80)
}

function containsMethodCallExpression(expression: string, methodName: string) {
  return new RegExp(`(^|[^\\w$])${escapeRegExp(methodName)}\\s*\\(`).test(expression)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function methodSimpleName(node: TraceabilityNode) {
  const symbol = node.symbol || node.label || ''
  const afterHash = symbol.includes('#') ? symbol.slice(symbol.lastIndexOf('#') + 1) : symbol
  return (afterHash.match(/[A-Za-z_$][\w$]*/) || [node.label || ''])[0]
}

function sameCodeFile(nodes: TraceabilityNode[], left: TraceabilityNode, right: TraceabilityNode) {
  return nearestCodeAncestor(nodes, left, 'CODE_FILE')?.id === nearestCodeAncestor(nodes, right, 'CODE_FILE')?.id
}

function stableClientId(value: string) {
  let hash = 0
  for (let i = 0; i < value.length; i++) hash = Math.imul(31, hash) + value.charCodeAt(i) | 0
  return Math.abs(hash).toString(36)
}

function structuralCallEdge(parent: TraceabilityNode, child: TraceabilityNode): TraceabilityEdge {
  return {
    id: `contains:${parent.id}->${child.id}`,
    source: parent.id,
    target: child.id,
    relation: 'CALLS',
    direction: 'FORWARD',
    evidenceType: 'DERIVED',
    confidence: 1,
    evidenceLevel: 'E1',
    generationMethod: 'CODE_STRUCTURE',
    reviewStatus: 'CONFIRMED',
    evidence: [{ reason: child.kind === 'CODE_METHOD' ? '类包含该方法' : '文件包含该类' }],
  }
}

function callEdgePriority(edge: TraceabilityEdge, scopedIds: Set<string>) {
  let score = 0
  if (edge.source === edge.target) score += 80
  if (scopedIds.size && !scopedIds.has(edge.source) && !scopedIds.has(edge.target)) score += 60
  if (scopedIds.has(edge.source) && !scopedIds.has(edge.target)) score += 5
  if (!scopedIds.has(edge.source) && scopedIds.has(edge.target)) score += 12
  if (edge.callEvidence === 'DYNAMIC_CONFIRMED') score -= 20
  if (edge.callEvidence === 'STATIC_BRIDGED') score -= 10
  return score
}

function codeNodePriority(node: TraceabilityNode, focusId: string) {
  if (node.id === focusId) return 0
  if (node.kind === 'CODE_CLASS') return 1
  if (node.kind === 'CODE_METHOD') return 2
  if (node.kind === 'CODE_FILE') return 3
  return 4
}

function edgeLabelWidth(label: string) {
  return Math.max(58, label.length * 13 + 16)
}

function callGraphLevels(graphNodes: TraceabilityNode[], edges: TraceabilityEdge[]) {
  const ids = new Set(graphNodes.map((node) => node.id))
  const incoming = new Map<string, number>()
  const outgoing = new Map<string, string[]>()
  ids.forEach((id) => incoming.set(id, 0))
  edges.forEach((edge) => {
    if (!ids.has(edge.source) || !ids.has(edge.target)) return
    incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1)
    outgoing.set(edge.source, [...(outgoing.get(edge.source) || []), edge.target])
  })
  const levels = new Map<string, number>()
  const queue = [...ids].filter((id) => (incoming.get(id) || 0) === 0)
  queue.forEach((id) => levels.set(id, 0))
  while (queue.length) {
    const current = queue.shift()!
    const nextLevel = (levels.get(current) || 0) + 1
    for (const next of outgoing.get(current) || []) {
      levels.set(next, Math.max(levels.get(next) ?? 0, nextLevel))
      incoming.set(next, (incoming.get(next) || 1) - 1)
      if (incoming.get(next) === 0) queue.push(next)
    }
  }
  // Cycles have no root. Keep them visible in a final level instead of dropping them.
  const lastLevel = Math.max(...levels.values(), 0)
  ids.forEach((id) => {
    if (!levels.has(id)) levels.set(id, lastLevel)
  })
  return levels
}

function collectCallScopeIds(nodes: TraceabilityNode[], edges: TraceabilityEdge[], rootId: string, scope: 'overview' | 'impact' | 'context') {
  const ids = new Set<string>([rootId])
  const root = nodes.find((node) => node.id === rootId)
  if (root?.kind === 'CODE_FILE' || root?.kind === 'CODE_CLASS') {
    collectCodeDescendantIds(nodes, rootId, ids)
  }
  if (scope === 'overview' && (root?.kind === 'CODE_FILE' || root?.kind === 'CODE_CLASS')) return ids
  const callEdges = edges.filter((edge) => edge.relation === 'CALLS')
  const rounds = scope === 'impact' ? 3 : 1
  for (let round = 0; round < rounds; round++) {
    const snapshot = new Set(ids)
    callEdges.forEach((edge) => {
      if (scope === 'impact') {
        if (snapshot.has(edge.source)) ids.add(edge.target)
      } else {
        if (snapshot.has(edge.source)) ids.add(edge.target)
        if (snapshot.has(edge.target)) ids.add(edge.source)
      }
    })
  }
  return ids
}

function codeNodeInCurrentScope(node: TraceabilityNode, nodes: TraceabilityNode[], focusId: string) {
  if (!focusId) return true
  const scope = new Set<string>([focusId])
  collectCodeDescendantIds(nodes, focusId, scope)
  let current = nodes.find((item) => item.id === focusId)
  while (current?.parentId) {
    scope.add(current.parentId)
    current = nodes.find((item) => item.id === current?.parentId)
  }
  return scope.has(node.id)
}

function codeMethodScopeIds(nodes: TraceabilityNode[], focusId: string) {
  const result = new Set<string>()
  if (!focusId) return result
  const focus = nodes.find((node) => node.id === focusId)
  if (!focus?.kind.startsWith('CODE_')) return result
  if (focus.kind === 'CODE_METHOD') {
    result.add(focus.id)
    return result
  }
  const scope = new Set<string>([focusId])
  collectCodeDescendantIds(nodes, focusId, scope)
  nodes.forEach((node) => {
    if (node.kind === 'CODE_METHOD' && scope.has(node.id)) result.add(node.id)
  })
  return result
}

function dependencyScopeLabels(nodes: TraceabilityNode[], focusId: string) {
  const result = new Set<string>()
  if (!focusId) return result
  const focus = nodes.find((node) => node.id === focusId)
  if (!focus?.kind.startsWith('CODE_')) return result
  const classNode = focus.kind === 'CODE_CLASS'
    ? focus
    : nearestCodeAncestor(nodes, focus, 'CODE_CLASS')
  const fileNode = focus.kind === 'CODE_FILE'
    ? focus
    : nearestCodeAncestor(nodes, focus, 'CODE_FILE')
  const scope = new Set<string>()
  if (classNode) scope.add(classNode.id)
  if (fileNode) {
    scope.add(fileNode.id)
    collectCodeDescendantIds(nodes, fileNode.id, scope)
  }
  if (!classNode && !fileNode) scope.add(focus.id)
  nodes.forEach((node) => {
    if (!scope.has(node.id) || node.kind !== 'CODE_CLASS') return
    const labels = [node.label, node.symbol, node.description]
    labels.forEach((label) => {
      const normalized = normalizeSearch(label)
      if (!normalized) return
      result.add(normalized)
      result.add(normalized.split('.').pop() || normalized)
    })
  })
  return result
}

function nearestCodeAncestor(nodes: TraceabilityNode[], node: TraceabilityNode, kind: string) {
  let current: TraceabilityNode | undefined = node
  while (current?.parentId) {
    current = nodes.find((item) => item.id === current?.parentId)
    if (current?.kind === kind) return current
  }
  return undefined
}

function collectCodeDescendantIds(nodes: TraceabilityNode[], rootId: string, ids: Set<string>) {
  let changed = true
  while (changed) {
    changed = false
    nodes.forEach((node) => {
      if (node.parentId && ids.has(node.parentId) && !ids.has(node.id)) {
        ids.add(node.id)
        changed = true
      }
    })
  }
}

function nodeTone(node?: TraceabilityNode | null) {
  if (node?.kind === 'REQUIREMENT') return 'tone-req'
  if (node?.kind === 'TESTCASE') return 'tone-tc'
  if (node?.kind?.startsWith('CODE_')) return 'tone-code'
  return 'tone-default'
}

function nodeKindLabel(kind?: string) {
  const labels: Record<string, string> = {
    REQUIREMENT: '需求',
    TESTCASE: '测试用例',
    CODE_FILE: '代码文件',
    CODE_CLASS: '代码类',
    CODE_METHOD: '代码方法',
  }
  return labels[kind || ''] || '节点'
}

function relationLabel(relation: TraceRelation) {
  return ({ VERIFIED_BY: '验证', COVERS: '覆盖', IMPLEMENTED_BY: '实现', CALLS: '调用' } as Record<TraceRelation, string>)[relation]
}

function traceNodeTitle(node: TraceabilityNode) {
  return [
    `${nodeKindLabel(node.kind)}：${node.label || node.id}`,
    node.symbol ? `符号：${node.symbol}` : '',
    node.locator ? `位置：${node.locator}` : '',
    node.description ? `说明：${node.description}` : '',
    node.language ? `语言：${node.language}` : '',
    node.metadata?.staticMethod === true ? '方法类型：静态方法' : '',
    node.metadata?.visibility ? `可见性：${node.metadata.visibility}` : '',
    node.metadata?.recursive === true ? '递归：是' : '',
    `ID：${node.id}`,
  ].filter(Boolean).join('\n')
}

function traceEdgeTitle(edge: TraceabilityEdge) {
  const source = map.nodeById.value.get(edge.source)
  const target = map.nodeById.value.get(edge.target)
  return [
    `${source?.label || edge.source} → ${target?.label || edge.target}`,
    `关系：${relationLabel(edge.relation)}`,
    edge.evidenceLevel ? `证据等级：${edge.evidenceLevel}` : '',
    edge.reviewStatus ? `状态：${edge.reviewStatus}` : '',
    edge.confidence !== undefined ? `置信度：${Math.round(edge.confidence * 100)}%` : '',
    edgeEvidenceText(edge) ? `依据：${edgeEvidenceText(edge)}` : '',
  ].filter(Boolean).join('\n')
}

function callEdgeTitle(edge: TraceabilityEdge) {
  const source = map.nodeById.value.get(edge.source)
  const target = map.nodeById.value.get(edge.target)
  return [
    `${source?.label || edge.source} → ${target?.label || edge.target}`,
    `调用类型：${callEvidenceLabel(edge, target)}`,
    edgeCallKind(edge, target) === 'STATIC' ? '方法调用：静态调用' : '方法调用：普通调用',
    edge.generationMethod ? `生成方式：${edge.generationMethod}` : '',
    edge.evidenceLevel ? `证据等级：${edge.evidenceLevel}` : '',
    edgeEvidenceText(edge) ? `依据：${edgeEvidenceText(edge)}` : '',
  ].filter(Boolean).join('\n')
}

function miniNodeTitle(node: MiniGraphNode) {
  const source = node.nodeId ? map.nodeById.value.get(node.nodeId) : undefined
  return [
    node.label,
    node.subtitle,
    source?.locator ? `位置：${source.locator}` : '',
    source?.symbol ? `符号：${source.symbol}` : '',
  ].filter(Boolean).join('\n')
}

function evidenceTone(edge: TraceabilityEdge) {
  if (edge.callEvidence === 'DYNAMIC_CONFIRMED') return 'dynamic'
  if (edge.callEvidence === 'STATIC_BRIDGED') return 'bridged'
  if (edge.evidenceType === 'DERIVED') return 'derived'
  return 'static'
}

function searchableCodeNode(node: TraceabilityNode) {
  return normalizeSearch([
    node.id,
    node.kind,
    node.label,
    node.description,
    node.locator,
    node.layer,
    node.language,
    node.symbol,
    node.parentId,
    node.evidenceState,
    JSON.stringify(node.metadata || {}),
  ].join(' '))
}

function searchableCallEdge(edge: TraceabilityEdge) {
  return normalizeSearch([
    edge.id,
    edge.source,
    edge.target,
    edge.relation,
    edge.evidenceType,
    edge.callEvidence,
    edge.generationMethod,
    edge.reviewStatus,
    JSON.stringify(edge.evidence || []),
  ].join(' '))
}

function edgeEvidenceText(edge: TraceabilityEdge) {
  const evidence = edge.evidence?.[0]
  const base = edge.callEvidence || edge.evidenceType
  return [base, edge.generationMethod, evidence?.traceId, evidence?.caseName, evidence?.reason].filter(Boolean).join(' · ')
}

function callEvidenceLabel(edge: TraceabilityEdge, targetNode?: TraceabilityNode) {
  if (edge.generationMethod === 'CODE_STRUCTURE') return '包含方法'
  if (edge.generationMethod === 'SOURCE_EXPRESSION') return '源码候选'
  if (edge.callEvidence === 'DYNAMIC_CONFIRMED') return '动态确认'
  if (edge.callEvidence === 'STATIC_BRIDGED') return '静态补全'
  return edgeCallKind(edge, targetNode) === 'STATIC' ? '静态调用' : '普通调用'
}

function edgeCallKind(edge: TraceabilityEdge, targetNode?: TraceabilityNode) {
  const metadataKind = edge.evidence
    ?.map((item) => String(item.metadata?.callKind || '').toUpperCase())
    .find(Boolean)
  if (metadataKind === 'STATIC' || metadataKind === 'NORMAL') return metadataKind
  return targetNode?.metadata?.staticMethod === true ? 'STATIC' : 'NORMAL'
}

function callNodeTone(node: TraceabilityNode) {
  if (node.kind === 'CODE_CLASS' && /controller/i.test(node.label || node.symbol || '')) return 'controller'
  if (node.metadata?.visibility === 'PRIVATE') return 'private'
  if (node.metadata?.staticMethod === true) return 'static'
  if (node.metadata?.recursive === true) return 'recursive'
  return 'method'
}

function downloadCallGraph() {
  const svg = document.querySelector('.call-map-svg')
  if (!svg) return
  const source = new XMLSerializer().serializeToString(svg)
  const blob = new Blob([source], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'code-call-graph.svg'
  link.click()
  URL.revokeObjectURL(url)
}

function toggleCallGraphFullscreen() {
  callGraphFullscreen.value ? exitCallGraphFullscreen() : enterCallGraphFullscreen()
}

function enterCallGraphFullscreen() {
  callGraphFullscreen.value = true
  document.body.classList.add('trace-call-graph-fullscreen')
}

function exitCallGraphFullscreen() {
  callGraphFullscreen.value = false
  document.body.classList.remove('trace-call-graph-fullscreen')
}

function handleCallGraphKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
    searchInput.value?.select()
    return
  }
  if (event.key === 'Escape' && callGraphFullscreen.value) {
    exitCallGraphFullscreen()
  }
}

function shorten(value: string, max: number) {
  const normalized = String(value || '').trim()
  return normalized.length > max ? `${normalized.slice(0, max - 1)}…` : normalized
}

function normalizeSearch(value?: string) {
  return String(value || '').trim().toLowerCase()
}

function percent(value: number) {
  return `${Math.round((value || 0) * 100)}%`
}

onMounted(() => {
  window.addEventListener('keydown', handleCallGraphKeydown)
  reload()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleCallGraphKeydown)
  if (slowLoadingTimer !== undefined) window.clearTimeout(slowLoadingTimer)
  exitCallGraphFullscreen()
})
</script>

<style scoped>
.trace-workspace { min-width: 0; }
.workspace-toolbar { display:flex; justify-content:space-between; gap:16px; align-items:center; margin-bottom:14px; padding:15px 17px; border:1px solid rgba(15,23,42,.08); border-radius:14px; background:rgba(255,255,255,.96); }
.workspace-toolbar > div:first-child { display:grid; gap:4px; }
.workspace-toolbar > div:first-child > strong { color:#172033; font-size:19px; }
.workspace-toolbar span { color:#64748b; font-size:13px; }
.eyebrow { color:#0f766e !important; font-size:11px !important; font-weight:900; text-transform:uppercase; }
.toolbar-actions { display:flex; gap:8px; flex-wrap:wrap; justify-content:flex-end; }
.toolbar-actions button { border:0; border-radius:9px; padding:9px 13px; background:#eef2f5; font-weight:800; cursor:pointer; }
.toolbar-actions button:hover:not(:disabled) { background:#0f766e; color:#fff; }
.toolbar-actions button:disabled { opacity:.5; cursor:not-allowed; }
.baseline-select { display:flex; align-items:center; gap:7px; padding:5px 10px; border:1px solid #e2e8f0; border-radius:9px; font-weight:800; }
.baseline-select select { max-width:260px; border:0; background:transparent; outline:0; }
.workspace-notice { margin-bottom:10px; padding:11px 14px; border-radius:10px; background:#f0fdfa; color:#0f766e; font-weight:700; }
.workspace-notice.error { background:#fef2f2; color:#b91c1c; }
.workspace-notice.warning { background:#fffbeb; color:#92400e; }
.summary-strip { display:grid; grid-template-columns:repeat(6,minmax(0,1fr)); gap:8px; margin-bottom:12px; }
.summary-strip div { display:grid; gap:2px; padding:10px 12px; border:1px solid #e2e8f0; border-radius:10px; background:#fff; }
.summary-strip strong { color:#172033; font-size:18px; }
.summary-strip span { color:#64748b; font-size:12px; font-weight:700; }
.trace-controls { display:flex; gap:10px; align-items:center; margin-bottom:12px; }
.search-box { flex:1; }
.search-box input { width:100%; box-sizing:border-box; border:1px solid #e2e8f0; border-radius:10px; padding:9px 11px; outline:0; background:#fff; }
.filter-tabs { display:flex; flex-wrap:wrap; gap:6px; }
.filter-tabs button { border:1px solid #e2e8f0; border-radius:9px; padding:8px 10px; background:#fff; color:#475569; font-weight:800; cursor:pointer; }
.filter-tabs button.active { border-color:#0f766e; background:#f0fdfa; color:#0f766e; }
.workspace-tabs { display:flex; gap:8px; margin-bottom:12px; border-bottom:1px solid #e2e8f0; }
.workspace-tabs button { border:0; border-bottom:3px solid transparent; padding:11px 16px; background:transparent; color:#475569; font-size:14px; font-weight:900; cursor:pointer; }
.workspace-tabs button.active { border-color:#0f766e; color:#0f766e; }
.tab-page { min-height:calc(100vh - 340px); }
.trace-tab { display:flex; flex-direction:column; gap:14px; min-width:0; }
.calls-tab { display:grid; grid-template-columns:360px minmax(620px,1fr); grid-template-areas:'tree graph'; gap:14px; min-height:calc(100vh - 340px); }
.call-graph-pane, .code-side-pane, .map-pane, .reference-panel { overflow:hidden; border:1px solid rgba(15,23,42,.08); border-radius:14px; background:rgba(255,255,255,.96); box-shadow:0 14px 36px rgba(15,23,42,.05); }
.trace-map-pane { display:flex; flex-direction:column; min-height:620px; min-width:0; }
.call-graph-pane, .code-side-pane { display:flex; flex-direction:column; min-width:0; }
.call-graph-pane { grid-area:graph; }
.code-side-pane { grid-area:tree; }
.trace-reference-grid { display:grid; grid-template-columns:minmax(320px,.8fr) minmax(560px,1.4fr); gap:14px; align-items:start; }
.reference-panel { padding:16px; }
.reference-panel h2 { margin:0 0 12px; color:#172033; font-size:18px; }
.reference-panel table { width:100%; border-collapse:collapse; font-size:13px; }
.reference-panel th, .reference-panel td { border-bottom:1px solid #e5e7eb; padding:10px 12px; text-align:left; vertical-align:middle; }
.reference-panel th { color:#172033; font-weight:900; background:#f8fafc; }
.reference-panel td { color:#334155; }
.relation-summary { overflow:auto; }
.node-detail-card { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:8px 14px; align-items:start; padding:13px 15px; border:1px solid; border-radius:12px; }
.node-detail-card > div { display:grid; gap:3px; min-width:0; }
.node-detail-card span, .code-node-detail span { font-size:10px; font-weight:900; letter-spacing:.04em; text-transform:uppercase; opacity:.76; }
.node-detail-card strong, .code-node-detail strong { color:#172033; overflow-wrap:anywhere; }
.node-detail-card small, .code-node-detail small { color:#64748b; overflow-wrap:anywhere; }
.node-detail-card p { grid-column:1 / -1; margin:0; color:#475569; font-size:12px; line-height:1.5; }
.node-detail-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:6px; }
.node-detail-actions button { border:0; border-radius:8px; padding:6px 10px; background:rgba(15,23,42,.08); color:#334155; font-size:12px; font-weight:900; cursor:pointer; }
.node-detail-actions button:hover { background:#0f766e; color:#fff; }
.line-cell { display:inline-block; width:36px; margin-right:8px; vertical-align:middle; border-top:2px solid #475569; }
.line-cell.dashed { border-top-style:dashed; }
.dot { display:inline-block; width:16px; height:16px; margin-right:8px; border-radius:999px; vertical-align:middle; }
.dot.blue { background:linear-gradient(#60a5fa,#2563eb); }
.dot.orange { background:linear-gradient(#fdba74,#f97316); }
.dot.green { background:linear-gradient(#4ade80,#16a34a); }
.map-pane { display:flex; flex-direction:column; min-width:0; }
.pane-head { display:flex; justify-content:space-between; gap:8px; padding:14px 15px; border-bottom:1px solid #eef2f5; color:#172033; font-size:14px; }
.pane-head > div { display:grid; gap:3px; min-width:0; }
.pane-head span { color:#64748b; font-size:11px; font-weight:700; }
.call-head { display:grid; gap:10px; padding:14px 15px 12px; border-bottom:1px solid #eef2f5; color:#172033; }
.call-head-main { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; min-width:0; }
.call-head-main > div { display:grid; gap:3px; min-width:0; }
.call-head-main strong { font-size:14px; }
.call-head-main span { color:#64748b; font-size:11px; font-weight:700; line-height:1.45; }
.call-count { flex:0 0 auto; align-self:center; border-radius:999px; padding:3px 9px; background:#f1f5f9; color:#475569 !important; font-size:11px; font-weight:900; }
.call-toolbar { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:8px; min-width:0; }
.call-graph-legend { display:flex; flex-wrap:wrap; gap:12px; padding:8px 15px; border-bottom:1px solid #eef2f5; color:#64748b; font-size:11px; font-weight:800; }
.call-graph-legend span { display:flex; align-items:center; gap:5px; }
.call-color { width:10px; height:10px; border:1px solid; border-radius:2px; }
.call-color.controller { background:#bfdbfe; border-color:#2563eb; }
.call-color.method { background:#e0e7ff; border-color:#6366f1; }
.call-color.private { background:#bbf7d0; border-color:#16a34a; }
.call-color.static { background:#fed7aa; border-color:#f97316; }
.call-color.recursive { background:#fecaca; border-color:#dc2626; }
.call-actions { display:flex !important; grid-template-columns:none !important; grid-auto-flow:column; align-items:center; justify-content:flex-end; gap:8px; }
.segmented-control { display:flex; flex-wrap:wrap; max-width:100%; overflow:visible; border:1px solid #dbe4ee; border-radius:9px; background:#f8fafc; }
.segmented-control button { flex:0 0 auto; border:0; padding:7px 10px; background:transparent; color:#475569; font-size:12px; font-weight:900; cursor:pointer; white-space:nowrap; }
.segmented-control button.active { background:#0f766e; color:#fff; }
.segmented-control button:disabled { cursor:not-allowed; opacity:.45; }
.mode-control { max-width:420px; }
.compact-control button { padding:7px 8px; font-size:11px; }
.ai-call-toggle { flex:0 0 auto; border:1px solid #dbe4ee; border-radius:8px; padding:7px 9px; background:#fff; color:#475569; font-size:12px; font-weight:900; cursor:pointer; white-space:nowrap; }
.ai-call-toggle.active { border-color:#0f766e; background:#ecfdf5; color:#0f766e; }
.ai-call-toggle:disabled { opacity:.56; cursor:not-allowed; }
.graph-loading-state { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:9px; flex:1; min-height:520px; padding:28px; background:#f8fafc radial-gradient(circle at 1px 1px, rgba(100,116,139,.14) 1px, transparent 0); background-size:22px 22px; color:#64748b; text-align:center; }
.graph-loading-state .spin { color:#0f766e; font-size:30px; }
.graph-loading-state strong { color:#172033; font-size:15px; }
.graph-loading-state small { max-width:420px; color:#64748b; font-size:12px; line-height:1.6; }
.map-head { align-items:center; }
.map-legend { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:10px; color:#64748b; font-size:11px; font-weight:800; }
.map-legend span { display:flex; align-items:center; gap:5px; }
.empty-card { padding:18px; color:#94a3b8; text-align:center; font-size:13px; }
.asset-group { display:grid; gap:7px; padding:12px; min-height:0; overflow:auto; }
.testcase-group { flex:1; border-top:1px solid #eef2f5; }
.group-head { display:flex; justify-content:space-between; align-items:center; color:#334155; font-size:13px; font-weight:700; }
.group-head span { min-width:20px; border-radius:999px; padding:2px 7px; background:#eff6ff; color:#2563eb; font-size:11px; text-align:center; }
.asset-card { display:grid; gap:3px; border:1px solid transparent; border-radius:10px; padding:9px 10px; background:#f8fafc; color:#172033; text-align:left; cursor:pointer; }
.asset-card:hover, .asset-card.active { border-color:rgba(15,118,110,.4); background:#f0fdfa; }
.asset-card b { color:#0f766e; font-size:11px; font-weight:900; }
.asset-card.testcase b { color:#2563eb; }
.asset-card strong, .tlc-label { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.asset-card strong { font-size:13px; }
.asset-card small { color:#64748b; font-size:11px; }
.trace-empty { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; flex:1; min-height:240px; color:#94a3b8; text-align:center; }
.trace-hint-icon { font-size:36px; }
.spin { display:inline-block; animation:spin 1.2s linear infinite; font-size:28px; }
@keyframes spin { to { transform:rotate(360deg); } }
.trace-selected-card { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; padding:14px; border-radius:12px; border:1px solid; }
.tone-req { color:#0f766e; background:#f0fdfa; border-color:rgba(15,118,110,.25); }
.tone-tc { color:#1d4ed8; background:#eff6ff; border-color:rgba(37,99,235,.25); }
.tone-code { color:#15803d; background:#f0fdf4; border-color:rgba(21,128,61,.25); }
.tone-default { color:#475569; background:#f8fafc; border-color:#e2e8f0; }
.tsc-left { display:grid; gap:4px; min-width:0; }
.tsc-type { font-size:10px; font-weight:900; text-transform:uppercase; opacity:.75; }
.tsc-left strong { font-size:15px; color:#172033; overflow-wrap:anywhere; }
.tsc-desc { font-size:12px; color:#64748b; overflow-wrap:anywhere; }
.tsc-clear { flex-shrink:0; border:0; border-radius:999px; width:28px; height:28px; background:rgba(0,0,0,.06); color:#475569; cursor:pointer; }
.trace-link-groups { display:grid; gap:14px; }
.trace-group { display:grid; gap:7px; }
.tg-head { display:flex; align-items:center; gap:8px; font-size:12px; color:#475569; }
.tg-head strong { font-size:13px; color:#172033; }
.tg-head em { margin-left:auto; border-radius:999px; padding:2px 8px; background:#f1f5f9; color:#64748b; font-size:11px; font-style:normal; font-weight:800; }
.tg-dot { width:9px; height:9px; border-radius:999px; flex-shrink:0; }
.tg-dot.tone-req { background:#0f766e; }
.tg-dot.tone-tc { background:#2563eb; }
.tg-dot.tone-code { background:#16a34a; }
.trace-link-card { display:flex; flex-wrap:wrap; align-items:center; gap:6px; width:100%; border:1px solid transparent; border-radius:10px; padding:9px 11px; background:#f8fafc; text-align:left; cursor:pointer; }
.trace-link-card:hover, .trace-link-card.active { border-color:rgba(15,118,110,.35); background:#f0fdfa; }
.tlc-label { flex:1; min-width:0; font-size:13px; color:#172033; font-weight:700; }
.tlc-sub { width:100%; font-size:11px; color:#64748b; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tlc-rel { flex-shrink:0; font-size:10px; font-weight:900; border-radius:999px; padding:2px 8px; }
.tlc-rel.static { background:#dcfce7; color:#15803d; }
.tlc-rel.dynamic { background:#dbeafe; color:#1d4ed8; }
.tlc-rel.bridged { background:#fef3c7; color:#92400e; }
.tlc-rel.derived { background:#f1f5f9; color:#475569; }
.trace-empty-links { color:#94a3b8; font-size:13px; text-align:center; padding:24px 0; }
.legend-row { display:flex; gap:10px; padding:10px 14px; border-bottom:1px solid #eef2f5; color:#64748b; font-size:11px; font-weight:800; }
.tree-actions { display:flex !important; grid-template-columns:none !important; gap:6px; justify-content:flex-end; }
.tree-actions button { border:1px solid #dbe4ee; border-radius:7px; padding:5px 8px; background:#fff; color:#475569; font-size:11px; font-weight:900; cursor:pointer; }
.tree-actions button:hover { border-color:#0f766e; color:#0f766e; }
.code-node-detail { display:grid; gap:4px; margin:10px 12px 0; padding:10px 12px; border:1px solid; border-radius:10px; }
.code-node-detail p { margin:2px 0 0; color:#475569; font-size:12px; }
.legend-row span { display:flex; align-items:center; gap:5px; }
.legend { display:inline-block; width:8px; height:8px; border-radius:999px; }
.legend.req { background:#2563eb; }
.legend.tc { background:#f97316; }
.legend.code { background:#16a34a; }
.legend.static { background:#16a34a; }
.legend.dynamic { background:#2563eb; }
.legend.both { background:#0f766e; }
.line-sample { display:inline-block; width:18px; height:0; border-top:2px dashed #94a3b8; }
.line-sample.solid { border-top-style:solid; }
.trace-map-scroll { flex:1; overflow:auto; background:#fbfce8; }
.trace-map-svg { display:block; width:100%; min-width:920px; height:auto; min-height:560px; cursor:default; }
.lane-band { fill:rgba(254,252,232,.74); stroke:#c6c453; stroke-width:1; }
.lane-title { fill:#475569; font-size:13px; font-weight:900; text-anchor:middle; }
.trace-edge-layer { pointer-events:none; }
.trace-svg-edge path, .call-svg-edge path { fill:none; stroke:#64748b; stroke-width:1.4; opacity:.72; transition:opacity .16s ease, stroke .16s ease, stroke-width .16s ease; }
.trace-svg-edge text, .call-svg-edge text { fill:#475569; font-size:11px; font-weight:800; paint-order:stroke; stroke:#fff; stroke-width:5px; stroke-linejoin:round; transition:opacity .16s ease, fill .16s ease; }
.trace-svg-edge.derived path { stroke-dasharray:4 4; opacity:.62; }
.trace-svg-edge.dynamic path, .call-svg-edge.dynamic path { stroke:#2563eb; stroke-width:1.8; }
.trace-svg-edge.bridged path, .call-svg-edge.bridged path { stroke:#d97706; stroke-dasharray:5 3; }
.trace-svg-edge.active path { stroke:#0f766e; stroke-width:3.2; opacity:1; }
.trace-svg-edge.active text { fill:#0f766e; opacity:1; }
.trace-svg-edge.dimmed { opacity:.16; }
.trace-svg-node { cursor:pointer; }
.trace-svg-node rect { stroke-width:2; filter:drop-shadow(0 8px 12px rgba(15,23,42,.08)); transition:opacity .16s ease, stroke .16s ease, stroke-width .16s ease, filter .16s ease; }
.trace-svg-node.req rect { fill:#bfdbfe; stroke:#2563eb; }
.trace-svg-node.tc rect { fill:#fed7aa; stroke:#f97316; }
.trace-svg-node.code rect { fill:#bbf7d0; stroke:#15803d; }
.trace-svg-node.active rect { stroke:#7c3aed; stroke-width:3.4; filter:drop-shadow(0 10px 18px rgba(124,58,237,.22)); }
.trace-svg-node.linked:not(.active) rect { stroke:#0f766e; stroke-width:2.8; filter:drop-shadow(0 8px 15px rgba(15,118,110,.18)); }
.trace-svg-node.dimmed { opacity:.24; }
.node-title, .node-subtitle { text-anchor:middle; pointer-events:none; }
.node-title { fill:#172033; font-size:12px; font-weight:900; }
.node-subtitle { fill:#475569; font-size:11px; font-weight:700; }
.call-map-scroll { flex:0 0 220px; overflow:auto; border-bottom:1px solid #eef2f5; background:#f8fafc; }
.call-map-scroll.large { position:relative; flex:1; min-height:560px; border-bottom:0; background:#f8fafc radial-gradient(circle at 1px 1px, rgba(100,116,139,.14) 1px, transparent 0); background-size:22px 22px; }
.call-map-scroll.large.fullscreen { position:fixed; inset:12px; z-index:1000; min-height:0; border:1px solid #dbe4ee; border-radius:12px; box-shadow:0 24px 80px rgba(15,23,42,.28); }
.call-graph-toolbar { position:absolute; z-index:2; top:10px; right:12px; display:flex; align-items:center; gap:5px; padding:5px 7px; border:1px solid #e2e8f0; border-radius:8px; background:rgba(255,255,255,.92); box-shadow:0 5px 16px rgba(15,23,42,.08); color:#64748b; font-size:11px; font-weight:800; }
.call-graph-toolbar button { border:0; border-radius:5px; padding:5px 7px; background:#f1f5f9; color:#334155; font-size:11px; font-weight:900; cursor:pointer; }
.call-graph-toolbar button:hover { background:#dbeafe; color:#1d4ed8; }
.graph-mode-label { margin-right:5px; color:#172033; }
.call-graph-viewport { width:100%; height:100%; min-height:560px; overflow:auto; padding:22px; box-sizing:border-box; }
.call-map-svg { display:block; min-width:760px; min-height:300px; }
.call-map-scroll.large.fullscreen .call-graph-viewport { height:calc(100vh - 58px); }
:global(body.trace-call-graph-fullscreen) { overflow:hidden; }
.coverage-data-panel { flex:1; overflow:auto; background:#fff; }
.code-analysis-panel { flex:1; min-height:560px; overflow:auto; background:#f8fafc radial-gradient(circle at 1px 1px, rgba(100,116,139,.14) 1px, transparent 0); background-size:22px 22px; }
.mini-code-graph { display:block; width:100%; min-width:760px; min-height:560px; padding:20px; box-sizing:border-box; }
.mini-graph-edge path { fill:none; stroke:#94a3b8; stroke-width:1.4; opacity:.7; transition:opacity .16s ease, stroke .16s ease, stroke-width .16s ease; }
.mini-graph-edge.active path { stroke:#0f766e; stroke-width:3; opacity:1; }
.mini-graph-edge.dimmed { opacity:.14; }
.mini-graph-node { cursor:pointer; }
.mini-graph-node rect { fill:#e0e7ff; stroke:#6366f1; stroke-width:1.5; transition:opacity .16s ease, stroke .16s ease, stroke-width .16s ease, filter .16s ease; }
.mini-graph-node.source rect { fill:#dbeafe; stroke:#2563eb; }
.mini-graph-node.target rect { fill:#fef3c7; stroke:#d97706; }
.mini-graph-node.branch rect { fill:#fed7aa; stroke:#f97316; }
.mini-graph-node.exit rect { fill:#fecaca; stroke:#dc2626; }
.mini-graph-node.active rect { stroke:#7c3aed; stroke-width:2.8; filter:drop-shadow(0 8px 14px rgba(124,58,237,.18)); }
.mini-graph-node.linked:not(.active) rect { stroke:#0f766e; stroke-width:2.5; filter:drop-shadow(0 6px 12px rgba(15,118,110,.16)); }
.mini-graph-node.dimmed { opacity:.26; }
.mini-graph-node text { fill:#172033; font-size:12px; font-weight:900; text-anchor:middle; pointer-events:none; }
.mini-graph-node .mini-node-subtitle { fill:#64748b; font-size:10px; font-weight:700; }
.coverage-table { width:100%; border-collapse:collapse; font-size:13px; }
.coverage-table th, .coverage-table td { border-bottom:1px solid #e5e7eb; padding:10px 12px; text-align:left; vertical-align:middle; }
.coverage-table th { position:sticky; top:0; z-index:1; background:#f8fafc; color:#172033; font-weight:900; }
.coverage-table button { border:0; background:transparent; color:#0f766e; font-weight:900; cursor:pointer; }
.coverage-pill { display:inline-flex; align-items:center; border-radius:999px; padding:2px 8px; background:#f1f5f9; color:#475569; font-size:11px; font-weight:900; }
.coverage-pill.dynamic { background:#dbeafe; color:#1d4ed8; }
.coverage-pill.both { background:#ccfbf1; color:#0f766e; }
.coverage-pill.static { background:#dcfce7; color:#15803d; }
.call-svg-edge path { stroke:#94a3b8; stroke-width:1.2; opacity:.62; }
.call-svg-edge.structure path { stroke:#0f766e; stroke-width:.9; opacity:.28; stroke-dasharray:3 4; }
.call-svg-edge.inferred path { stroke:#0f766e; stroke-width:1.3; opacity:.72; stroke-dasharray:5 4; }
.call-svg-edge .edge-label-bg { fill:rgba(255,255,255,.96); stroke:#e2e8f0; stroke-width:1; filter:drop-shadow(0 2px 5px rgba(15,23,42,.08)); }
.call-svg-edge text { fill:#64748b; font-size:10px; font-weight:900; text-anchor:middle; pointer-events:none; }
.call-svg-edge.recursive path { stroke:#dc2626; stroke-width:1.8; stroke-dasharray:5 3; }
.call-svg-edge.recursive .edge-label-bg { fill:#fff1f2; stroke:#fecaca; }
.call-svg-edge.recursive text { fill:#b91c1c; }
.call-svg-edge.active path { stroke:#0f766e; stroke-width:2.8; opacity:1; }
.call-svg-edge.structure.active path { stroke:#0f766e; stroke-width:1.1; opacity:.36; stroke-dasharray:3 4; }
.call-svg-edge.inferred.active path { stroke:#0f766e; stroke-width:2.1; opacity:1; stroke-dasharray:5 4; }
.call-svg-edge.active .edge-label-bg { fill:#ecfdf5; stroke:#5eead4; }
.call-svg-edge.active text { fill:#0f766e; }
.call-svg-edge.dimmed { opacity:.16; }
.call-svg-node { cursor:pointer; }
.call-svg-node rect { fill:#e0e7ff; stroke:#6366f1; stroke-width:1.8; filter:drop-shadow(0 8px 14px rgba(15,23,42,.08)); }
.call-svg-node.call-controller rect { fill:#bfdbfe; stroke:#2563eb; }
.call-svg-node.call-private rect { fill:#bbf7d0; stroke:#16a34a; }
.call-svg-node.call-static rect { fill:#fed7aa; stroke:#f97316; }
.call-svg-node.call-recursive rect { fill:#fecaca; stroke:#dc2626; }
.call-svg-node.active rect { stroke:#7c3aed; stroke-width:2.8; }
.call-svg-node.linked:not(.active) rect { stroke:#0f766e; stroke-width:2.5; }
.call-svg-node.dimmed { opacity:.28; }
.call-svg-node text { fill:#172033; font-size:12px; font-weight:900; text-anchor:middle; pointer-events:none; dominant-baseline:middle; }
.call-svg-node .call-node-subtitle { fill:#64748b; font-size:10px; font-weight:800; }
.code-tree-head { border-top:1px solid #eef2f5; }
.tree-scroll { flex:1; min-height:160px; overflow-y:auto; padding:8px 6px 12px; }
@media(max-width:1180px) { .summary-strip { grid-template-columns:repeat(3,minmax(0,1fr)); } .calls-tab, .trace-reference-grid { grid-template-columns:1fr; } .calls-tab { grid-template-areas:'graph' 'tree'; } .code-side-pane { min-height:420px; } }
@media(max-width:760px) { .workspace-toolbar, .trace-controls { flex-direction:column; align-items:stretch; } .summary-strip { grid-template-columns:1fr; } .map-legend { justify-content:flex-start; } .workspace-tabs { overflow-x:auto; } .workspace-tabs button { white-space:nowrap; } }
</style>
