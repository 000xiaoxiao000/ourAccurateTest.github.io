<template>
  <section :class="['relation-board', compact && 'compact-board']">
    <div v-if="!compact" class="page-header">
      <div>
        <div class="eyebrow">{{ eyebrow }}</div>
        <h1>{{ title }}</h1>
        <p v-if="subtext" class="subtext">{{ subtext }}</p>
      </div>
      <div v-if="backRoute" class="header-actions">
        <RouterLink class="secondary-link" :to="backRoute">{{ backLabel || '返回' }}</RouterLink>
      </div>
    </div>

    <div v-if="loading" class="status-card">{{ loadingText || '正在加载...' }}</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else>
      <div v-if="!compact" class="summary-grid">
        <article class="summary-card">
          <span>节点</span>
          <strong>{{ filteredNodes.length }}</strong>
        </article>
        <article class="summary-card">
          <span>关系</span>
          <strong>{{ filteredEdges.length }}</strong>
        </article>
      </div>

      <div v-if="!compact || compactSearchOpen || keyword" :class="['graph-search-block', compact && 'compact-search-block']">
        <label class="search-box">
          <span>图谱搜索</span>
              <input
                ref="searchInputRef"
                v-model.trim="keyword"
                class="text-input"
                type="search"
                placeholder="输入名称、类型或描述，自动定位节点"
                aria-label="搜索图谱节点"
                @keydown.down.prevent="selectSearchMatch(searchMatches[0]?.id)"
              />
        </label>
        <div v-if="keyword" class="graph-search-results">
          <button
            v-for="node in searchMatches.slice(0, 8)"
            :key="node.id"
            type="button"
            :class="{ active: selectedNode?.id === node.id }"
            @click="selectSearchMatch(node.id)"
          >
            <strong>{{ node.label || node.id }}</strong>
            <span>{{ node.type || 'node' }}</span>
          </button>
          <div v-if="!searchMatches.length" class="empty-search-result">没有匹配节点</div>
        </div>
      </div>

      <div :class="['board-workspace', hideLists && 'graph-only']">
        <section
          class="graph-panel"
          @click="closeContextMenu"
          @wheel.prevent="handleGraphWheel"
          @pointerdown="startBoardPan"
          @pointermove="moveBoardPan"
          @pointerup="endBoardPan"
          @pointerleave="endBoardPan"
          @contextmenu.prevent="openCanvasContextMenu"
        >
          <div class="graph-toolbar">
            <div>图形画布</div>
            <div class="graph-tools">
              <button type="button" @click.stop="openSearchPanel">查找</button>
              <button
                type="button"
                :class="{ active: graphLayoutDirection === 'horizontal' }"
                @click.stop="setGraphLayoutDirection('horizontal')"
              >
                横向
              </button>
              <button
                type="button"
                :class="{ active: graphLayoutDirection === 'vertical' }"
                @click.stop="setGraphLayoutDirection('vertical')"
              >
                纵向
              </button>
              <button type="button" @click.stop="fitGraph">适配视图</button>
              <button type="button" @click.stop="zoomGraph(0.15)">放大</button>
              <button type="button" @click.stop="zoomGraph(-0.15)">缩小</button>
              <button type="button" @click.stop="resetGraphLayout">重排</button>
              <button type="button" @click.stop="showSelectedTip" :disabled="!selectedNode">节点提示</button>
            </div>
          </div>
          <svg ref="relationGraphRef" class="relation-graph" :viewBox="graphViewBox" role="img" aria-label="关系图画布">
          <defs>
            <marker id="graph-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L0,6 L9,3 z" fill="#64748b" />
            </marker>
          </defs>
          <g :transform="graphTransform">
            <g class="edge-layer">
              <g v-for="edge in graphEdges" :key="edge.id" :class="['graph-edge', edgeTone(edge), edgeRelated(edge) && 'related', edgeDimmed(edge) && 'dimmed']">
                <path :d="edge.path" marker-end="url(#graph-arrow)" />
                <text v-if="showEdgeLabel(edge)" :x="edge.labelX" :y="edge.labelY">{{ edge.label || actionText(edge.action) || '关联' }}</text>
              </g>
            </g>
            <g class="node-layer">
              <g
                v-for="node in graphNodes"
                :key="node.id"
                :class="['graph-node', nodeTone(node), selectedNode?.id === node.id && 'active', searchMatchIds.has(node.id) && 'find-match', nodeRelated(node) && 'related', nodeDimmed(node) && 'dimmed']"
                :transform="`translate(${node.x}, ${node.y})`"
                @pointerdown.stop="startNodeDrag($event, node)"
                @click.stop="selectGraphNode(node.id)"
                @contextmenu.prevent.stop="openContextMenu($event, node.id)"
              >
                <circle :r="node.radius" />
                <text v-if="showNodeLabel(node)" class="node-label" text-anchor="middle" :y="node.radius + 16">{{ node.shortLabel }}</text>
                <text v-if="node.metric" class="node-metric" text-anchor="middle" y="5">{{ node.metric }}</text>
              </g>
            </g>
          </g>
          </svg>
          <div v-if="contextMenu.open && visibleContextActions.length" class="graph-context-menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @click.stop>
          <button
            v-for="action in visibleContextActions"
            :key="`${action.id}-${action.target || 'node'}`"
            type="button"
            :class="{ danger: action.danger }"
            :disabled="action.disabled"
            @click="runContextAction(action)"
          >
            <span>{{ action.label }}</span>
            <small v-if="action.shortcut">{{ action.shortcut }}</small>
          </button>
          </div>
          <div v-if="tip.open && selectedNode" class="graph-tip" @click.stop="tip.open = false">
          <strong>{{ selectedNode.label || selectedNode.id }}</strong>
          <span class="tip-line">ID：{{ selectedNode.id }}</span>
          <span class="tip-line">类型：{{ selectedNode.type || 'node' }}</span>
          <span v-if="selectedNode.description" class="tip-line">描述：{{ selectedNode.description }}</span>
          <ul v-if="selectedNode.meta?.length" class="tip-meta">
            <li v-for="item in selectedNode.meta" :key="item">{{ item }}</li>
          </ul>
          <span class="tip-line">关联关系：{{ selectedEdges.length }} 条</span>
          </div>
        </section>

        <aside v-if="!hideLists" class="board-side">
          <div class="layout-grid compact-lists">
            <section class="panel node-list-panel">
              <div class="panel-head">
                <h2>节点列表</h2>
                <span>{{ filteredNodes.length }}</span>
              </div>
              <div v-if="!filteredNodes.length" class="empty-card">暂无节点数据</div>
              <div v-else class="node-grid">
                <button
                  v-for="node in paginatedNodes"
                  :key="node.id"
                  type="button"
                  class="node-card"
                  :class="{ active: selectedNode?.id === node.id }"
                  @click="selectGraphNode(node.id)"
                >
                  <div class="node-top">
                    <strong>{{ node.label || node.id }}</strong>
                    <span class="type-pill">{{ node.type || 'node' }}</span>
                  </div>
                  <p v-if="node.description" class="node-desc">{{ node.description }}</p>
                  <ul v-if="node.meta?.length" class="meta-list">
                    <li v-for="item in node.meta" :key="item">{{ item }}</li>
                  </ul>
                </button>
              </div>
              <AppPagination
                v-if="filteredNodes.length"
                v-model:page="nodePage"
                v-model:page-size="nodePageSize"
                :total="filteredNodes.length"
                item-name="个节点"
                :page-sizes="[8, 16, 24]"
              />
            </section>

            <section class="panel node-detail-panel">
              <div class="panel-head">
                <h2>节点详情</h2>
                <span>{{ selectedNode?.type || '-' }}</span>
              </div>
              <div v-if="selectedNode" class="detail-card">
                <h3>{{ selectedNode.label || selectedNode.id }}</h3>
                <p class="detail-id">{{ selectedNode.id }}</p>
                <p v-if="selectedNode.description" class="node-desc">{{ selectedNode.description }}</p>
                <ul v-if="selectedNode.meta?.length" class="meta-list">
                  <li v-for="item in selectedNode.meta" :key="item">{{ item }}</li>
                </ul>
                <div class="connection-group">
                  <strong>关联关系</strong>
                  <div v-if="!selectedEdges.length" class="empty-inline">该节点暂无关系</div>
                  <div v-else class="edge-list">
                    <article v-for="edge in visibleSelectedEdges" :key="edge.id" :class="['edge-card', edgeTone(edge)]">
                      <span>{{ edge.sourceLabel || edge.source }}</span>
                      <strong>{{ edge.label || actionText(edge.action) || '关联' }}</strong>
                      <span>{{ edge.targetLabel || edge.target }}</span>
                    </article>
                  </div>
                  <button v-if="selectedEdges.length > selectedEdgePreviewLimit" class="inline-more-button" type="button" @click="showAllSelectedEdges = !showAllSelectedEdges">
                    {{ showAllSelectedEdges ? '收起关系' : `查看全部 ${selectedEdges.length} 条` }}
                  </button>
                </div>
              </div>
              <div v-else class="empty-card">选择一个节点后可查看详细关系</div>
            </section>

            <section class="panel edge-panel">
              <div class="panel-head">
                <h2>关系清单</h2>
                <span>{{ filteredEdges.length }}</span>
              </div>
              <div v-if="!filteredEdges.length" class="empty-card">暂无关系数据</div>
              <div v-else class="table-shell">
                <table class="edge-table">
                  <thead>
                    <tr>
                      <th>来源</th>
                      <th>关系</th>
                      <th>目标</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="edge in paginatedEdges" :key="edge.id">
                      <td>{{ edge.sourceLabel || edge.source }}</td>
                      <td><span :class="['action-pill', edgeTone(edge)]">{{ edge.label || actionText(edge.action) || '-' }}</span></td>
                      <td>{{ edge.targetLabel || edge.target }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <AppPagination
                v-if="filteredEdges.length"
                v-model:page="edgePage"
                v-model:page-size="edgePageSize"
                :total="filteredEdges.length"
                item-name="条关系"
                :page-sizes="[10, 20, 50]"
              />
            </section>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, watchEffect } from 'vue'
import { RouterLink } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'
import AppPagination from '@/components/AppPagination.vue'
import {
  actionText,
  edgeTone,
  nodeSearchText,
  nodeTone,
  nodeTypeText,
  useRelationGraphLayout,
} from '@/features/map/composables/useRelationGraphLayout'
import type { GraphLayoutDirection, GraphNode, RelationContextAction, RelationEdge, RelationNode } from '@/features/map/types'

const props = defineProps<{
  eyebrow: string
  title: string
  subtext?: string
  loading: boolean
  loadingText?: string
  error?: string
  nodes: RelationNode[]
  edges: RelationEdge[]
  backRoute?: RouteLocationRaw
  backLabel?: string
  compact?: boolean
  hideLists?: boolean
  contextActions?: RelationContextAction[]
  showEdgeLabels?: boolean
  highlightRelated?: boolean
  selectedNodeId?: string
  layoutDirection?: GraphLayoutDirection
}>()

const emit = defineEmits<{
  (event: 'node-select', node: RelationNode | null): void
  (event: 'context-action', actionId: string, node: RelationNode | null): void
}>()

const compact = computed(() => Boolean(props.compact))
const hideLists = computed(() => Boolean(props.hideLists))

const keyword = ref('')
const compactSearchOpen = ref(false)
const selectedId = ref('')
const nodePage = ref(1)
const edgePage = ref(1)
const nodePageSize = ref(8)
const edgePageSize = ref(10)
const selectedEdgePreviewLimit = 8
const showAllSelectedEdges = ref(false)
const searchInputRef = ref<HTMLInputElement | null>(null)
const contextMenu = reactive({ open: false, x: 0, y: 0, nodeId: '', target: 'canvas' as 'canvas' | 'node' })
const tip = reactive({ open: false })
const relationGraphRef = ref<SVGSVGElement | null>(null)
const graphZoom = ref(1)
const graphOffset = ref({ x: 0, y: 0 })
const graphLayoutDirection = ref<GraphLayoutDirection>(props.layoutDirection || 'horizontal')
const graphPan = ref<{ startX: number; startY: number; originX: number; originY: number } | null>(null)
const nodePositionOverrides = ref<Record<string, { x: number; y: number }>>({})
const nodeDrag = ref<{ id: string; startX: number; startY: number; originX: number; originY: number } | null>(null)


const filteredNodes = computed(() => {
  if (!keyword.value) {
    return props.nodes
  }
  const needle = keyword.value.toLowerCase()
  return props.nodes.filter((node) =>
    [node.id, node.label, node.type, node.description, ...(node.meta || [])]
      .filter(Boolean)
      .some((item) => String(item).toLowerCase().includes(needle)),
  )
})

const filteredEdges = computed(() => {
  const allowed = new Set(filteredNodes.value.map((node) => node.id))
  return props.edges.filter((edge) => allowed.has(edge.source) || allowed.has(edge.target))
})

const nodeTotalPages = computed(() => Math.max(1, Math.ceil(filteredNodes.value.length / nodePageSize.value)))
const paginatedNodes = computed(() => {
  const start = (nodePage.value - 1) * nodePageSize.value
  return filteredNodes.value.slice(start, start + nodePageSize.value)
})

const edgeTotalPages = computed(() => Math.max(1, Math.ceil(filteredEdges.value.length / edgePageSize.value)))
const paginatedEdges = computed(() => {
  const start = (edgePage.value - 1) * edgePageSize.value
  return filteredEdges.value.slice(start, start + edgePageSize.value)
})

const selectedNode = computed(() => filteredNodes.value.find((node) => node.id === selectedId.value))
const selectedEdges = computed(() =>
  filteredEdges.value.filter((edge) => edge.source === selectedId.value || edge.target === selectedId.value),
)
const visibleSelectedEdges = computed(() => showAllSelectedEdges.value ? selectedEdges.value : selectedEdges.value.slice(0, selectedEdgePreviewLimit))
const relatedNodeIds = computed(() => {
  const ids = new Set<string>()
  if (!selectedId.value) return ids
  ids.add(selectedId.value)
  filteredEdges.value.forEach((edge) => {
    if (edge.source === selectedId.value) ids.add(edge.target)
    if (edge.target === selectedId.value) ids.add(edge.source)
  })
  return ids
})
const relatedEdgeIds = computed(() => {
  if (!selectedId.value) return new Set<string>()
  return new Set(filteredEdges.value
    .filter((edge) => edge.source === selectedId.value || edge.target === selectedId.value)
    .map((edge) => edge.id))
})
const searchMatches = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) return []
  return props.nodes.filter((node) => nodeSearchText(node).includes(needle))
})
const searchMatchIds = computed(() => new Set(searchMatches.value.map((node) => node.id)))
const contextMenuNode = computed(() => filteredNodes.value.find((node) => node.id === contextMenu.nodeId) || null)
const builtInContextActions = computed<RelationContextAction[]>(() => {
  if (contextMenu.target === 'canvas') {
    return [
      { id: 'find', label: '查找', target: 'canvas', shortcut: 'Ctrl+F' },
      { id: 'fit', label: '适配视图', target: 'canvas' },
      { id: 'relayout', label: '重排图谱', target: 'canvas' },
    ]
  }
  return [{ id: 'tip', label: '节点概要', target: 'node', shortcut: 'F2' }]
})
const visibleContextActions = computed(() =>
  [...(props.contextActions || []), ...builtInContextActions.value]
    .filter(actionMatchesContext)
)

const layoutSourceNodes = computed(() => filteredNodes.value)
const layoutSourceEdges = computed(() => filteredEdges.value)
const {
  denseGraph,
  graphEdges,
  graphNodes,
  graphNodeMap,
  graphViewBox,
  graphViewport,
} = useRelationGraphLayout(layoutSourceNodes, layoutSourceEdges, compact, nodePositionOverrides, graphLayoutDirection)
const graphTransform = computed(() => `translate(${graphOffset.value.x} ${graphOffset.value.y}) scale(${graphZoom.value})`)

function selectGraphNode(nodeId: string) {
  selectedId.value = nodeId
  tip.open = false
  emit('node-select', filteredNodes.value.find((node) => node.id === nodeId) || null)
}

function closeContextMenu() {
  contextMenu.open = false
}

function openCanvasContextMenu(event: MouseEvent) {
  if ((event.target as Element).closest('.graph-node, .graph-tools, .graph-context-menu, .graph-tip')) return
  contextMenu.open = true
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.nodeId = ''
  contextMenu.target = 'canvas'
}

function openContextMenu(event: MouseEvent, nodeId: string) {
  selectGraphNode(nodeId)
  contextMenu.open = true
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.nodeId = nodeId
  contextMenu.target = 'node'
}

function actionMatchesContext(action: RelationContextAction) {
  const target = action.target || 'node'
  if (target === 'canvas') {
    return contextMenu.target === 'canvas'
  }
  if (contextMenu.target !== 'node') {
    return false
  }
  if (target === 'node') {
    return Boolean(contextMenuNode.value)
  }
  return hasContextNodeClass(target)
}

function hasContextNodeClass(target: Exclude<NonNullable<RelationContextAction['target']>, 'canvas' | 'node'>) {
  const node = contextMenuNode.value
  if (!node) return false
  const tokens = `${node.type || ''} ${(node.classes || []).join(' ')}`.toLowerCase().split(/\s+/).filter(Boolean)
  if (target === 'remote') return tokens.some((item) => item.includes('remote') || item.includes('dubbo'))
  if (target === 'code') return tokens.some((item) => item.includes('code'))
  return tokens.some((item) => item === target || item.includes(target))
}

function runContextAction(action: RelationContextAction) {
  if (action.disabled) return
  const node = contextMenuNode.value
  if (action.id === 'find') {
    openSearchPanel()
    closeContextMenu()
    return
  }
  if (action.id === 'fit') {
    fitGraph()
    closeContextMenu()
    return
  }
  if (action.id === 'relayout') {
    resetGraphLayout()
    closeContextMenu()
    return
  }
  if (action.id === 'tip') {
    if (node) selectGraphNode(node.id)
    showSelectedTip()
    closeContextMenu()
    return
  }
  emit('context-action', action.id, node)
  closeContextMenu()
}

function focusSearchInput() {
  searchInputRef.value?.focus()
}

async function openSearchPanel() {
  compactSearchOpen.value = true
  await nextTick()
  focusSearchInput()
  searchInputRef.value?.select()
}

function clampGraphZoom(value: number) {
  return Math.min(3, Math.max(0.45, value))
}

function zoomGraph(delta: number) {
  graphZoom.value = clampGraphZoom(graphZoom.value + delta)
}

function fitGraph() {
  const bounds = graphContentBounds()
  const padding = denseGraph.value ? 64 : 80
  const viewport = graphViewport.value
  const availableWidth = Math.max(320, viewport.width - padding * 2)
  const availableHeight = Math.max(260, viewport.height - padding * 2)
  const widthRatio = availableWidth / Math.max(bounds.width, 1)
  const heightRatio = availableHeight / Math.max(bounds.height, 1)
  const zoom = clampGraphZoom(Math.min(1.35, Math.max(0.55, Math.min(widthRatio, heightRatio))))
  graphZoom.value = zoom
  graphOffset.value = {
    x: viewport.width / 2 - (bounds.x + bounds.width / 2) * zoom,
    y: viewport.height / 2 - (bounds.y + bounds.height / 2) * zoom,
  }
}

function graphContentBounds() {
  const nodes = graphNodes.value
  if (!nodes.length) {
    return { x: 0, y: 0, width: graphViewport.value.width, height: graphViewport.value.height }
  }
  const labelPadding = denseGraph.value ? 36 : 72
  const minX = Math.min(...nodes.map((node) => node.x - node.radius - labelPadding))
  const maxX = Math.max(...nodes.map((node) => node.x + node.radius + labelPadding))
  const minY = Math.min(...nodes.map((node) => node.y - node.radius - labelPadding))
  const maxY = Math.max(...nodes.map((node) => node.y + node.radius + labelPadding))
  return {
    x: minX,
    y: minY,
    width: Math.max(1, maxX - minX),
    height: Math.max(1, maxY - minY),
  }
}

function resetGraphLayout() {
  nodePositionOverrides.value = {}
  fitGraph()
}

async function setGraphLayoutDirection(direction: GraphLayoutDirection) {
  if (graphLayoutDirection.value === direction) return
  graphLayoutDirection.value = direction
  nodePositionOverrides.value = {}
  await nextTick()
  fitGraph()
}

function graphPointerDelta(event: PointerEvent, startX: number, startY: number) {
  const rect = relationGraphRef.value?.getBoundingClientRect()
  const scaleX = rect?.width ? graphViewport.value.width / rect.width : 1
  const scaleY = rect?.height ? graphViewport.value.height / rect.height : 1
  return {
    x: (event.clientX - startX) * scaleX / graphZoom.value,
    y: (event.clientY - startY) * scaleY / graphZoom.value,
  }
}

function handleGraphWheel(event: WheelEvent) {
  zoomGraph(event.deltaY > 0 ? -0.1 : 0.1)
}

function startBoardPan(event: PointerEvent) {
  if ((event.target as Element).closest('.graph-tools, .graph-node, .graph-context-menu, .graph-tip')) return
  graphPan.value = {
    startX: event.clientX,
    startY: event.clientY,
    originX: graphOffset.value.x,
    originY: graphOffset.value.y,
  }
}

function moveBoardPan(event: PointerEvent) {
  if (nodeDrag.value) {
    const delta = graphPointerDelta(event, nodeDrag.value.startX, nodeDrag.value.startY)
    nodePositionOverrides.value = {
      ...nodePositionOverrides.value,
      [nodeDrag.value.id]: {
        x: nodeDrag.value.originX + delta.x,
        y: nodeDrag.value.originY + delta.y,
      },
    }
    return
  }
  if (!graphPan.value) return
  const delta = graphPointerDelta(event, graphPan.value.startX, graphPan.value.startY)
  graphOffset.value = {
    x: graphPan.value.originX + delta.x,
    y: graphPan.value.originY + delta.y,
  }
}

function endBoardPan() {
  graphPan.value = null
  nodeDrag.value = null
}

function startNodeDrag(event: PointerEvent, node: GraphNode) {
  selectGraphNode(node.id)
  nodeDrag.value = {
    id: node.id,
    startX: event.clientX,
    startY: event.clientY,
    originX: node.x,
    originY: node.y,
  }
}

function selectSearchMatch(nodeId?: string) {
  if (!nodeId) return
  selectGraphNode(nodeId)
  centerGraphOnNode(nodeId)
}

function centerGraphOnNode(nodeId: string) {
  const node = graphNodeMap.value.get(nodeId)
  if (!node) return
  graphZoom.value = Math.max(graphZoom.value, denseGraph.value ? 1.15 : 1)
  graphOffset.value = {
    x: graphViewport.value.width / 2 - node.x * graphZoom.value,
    y: graphViewport.value.height / 2 - node.y * graphZoom.value,
  }
}

function showNodeLabel(node: GraphNode) {
  if (!denseGraph.value) return true
  return selectedId.value === node.id || searchMatchIds.value.has(node.id) || relatedNodeIds.value.has(node.id)
}

function showEdgeLabel(edge: { id: string }) {
  const showByDefault = props.showEdgeLabels ?? !denseGraph.value
  return showByDefault || relatedEdgeIds.value.has(edge.id)
}

function nodeRelated(node: GraphNode) {
  return Boolean(props.highlightRelated && relatedNodeIds.value.has(node.id))
}

function nodeDimmed(node: GraphNode) {
  return Boolean(props.highlightRelated && selectedId.value && !relatedNodeIds.value.has(node.id))
}

function edgeRelated(edge: { id: string }) {
  return Boolean(props.highlightRelated && relatedEdgeIds.value.has(edge.id))
}

function edgeDimmed(edge: { id: string }) {
  return Boolean(props.highlightRelated && selectedId.value && !relatedEdgeIds.value.has(edge.id))
}

function showSelectedTip() {
  if (!selectedNode.value) return
  tip.open = true
  closeContextMenu()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.ctrlKey && event.key.toLowerCase() === 'f') {
    event.preventDefault()
    openSearchPanel()
  }
  if (event.key === 'F2') {
    showSelectedTip()
  }
}

watch(
  () => props.selectedNodeId,
  (nodeId) => {
    if (!nodeId || nodeId === selectedId.value || !props.nodes.some((node) => node.id === nodeId)) return
    selectedId.value = nodeId
    centerGraphOnNode(nodeId)
  },
)

watch(
  () => props.layoutDirection,
  (direction) => {
    if (!direction || direction === graphLayoutDirection.value) return
    graphLayoutDirection.value = direction
    nodePositionOverrides.value = {}
  },
)

watch(
  () => [
    layoutSourceNodes.value.map((node) => node.id).join('|'),
    layoutSourceEdges.value.map((edge) => `${edge.source}>${edge.target}`).join('|'),
    graphLayoutDirection.value,
  ],
  async () => {
    nodePositionOverrides.value = {}
    await nextTick()
    fitGraph()
  },
  { flush: 'post' },
)

watch([nodePageSize, edgePageSize], () => {
  nodePage.value = 1
  edgePage.value = 1
})

watchEffect(() => {
  if (nodePage.value > nodeTotalPages.value) nodePage.value = nodeTotalPages.value
  if (edgePage.value > edgeTotalPages.value) edgePage.value = edgeTotalPages.value
})
watchEffect(() => {
  keyword.value
  nodePage.value = 1
  edgePage.value = 1
})

watchEffect(() => {
  selectedId.value
  showAllSelectedEdges.value = false
})

watchEffect(() => {
  const firstMatch = searchMatches.value[0]
  if (keyword.value && firstMatch && selectedId.value !== firstMatch.id) {
    selectedId.value = firstMatch.id
    emit('node-select', firstMatch)
  }
})

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))

watchEffect(() => {
  if (!selectedId.value && filteredNodes.value.length) {
    selectedId.value = filteredNodes.value[0].id
    emit('node-select', filteredNodes.value[0])
    return
  }
  if (selectedId.value && !filteredNodes.value.some((node) => node.id === selectedId.value)) {
    selectedId.value = filteredNodes.value[0]?.id || ''
    emit('node-select', filteredNodes.value[0] || null)
  }
})
</script>

<style scoped src="@/features/map/styles/relation-board.css"></style>
