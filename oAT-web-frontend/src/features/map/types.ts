export interface RelationNode {
  id: string
  label?: string
  type?: string
  description?: string
  meta?: string[]
  raw?: Record<string, unknown>
  classes?: string[]
}

export interface RelationEdge {
  id: string
  source: string
  target: string
  label?: string
  action?: string
  sourceLabel?: string
  targetLabel?: string
}

export interface RelationContextAction {
  id: string
  label: string
  target?: 'canvas' | 'node' | 'app' | 'table' | 'remote' | 'code'
  shortcut?: string
  danger?: boolean
  disabled?: boolean
}

export interface GraphNode extends RelationNode {
  x: number
  y: number
  radius: number
  shortLabel: string
  metric: string
}
