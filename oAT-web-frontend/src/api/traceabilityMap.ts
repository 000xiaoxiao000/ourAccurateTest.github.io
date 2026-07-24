import { apiGetRaw } from './http'
import type { EvidenceLevel, Freshness, ReviewStatus } from './verification'

export type TraceNodeKind = 'REQUIREMENT' | 'TESTCASE' | 'CODE_FILE' | 'CODE_CLASS' | 'CODE_METHOD'
export type TraceRelation = 'VERIFIED_BY' | 'COVERS' | 'IMPLEMENTED_BY' | 'CALLS'
export type TraceEvidenceType = 'DOCUMENT' | 'AI' | 'STATIC_ANALYSIS' | 'COVERAGE' | 'EXECUTION_TRACE' | 'DERIVED'
export type TraceCallEvidence = 'DYNAMIC_CONFIRMED' | 'STATIC_BRIDGED' | 'STATIC_ONLY'
export type TraceEvidenceState = 'NONE' | 'STATIC' | 'DYNAMIC' | 'BOTH'
export type CodeTreeKind = 'DIRECTORY' | 'FILE' | 'CLASS' | 'METHOD'

export interface TraceabilityBaselineInfo {
  id: string
  name?: string
  sourceAppId?: string
  sourceAssetId?: string
  executionAssetId?: string
  coverageAssetId?: string
  repositoryUrl?: string
  sourceBranch?: string
  sourceCommit?: string
  analyzerVersion?: string
  freshness: Freshness
  updateTime?: string
}

export interface TraceabilitySummary {
  requirementCount: number
  testcaseCount: number
  codeCount: number
  codeFileCount?: number
  codeClassCount?: number
  codeMethodCount?: number
  completeChainCount: number
  completeChainRate: number
  staticNodeCount: number
  dynamicNodeCount: number
  brokenRequirementCount: number
  brokenTestcaseCount: number
  dynamicEvidenceCount: number
  staticBridgeCount: number
  clipped: boolean
}

export interface CoverageSummary {
  coveredLines?: number
  totalLines?: number
  lineRate?: number
  coveredBranches?: number
  totalBranches?: number
  branchRate?: number
}

export interface CoverageReportOverview {
  coveredClasses: number
  totalClasses: number
  coveredMethods: number
  totalMethods: number
  coveredBranches: number
  totalBranches: number
  coveredLines: number
  totalLines: number
  totalComplexity: number
}

export interface TraceabilityNode {
  id: string
  kind: TraceNodeKind
  label: string
  description?: string
  locator?: string
  layer: string
  language?: string
  symbol?: string
  parentId?: string
  evidenceState: TraceEvidenceState
  coverage?: CoverageSummary
  metadata?: Record<string, unknown>
}

export interface TraceEdgeEvidence {
  assetId?: string
  traceId?: string
  caseName?: string
  locator?: string
  line?: number
  reason?: string
  metadata?: Record<string, unknown>
}

export interface TraceabilityEdge {
  id: string
  source: string
  target: string
  relation: TraceRelation
  direction: 'FORWARD'
  evidenceType: TraceEvidenceType
  callEvidence?: TraceCallEvidence
  confidence: number
  evidenceLevel: EvidenceLevel
  generationMethod: string
  reviewStatus: ReviewStatus
  evidence: TraceEdgeEvidence[]
}

export interface CodeTreeNode {
  id: string
  kind: CodeTreeKind
  label: string
  path?: string
  parentId?: string
  language?: string
  evidenceState: TraceEvidenceState
  coverage?: CoverageSummary
  children: CodeTreeNode[]
}

export interface TraceabilityMapResponse {
  baseline: TraceabilityBaselineInfo
  summary: TraceabilitySummary
  nodes: TraceabilityNode[]
  edges: TraceabilityEdge[]
  codeTree: CodeTreeNode[]
  codeGraph?: CodeGraphData | null
  coverageOverview?: CoverageReportOverview
  warnings: string[]
}

export interface CodeGraphData {
  dependencies: CodeDependency[]
  controlFlows: ControlFlowStep[]
}

export interface CodeDependency { source: string; target: string; kind: string }
export interface ControlFlowStep { methodId: string; methodLabel: string; kind: string; expression: string; order: number }

export interface TraceabilityMapQuery {
  baselineId?: string
  focusId?: string
  direction?: 'BOTH' | 'UPSTREAM' | 'DOWNSTREAM'
  depth?: number
  includeStatic?: boolean
  includeDynamic?: boolean
  includeAiCalls?: boolean
  view?: 'trace' | 'calls' | 'full'
}

export function fetchTraceabilityMap(projectId: string, query: TraceabilityMapQuery = {}) {
  const params = new URLSearchParams()
  if (query.baselineId) params.set('baselineId', query.baselineId)
  if (query.focusId) params.set('focusId', query.focusId)
  if (query.direction) params.set('direction', query.direction)
  if (query.depth) params.set('depth', String(query.depth))
  if (query.includeStatic !== undefined) params.set('includeStatic', String(query.includeStatic))
  if (query.includeDynamic !== undefined) params.set('includeDynamic', String(query.includeDynamic))
  if (query.includeAiCalls !== undefined) params.set('includeAiCalls', String(query.includeAiCalls))
  if (query.view) params.set('view', query.view)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return apiGetRaw<TraceabilityMapResponse>(`/api/projects/${projectId}/map/traceability${suffix}`)
}
