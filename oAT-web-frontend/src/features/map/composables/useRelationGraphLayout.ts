import { computed, type ComputedRef, type Ref } from 'vue'

import type { GraphNode, RelationEdge, RelationNode } from '@/features/map/types'

export function useRelationGraphLayout(
  nodes: ComputedRef<RelationNode[]>,
  edges: ComputedRef<RelationEdge[]>,
  compact: ComputedRef<boolean>,
  nodePositionOverrides: Ref<Record<string, { x: number; y: number }>>,
) {
  const graphLayoutKind = computed(() => resolveGraphLayoutKind(nodes.value))
  const graphLayoutPlan = computed(() => buildGraphLayout(nodes.value, edges.value, graphLayoutKind.value))
  const graphViewport = computed(() => ({ width: compact.value ? 1080 : 1200, height: compact.value ? 620 : 720 }))
  const graphViewBox = computed(() => `0 0 ${graphViewport.value.width} ${graphViewport.value.height}`)
  const denseGraph = computed(() => nodes.value.length > 70)

  const graphNodes = computed<GraphNode[]>(() => {
    const positions = graphLayoutPlan.value.positions
    return nodes.value.map((node) => {
      const autoPosition = positions.get(node.id) || { x: 80, y: 80 }
      const override = nodePositionOverrides.value[node.id]
      const type = node.type || ''
      const isCode = type.includes('code') || type.includes('class') || type.includes('method')
      return {
        ...node,
        x: override?.x ?? autoPosition.x,
        y: override?.y ?? autoPosition.y,
        radius: isCode ? 18 : type.includes('table') ? 22 : 20,
        shortLabel: shorten(node.label || node.id, denseGraph.value ? 14 : 20),
        metric: Number(node.meta?.join(' ').match(/([0-9]+(?:\.[0-9]+)?)%/)?.[1] || 0) ? `${Number(node.meta?.join(' ').match(/([0-9]+(?:\.[0-9]+)?)%/)?.[1] || 0)}%` : '',
      }
    })
  })

  const graphNodeMap = computed(() => new Map(graphNodes.value.map((node) => [node.id, node])))
  const graphEdges = computed(() => edges.value
    .map((edge) => {
      const source = graphNodeMap.value.get(edge.source)
      const target = graphNodeMap.value.get(edge.target)
      if (!source || !target) return null
      const labelPoint = edgeLabelPoint(source, target)
      return {
        ...edge,
        source,
        target,
        path: edgePath(source, target),
        labelX: labelPoint.x,
        labelY: labelPoint.y,
      }
    })
    .filter((edge): edge is NonNullable<typeof edge> => Boolean(edge)))

  return {
    denseGraph,
    graphEdges,
    graphNodes,
    graphNodeMap,
    graphViewBox,
    graphViewport,
  }
}

export function nodeSearchText(node: RelationNode) {
  return [node.id, node.label, node.type, node.description, ...(node.meta || [])]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}

export function nodeTypeText(node: RelationNode) {
  return `${node.type || ''} ${(node.classes || []).join(' ')}`.toLowerCase()
}

export function isCodeNode(node: RelationNode) {
  const type = nodeTypeText(node)
  return type.includes('code') || type.includes('class') || type.includes('method')
}

export function nodeTone(node: RelationNode) {
  const type = node.type || ''
  if (type.includes('requirement')) return 'requirement'
  if (type.includes('testcase')) return 'testcase'
  if (type.includes('bug') || type.includes('finding')) return 'bug'
  if (type.includes('source')) return 'source'
  if (type.includes('table')) return 'table'
  if (type.includes('code')) return 'code'
  if (type.includes('notice')) return 'notice'
  return 'default'
}

export function edgeTone(edge: Pick<RelationEdge, 'action' | 'label'>) {
  const value = `${edge.action || ''} ${edge.label || ''}`.toLowerCase()
  if (value.includes('bug') || value.includes('finding') || value.includes('critical') || value.includes('high')) return 'delete'
  if (value.includes('源码') || value.includes('source') || value.includes('implement')) return 'source'
  if (value.includes('用例') || value.includes('testcase') || value.includes('cover')) return 'testcase'
  if (value.includes('需求') || value.includes('requirement')) return 'requirement'
  if (value.includes('delete') || value.includes('删')) return 'delete'
  if (value.includes('update') || value.includes('改')) return 'update'
  if (value.includes('insert') || value.includes('增')) return 'insert'
  if (value.includes('select') || value.includes('查')) return 'select'
  if (value.includes('invoke') || value.includes('调用') || value.includes('执行') || value.includes('entry') || value.includes('入口')) return 'invoke'
  return 'default'
}

export function actionText(action?: string) {
  if (!action) return ''
  return action.split(',').map((item) => {
    const value = item.trim().toLowerCase()
    if (value === 'insert') return '增'
    if (value === 'delete') return '删'
    if (value === 'update') return '改'
    if (value === 'select') return '查'
    if (value === 'invoke') return '调用'
    if (value === 'entry' || value === 'start') return '入口'
    if (value === 'covers') return '覆盖'
    if (value === 'implements') return '实现'
    if (value === 'static_match') return '静态匹配'
    return item
  }).filter(Boolean).join(',')
}

function resolveGraphLayoutKind(layoutNodes: RelationNode[]) {
  const codeCount = layoutNodes.filter(isCodeNode).length
  if (layoutNodes.length > 60 || codeCount > Math.max(8, layoutNodes.length * 0.35)) return 'layered'
  if (layoutNodes.some((node) => nodeTypeText(node).includes('table') || nodeTypeText(node).includes('app'))) return 'layered'
  return 'grid'
}

function buildGraphLayout(layoutNodes: RelationNode[], layoutEdges: RelationEdge[], kind: string) {
  if (!layoutNodes.length) {
    return { canvas: { width: 1200, height: 680 }, positions: new Map<string, { x: number; y: number }>() }
  }
  return kind === 'grid' ? buildGridLayout(layoutNodes) : buildLayeredLayout(layoutNodes, layoutEdges)
}

function buildGridLayout(layoutNodes: RelationNode[]) {
  const count = layoutNodes.length
  const columns = Math.max(1, Math.ceil(Math.sqrt(count * 1.6)))
  const rows = Math.ceil(count / columns)
  const spacingX = 160
  const spacingY = 92
  const canvas = {
    width: Math.max(1200, columns * spacingX + 160),
    height: Math.max(680, rows * spacingY + 160),
  }
  const positions = new Map<string, { x: number; y: number }>()
  layoutNodes.forEach((node, index) => {
    const column = index % columns
    const row = Math.floor(index / columns)
    positions.set(node.id, {
      x: 90 + column * spacingX,
      y: 92 + row * spacingY,
    })
  })
  return { canvas, positions }
}

function buildLayeredLayout(layoutNodes: RelationNode[], layoutEdges: RelationEdge[]) {
  const nodeIds = new Set(layoutNodes.map((node) => node.id))
  const incoming = new Map<string, number>()
  const children = new Map<string, string[]>()
  layoutNodes.forEach((node) => {
    incoming.set(node.id, 0)
    children.set(node.id, [])
  })
  layoutEdges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) return
    incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1)
    children.get(edge.source)?.push(edge.target)
  })

  const ranks = new Map<string, number>()
  const roots = layoutNodes.filter((node) => (incoming.get(node.id) || 0) === 0)
  const queue = (roots.length ? roots : layoutNodes.slice(0, Math.min(4, layoutNodes.length))).map((node) => node.id)
  queue.forEach((id) => ranks.set(id, 0))
  for (let index = 0; index < queue.length; index += 1) {
    const id = queue[index]
    const nextRank = (ranks.get(id) || 0) + 1
    ;(children.get(id) || []).forEach((childId) => {
      if ((ranks.get(childId) ?? -1) < nextRank) {
        ranks.set(childId, nextRank)
        queue.push(childId)
      }
    })
  }

  layoutNodes.forEach((node, index) => {
    if (!ranks.has(node.id)) ranks.set(node.id, Math.floor(index / 10))
  })

  const buckets = new Map<number, RelationNode[]>()
  layoutNodes.forEach((node) => {
    const rank = ranks.get(node.id) || 0
    const list = buckets.get(rank) || []
    list.push(node)
    buckets.set(rank, list)
  })

  const mostlyCode = layoutNodes.filter(isCodeNode).length > layoutNodes.length * 0.5
  if (mostlyCode && layoutNodes.length > 10) {
    return buildWrappedFlowLayout(layoutNodes, layoutEdges)
  }
  const maxRowsPerColumn = mostlyCode ? (layoutNodes.length > 80 ? 5 : 4) : (layoutNodes.length > 80 ? 7 : 6)
  const maxColumnsPerBand = mostlyCode ? (layoutNodes.length > 80 ? 5 : 6) : (layoutNodes.length > 80 ? 6 : 7)
  const spacingX = mostlyCode ? 230 : 220
  const spacingY = mostlyCode ? 118 : 108
  const bandGap = mostlyCode ? 132 : 118
  const left = 130
  const top = 118
  const positions = new Map<string, { x: number; y: number }>()
  let visualColumn = 0

  Array.from(buckets.keys()).sort((a, b) => a - b).forEach((rank) => {
    const bucket = buckets.get(rank) || []
    const sortedBucket = bucket.slice().sort((leftNode, rightNode) => nodeSortWeight(leftNode) - nodeSortWeight(rightNode) || (leftNode.label || leftNode.id).localeCompare(rightNode.label || rightNode.id))
    const chunkCount = Math.max(1, Math.ceil(sortedBucket.length / maxRowsPerColumn))
    for (let chunkIndex = 0; chunkIndex < chunkCount; chunkIndex += 1) {
      const chunk = sortedBucket.slice(chunkIndex * maxRowsPerColumn, (chunkIndex + 1) * maxRowsPerColumn)
      const band = Math.floor(visualColumn / maxColumnsPerBand)
      const column = visualColumn % maxColumnsPerBand
      const x = left + column * spacingX
      const bandTop = top + band * (maxRowsPerColumn * spacingY + bandGap)
      const verticalOffset = Math.max(0, maxRowsPerColumn - chunk.length) * spacingY * 0.5
      chunk.forEach((node, row) => {
        positions.set(node.id, {
          x,
          y: bandTop + verticalOffset + row * spacingY,
        })
      })
      visualColumn += 1
    }
  })

  const usedColumns = Math.min(maxColumnsPerBand, Math.max(1, visualColumn))
  const bands = Math.max(1, Math.ceil(visualColumn / maxColumnsPerBand))
  const canvas = {
    width: Math.max(1120, left * 2 + (usedColumns - 1) * spacingX + 160),
    height: Math.max(680, top * 2 + bands * maxRowsPerColumn * spacingY + (bands - 1) * bandGap),
  }
  return { canvas, positions }
}

function buildWrappedFlowLayout(layoutNodes: RelationNode[], layoutEdges: RelationEdge[]) {
  const order = topologicalNodeOrder(layoutNodes, layoutEdges)
  const perRow = layoutNodes.length > 80 ? 8 : layoutNodes.length > 40 ? 7 : 6
  const spacingX = layoutNodes.length > 80 ? 144 : 160
  const spacingY = layoutNodes.length > 80 ? 108 : 118
  const left = 96
  const top = 96
  const rowCount = Math.max(1, Math.ceil(order.length / perRow))
  const usedColumns = Math.min(perRow, order.length)
  const positions = new Map<string, { x: number; y: number }>()

  for (let row = 0; row < rowCount; row += 1) {
    const rowNodes = order.slice(row * perRow, (row + 1) * perRow)
    const displayNodes = row % 2 === 0 ? rowNodes : rowNodes.slice().reverse()
    const rowOffset = Math.max(0, perRow - rowNodes.length) * spacingX * 0.5
    displayNodes.forEach((node, column) => {
      positions.set(node.id, {
        x: left + rowOffset + column * spacingX,
        y: top + row * spacingY,
      })
    })
  }

  const canvas = {
    width: Math.max(1120, left * 2 + Math.max(0, usedColumns - 1) * spacingX + 96),
    height: Math.max(680, top * 2 + Math.max(0, rowCount - 1) * spacingY + 96),
  }
  return { canvas, positions }
}

function topologicalNodeOrder(layoutNodes: RelationNode[], layoutEdges: RelationEdge[]) {
  const nodeMap = new Map(layoutNodes.map((node) => [node.id, node]))
  const indegree = new Map(layoutNodes.map((node) => [node.id, 0]))
  const children = new Map(layoutNodes.map((node) => [node.id, [] as string[]]))
  layoutEdges.forEach((edge) => {
    if (!nodeMap.has(edge.source) || !nodeMap.has(edge.target)) return
    indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1)
    children.get(edge.source)?.push(edge.target)
  })
  children.forEach((items) => items.sort((leftId, rightId) => {
    const leftNode = nodeMap.get(leftId)!
    const rightNode = nodeMap.get(rightId)!
    return nodeSortWeight(leftNode) - nodeSortWeight(rightNode) || nodeLabel(leftNode).localeCompare(nodeLabel(rightNode))
  }))

  const queue = layoutNodes
    .filter((node) => (indegree.get(node.id) || 0) === 0)
    .sort((leftNode, rightNode) => nodeSortWeight(leftNode) - nodeSortWeight(rightNode) || nodeLabel(leftNode).localeCompare(nodeLabel(rightNode)))
  const ordered: RelationNode[] = []
  const visited = new Set<string>()

  for (let index = 0; index < queue.length; index += 1) {
    const node = queue[index]
    if (!node || visited.has(node.id)) continue
    visited.add(node.id)
    ordered.push(node)
    ;(children.get(node.id) || []).forEach((childId) => {
      indegree.set(childId, Math.max(0, (indegree.get(childId) || 0) - 1))
      if ((indegree.get(childId) || 0) === 0) {
        const child = nodeMap.get(childId)
        if (child) queue.push(child)
      }
    })
  }

  layoutNodes
    .filter((node) => !visited.has(node.id))
    .sort((leftNode, rightNode) => nodeSortWeight(leftNode) - nodeSortWeight(rightNode) || nodeLabel(leftNode).localeCompare(nodeLabel(rightNode)))
    .forEach((node) => ordered.push(node))

  return ordered
}

function nodeLabel(node: RelationNode) {
  return node.label || node.id
}

function nodeSortWeight(node: RelationNode) {
  const type = nodeTypeText(node)
  if (type.includes('entry')) return 0
  if (type.includes('controller')) return 1
  if (type.includes('service')) return 2
  if (type.includes('code')) return 3
  return 4
}

function shorten(value: string, limit: number) {
  return value.length > limit ? `${value.slice(0, limit - 1)}...` : value
}

function edgePath(source: GraphNode, target: GraphNode) {
  const dx = target.x - source.x
  const dy = target.y - source.y
  const curve = Math.max(50, Math.min(180, Math.abs(dx) * 0.45 + Math.abs(dy) * 0.12))
  if (Math.abs(dx) >= Math.abs(dy)) {
    const direction = dx >= 0 ? 1 : -1
    return `M ${source.x} ${source.y} C ${source.x + curve * direction} ${source.y}, ${target.x - curve * direction} ${target.y}, ${target.x} ${target.y}`
  }
  const direction = dy >= 0 ? 1 : -1
  return `M ${source.x} ${source.y} C ${source.x} ${source.y + curve * direction}, ${target.x} ${target.y - curve * direction}, ${target.x} ${target.y}`
}

function edgeLabelPoint(source: GraphNode, target: GraphNode) {
  return {
    x: (source.x + target.x) / 2,
    y: (source.y + target.y) / 2 - 10,
  }
}
