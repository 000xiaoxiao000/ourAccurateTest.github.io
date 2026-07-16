import { computed, ref } from 'vue'
import { fetchTraceabilityMap } from '@/api/traceabilityMap'
import type { CodeTreeNode, TraceabilityEdge, TraceabilityMapResponse, TraceabilityNode, TraceRelation } from '@/api/traceabilityMap'

export type TraceFilter = 'ALL' | 'COMPLETE' | 'BROKEN' | 'DYNAMIC_CONFIRMED' | 'STATIC_BRIDGED' | 'PENDING_REVIEW'

export function useTraceabilityMap(projectId: () => string) {
  const loading = ref(false)
  const error = ref('')
  const activeBaselineId = ref('')
  const focusId = ref('')
  const depth = ref(4)
  // Code call-chain data belongs to the code graph tab. It is derived from
  // source/static indexes and is independent from verification AI analysis.
  const includeAiCalls = ref(true)
  const filter = ref<TraceFilter>('ALL')
  const keyword = ref('')
  const response = ref<TraceabilityMapResponse | null>(null)

  const nodes = computed(() => response.value?.nodes || [])
  const edges = computed(() => response.value?.edges || [])
  const codeTree = computed(() => response.value?.codeTree || [])
  const nodeById = computed(() => new Map(nodes.value.map((node) => [node.id, node])))
  const incomingByNode = computed(() => groupEdges(edges.value, 'target'))
  const outgoingByNode = computed(() => groupEdges(edges.value, 'source'))

  const requirements = computed(() => applyAssetFilters(nodes.value.filter((node) => node.kind === 'REQUIREMENT')))
  const testcases = computed(() => applyAssetFilters(nodes.value.filter((node) => node.kind === 'TESTCASE')))

  const linkedNodeIds = computed(() => {
    const ids = new Set<string>()
    filteredEdges.value.forEach((edge) => {
      ids.add(edge.source)
      ids.add(edge.target)
      const sourceParent = nodeById.value.get(edge.source)?.parentId
      const targetParent = nodeById.value.get(edge.target)?.parentId
      if (sourceParent) ids.add(sourceParent)
      if (targetParent) ids.add(targetParent)
    })
    return ids
  })

  const filteredEdges = computed(() => {
    const kw = normalize(keyword.value)
    return edges.value.filter((edge) => {
      if (!edgePassesFilter(edge)) return false
      if (!kw) return true
      const source = nodeById.value.get(edge.source)
      const target = nodeById.value.get(edge.target)
      return searchable(source).includes(kw) || searchable(target).includes(kw) || normalize(edge.generationMethod).includes(kw)
    })
  })

  const selectedNode = computed(() => focusId.value ? nodeById.value.get(focusId.value) || null : null)
  const focusedGroups = computed(() => buildFocusedGroups(focusId.value, filteredEdges.value, nodeById.value))

  async function load(options: { baselineId?: string; focusId?: string; view?: 'trace' | 'calls' | 'full' } = {}) {
    loading.value = true
    error.value = ''
    try {
      const data = await fetchTraceabilityMap(projectId(), {
        baselineId: options.baselineId ?? activeBaselineId.value,
        focusId: options.focusId ?? focusId.value,
        direction: 'BOTH',
        depth: depth.value,
        includeStatic: true,
        includeDynamic: true,
        includeAiCalls: includeAiCalls.value,
        view: options.view || 'full',
      })
      response.value = data
      activeBaselineId.value = data.baseline.id
      if (options.focusId !== undefined) {
        focusId.value = options.focusId
      }
    } catch (err) {
      error.value = err instanceof Error ? err.message : '加载追溯地图失败'
    } finally {
      loading.value = false
    }
  }

  function select(id: string) {
    focusId.value = id
  }

  function edgePassesFilter(edge: TraceabilityEdge) {
    if (filter.value === 'ALL') return true
    if (filter.value === 'DYNAMIC_CONFIRMED') return edge.callEvidence === 'DYNAMIC_CONFIRMED' || edge.evidenceType === 'EXECUTION_TRACE'
    if (filter.value === 'STATIC_BRIDGED') return edge.callEvidence === 'STATIC_BRIDGED'
    if (filter.value === 'PENDING_REVIEW') return edge.reviewStatus === 'PENDING'
    if (filter.value === 'COMPLETE') return edge.relation === 'VERIFIED_BY' || edge.relation === 'COVERS' || edge.relation === 'IMPLEMENTED_BY'
    if (filter.value === 'BROKEN') return true
    return true
  }

  function applyAssetFilters(items: TraceabilityNode[]) {
    const kw = normalize(keyword.value)
    return items.filter((node) => {
      if (filter.value === 'BROKEN') {
        const out = outgoingByNode.value.get(node.id) || []
        const incoming = incomingByNode.value.get(node.id) || []
        if (node.kind === 'REQUIREMENT' && out.some((edge) => edge.relation === 'VERIFIED_BY')) return false
        if (node.kind === 'TESTCASE' && out.some((edge) => edge.relation === 'COVERS') && incoming.some((edge) => edge.relation === 'VERIFIED_BY')) return false
      }
      if (!kw) return true
      return searchable(node).includes(kw)
    })
  }

  function linkCount(nodeId: string) {
    return filteredEdges.value.filter((edge) => edge.source === nodeId || edge.target === nodeId).length
  }

  return {
    loading,
    error,
    activeBaselineId,
    focusId,
    depth,
    includeAiCalls,
    filter,
    keyword,
    response,
    nodes,
    edges,
    filteredEdges,
    codeTree,
    nodeById,
    incomingByNode,
    outgoingByNode,
    requirements,
    testcases,
    linkedNodeIds,
    selectedNode,
    focusedGroups,
    load,
    select,
    linkCount,
  }
}

function groupEdges(edges: TraceabilityEdge[], key: 'source' | 'target') {
  const map = new Map<string, TraceabilityEdge[]>()
  edges.forEach((edge) => map.set(edge[key], [...(map.get(edge[key]) || []), edge]))
  return map
}

function buildFocusedGroups(id: string, edges: TraceabilityEdge[], nodes: Map<string, TraceabilityNode>) {
  if (!id) return []
  const groups: Array<{ key: string; title: string; relation: TraceRelation; tone: string; edges: TraceabilityEdge[]; pick: 'source' | 'target' }> = [
    { key: 'req-tc-out', title: '需求验证用例', relation: 'VERIFIED_BY', tone: 'tc', edges: [], pick: 'target' },
    { key: 'tc-req-in', title: '上游需求', relation: 'VERIFIED_BY', tone: 'req', edges: [], pick: 'source' },
    { key: 'tc-code-out', title: '测试覆盖代码', relation: 'COVERS', tone: 'code', edges: [], pick: 'target' },
    { key: 'code-tc-in', title: '覆盖该代码的用例', relation: 'COVERS', tone: 'tc', edges: [], pick: 'source' },
    { key: 'req-code-out', title: '需求直接实现', relation: 'IMPLEMENTED_BY', tone: 'code', edges: [], pick: 'target' },
    { key: 'code-req-in', title: '实现的需求', relation: 'IMPLEMENTED_BY', tone: 'req', edges: [], pick: 'source' },
    { key: 'call-out', title: '调用下游代码', relation: 'CALLS', tone: 'code', edges: [], pick: 'target' },
    { key: 'call-in', title: '上游调用者', relation: 'CALLS', tone: 'code', edges: [], pick: 'source' },
  ]
  edges.forEach((edge) => {
    groups.forEach((group) => {
      if (edge.relation !== group.relation) return
      if (group.pick === 'target' && edge.source === id) group.edges.push(edge)
      if (group.pick === 'source' && edge.target === id) group.edges.push(edge)
    })
  })
  return groups
    .filter((group) => group.edges.length)
    .map((group) => ({
      ...group,
      items: group.edges.map((edge) => {
        const nodeId = edge[group.pick]
        return { node: nodes.get(nodeId), edge }
      }),
    }))
}

function searchable(node?: TraceabilityNode) {
  if (!node) return ''
  return normalize([
    node.id,
    node.label,
    node.description,
    node.locator,
    node.symbol,
    node.language,
    JSON.stringify(node.metadata || {}),
  ].join(' '))
}

function normalize(value?: string) {
  return String(value || '').trim().toLowerCase()
}
