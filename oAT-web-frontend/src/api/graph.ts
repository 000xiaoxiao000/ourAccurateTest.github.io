import { apiGetRaw, apiPostRaw } from './http'

// ── Shared graph fact types ───────────────────────────────────────────────────

export type SnapshotKind =
  | 'STATIC' | 'STATIC_CFG' | 'STATIC_DEPENDENCY'
  | 'RUNTIME' | 'RUNTIME_BRANCH' | 'RUNTIME_TRACE' | 'TRACEABILITY'

export type GraphNodeKind =
  | 'REQUIREMENT' | 'ACCEPTANCE_CRITERION' | 'TESTCASE' | 'TEST_STEP' | 'TEST_EXECUTION'
  | 'SOURCE_FILE' | 'TYPE' | 'METHOD' | 'FIELD' | 'ENDPOINT' | 'CONFIG' | 'SQL_STATEMENT'
  | 'BASIC_BLOCK' | 'DECISION' | 'BRANCH' | 'RUNTIME_SPAN' | 'COVERAGE_UNIT'

export type GraphEdgeType =
  | 'HAS_AC' | 'VERIFIED_BY' | 'EXECUTED_AS' | 'IMPLEMENTED_BY' | 'EXERCISES' | 'TOUCHED' | 'COVERED'
  | 'DECLARES' | 'CONTAINS' | 'IMPORTS' | 'EXTENDS' | 'IMPLEMENTS' | 'INJECTS' | 'CALLS_STATIC'
  | 'CALLS_RUNTIME' | 'OVERRIDES' | 'READS' | 'WRITES' | 'ROUTES_TO' | 'QUERIES' | 'CONFIGURES'
  | 'PUBLISHES' | 'CONSUMES' | 'NORMAL' | 'TRUE' | 'FALSE' | 'CASE' | 'DEFAULT' | 'LOOP_BACK'
  | 'THROW' | 'CATCH' | 'FINALLY' | 'RETURN'

export type FusionState = 'REACHABLE_NOT_EXECUTED' | 'EXECUTED_CONFIRMED' | 'NOT_OBSERVABLE'

export interface GraphSnapshot {
  id: string
  projectId: string
  baselineId: string
  repositoryUrl?: string
  sourceCommit?: string
  kind: SnapshotKind
  analyzerVersion: string
  inputHash: string
  status: string
  attributes?: Record<string, unknown>
}

export interface GraphNode {
  id: string
  snapshotId: string
  baselineId: string
  projectId: string
  kind: GraphNodeKind
  stableSymbolId?: string
  logicalSymbolId?: string
  locator?: string
  displayName: string
  contentHash?: string
  attributes?: Record<string, unknown>
}

export interface GraphEdge {
  id: string
  snapshotId: string
  baselineId: string
  projectId: string
  sourceNodeId: string
  targetNodeId: string
  type: GraphEdgeType
  evidenceKind: string
  evidenceLevel: string
  confidence: number
  executionId?: string
  evidenceLocator?: string
  attributes?: Record<string, unknown>
}

export interface GraphAggregate {
  id: string
  baselineId: string
  projectId: string
  kind: string
  subjectId: string
  sourceHash: string
  payload: Record<string, unknown>
}

export interface GraphSummary {
  staticReady: boolean
  runtimeReady: boolean
  runtimeTraceReady: boolean
  traceabilityReady: boolean
  fusionState: string
  snapshots: GraphSnapshot[]
}

export interface GraphView {
  snapshot?: GraphSnapshot
  nodes: GraphNode[]
  edges: GraphEdge[]
  nodesClipped: boolean
  edgesClipped: boolean
  depth: number
  maxNodes: number
  maxEdges: number
  summary: GraphSummary
  clipReasons: string[]
  totalActiveNodes: number
  edgesInScope: number
  expandHint?: string
}

// ── Projection responses ──────────────────────────────────────────────────────

export interface StaticProjectionResponse { snapshotId: string; nodeCount: number; edgeCount: number }
export interface RuntimeProjectionResponse { snapshotId: string; executionId: string; nodeCount: number; edgeCount: number }
export interface TraceabilityProjectionResponse { snapshotId: string; nodeCount: number; edgeCount: number }

// ── Fusion / assertion / read-model / incremental / diff ───────────────────────

export interface FusionNode {
  nodeId: string
  stableSymbolId?: string
  displayName: string
  locator?: string
  fusionState: FusionState
}

export interface FusionView {
  nodes: FusionNode[]
  totalMethods: number
  executedConfirmed: number
  reachableNotExecuted: number
  notObservable: number
  clipped: boolean
  clipReason?: string
}

export interface AssertionConsistencyResult {
  criterionId: string
  acKey: string
  verdict: 'ASSERTION_ALIGNED' | 'SUSPECTED_FALSE_PASS'
  assertionOverlap: number
  bestTestcaseKey?: string
}

export interface IncrementalRecomputeResult {
  affectedSymbols: string[]
  affectedNodeCount: number
  affectedAcIds: string[]
  affectedTestcaseIds: string[]
  staleAggregates: number
}

export type DiffChangeType =
  | 'REQUIREMENT' | 'TESTCASE' | 'CODE' | 'CONFIG_SQL_API'
  | 'COVERAGE' | 'EXECUTION_TRACE' | 'PROMPT_MODEL_RULE' | 'ANALYZER_UPGRADE'

export interface DiffInvalidationResult {
  changeType: string
  invalidatedSnapshotKinds: string[]
  invalidatedAggregateKinds: string[]
  baselineMarkedStale: boolean
}

export interface BaselineLineageResult { supersededBaselineId: string; supersededByBaselineId: string }

export interface ReadModelRebuildResult {
  acCoverageRows: number
  symbolProtectionRows: number
  hotCallChainRows: number
  uncoveredUnitRows: number
  impactSummaryRows: number
  qualityGateSummaryRows: number
}

export type ReadModelKind =
  | 'RM_AC_COVERAGE_SUMMARY' | 'RM_SYMBOL_TEST_PROTECTION'
  | 'RM_HOT_CALL_CHAIN' | 'RM_UNCOVERED_UNITS'
  | 'RM_IMPACT_SUMMARY' | 'RM_QUALITY_GATE_SUMMARY'

export interface AcEvidenceDelta {
  criterionId: string
  acKey: string
  changeType: 'ADDED' | 'REMOVED' | 'EVIDENCE_CHANGED'
  beforeEvidence: string
  afterEvidence: string
}

export interface FusionStateDelta {
  symbol: string
  beforeState: string
  afterState: string
}

export interface BaselineComparisonResult {
  baseBaselineId: string
  targetBaselineId: string
  acDeltas: AcEvidenceDelta[]
  fusionDeltas: FusionStateDelta[]
}

// ── API functions (map endpoints return raw JSON) ──────────────────────────────

const mapBase = (projectId: string) => `/api/projects/${projectId}/map/graph`

export function fetchGraphView(projectId: string, query: {
  baselineId: string; focusId?: string; depth?: number; maxNodes?: number; maxEdges?: number
}) {
  const params = new URLSearchParams()
  params.set('baselineId', query.baselineId)
  if (query.focusId) params.set('focusId', query.focusId)
  if (query.depth) params.set('depth', String(query.depth))
  if (query.maxNodes) params.set('maxNodes', String(query.maxNodes))
  if (query.maxEdges) params.set('maxEdges', String(query.maxEdges))
  return apiGetRaw<GraphView>(`${mapBase(projectId)}?${params.toString()}`)
}

export function fetchGraphFocusCandidates(projectId: string, baselineId: string, limit?: number) {
  const params = new URLSearchParams()
  params.set('baselineId', baselineId)
  if (limit) params.set('limit', String(limit))
  return apiGetRaw<GraphNode[]>(`${mapBase(projectId)}/focus-candidates?${params.toString()}`)
}

export function projectControlFlowGraph(projectId: string, baselineId: string) {
  return apiPostRaw<StaticProjectionResponse>(`${mapBase(projectId)}/control-flow-project?baselineId=${baselineId}`)
}

export function projectStaticDependencyGraph(projectId: string, baselineId: string) {
  return apiPostRaw<StaticProjectionResponse>(`${mapBase(projectId)}/static-dependency-project?baselineId=${baselineId}`)
}

export function projectBranchCoverageGraph(projectId: string, baselineId: string) {
  return apiPostRaw<RuntimeProjectionResponse>(`${mapBase(projectId)}/branch-coverage-project?baselineId=${baselineId}`)
}

export function projectTestExecutionGraph(projectId: string, baselineId: string) {
  return apiPostRaw<TraceabilityProjectionResponse>(`${mapBase(projectId)}/test-execution-project?baselineId=${baselineId}`)
}

export function fetchFusionView(projectId: string, baselineId: string, maxNodes?: number) {
  const suffix = maxNodes ? `&maxNodes=${maxNodes}` : ''
  return apiGetRaw<FusionView>(`${mapBase(projectId)}/fusion-view?baselineId=${baselineId}${suffix}`)
}

export function evaluateAssertionConsistency(projectId: string, baselineId: string) {
  return apiPostRaw<AssertionConsistencyResult[]>(`${mapBase(projectId)}/assertion-consistency?baselineId=${baselineId}`)
}

export function fetchAssertionConsistency(projectId: string, baselineId: string) {
  return apiGetRaw<GraphAggregate[]>(`${mapBase(projectId)}/assertion-consistency?baselineId=${baselineId}`)
}

export function incrementalRecompute(projectId: string, baselineId: string, changedSymbols: string[]) {
  return apiPostRaw<IncrementalRecomputeResult>(
    `${mapBase(projectId)}/incremental-recompute?baselineId=${baselineId}`,
    JSON.stringify(changedSymbols), 'application/json')
}

export function applyDiffInvalidation(projectId: string, baselineId: string, changeType: DiffChangeType) {
  return apiPostRaw<DiffInvalidationResult>(
    `${mapBase(projectId)}/diff-invalidation?baselineId=${baselineId}&changeType=${changeType}`)
}

export function recordBaselineSuccession(projectId: string, predecessorBaselineId: string, successorBaselineId: string) {
  return apiPostRaw<BaselineLineageResult>(
    `${mapBase(projectId)}/baseline-succession?predecessorBaselineId=${predecessorBaselineId}&successorBaselineId=${successorBaselineId}`)
}

export function fetchBaselineLineage(projectId: string, baselineId: string) {
  return apiGetRaw<GraphAggregate[]>(`${mapBase(projectId)}/baseline-lineage?baselineId=${baselineId}`)
}

export function rebuildReadModels(projectId: string, baselineId: string) {
  return apiPostRaw<ReadModelRebuildResult>(`${mapBase(projectId)}/read-models/rebuild?baselineId=${baselineId}`)
}

export function fetchReadModel(projectId: string, baselineId: string, kind: ReadModelKind) {
  return apiGetRaw<GraphAggregate[]>(`${mapBase(projectId)}/read-models?baselineId=${baselineId}&kind=${kind}`)
}

export function projectStaticGraph(projectId: string, baselineId: string) {
  return apiPostRaw<StaticProjectionResponse>(`${mapBase(projectId)}/static-project?baselineId=${baselineId}`)
}

export function projectRuntimeCoverageGraph(projectId: string, baselineId: string) {
  return apiPostRaw<RuntimeProjectionResponse>(`${mapBase(projectId)}/runtime-coverage-project?baselineId=${baselineId}`)
}

export function projectTraceabilityGraph(projectId: string, baselineId: string) {
  return apiPostRaw<TraceabilityProjectionResponse>(`${mapBase(projectId)}/traceability-project?baselineId=${baselineId}`)
}

export function compareBaselines(projectId: string, baseBaselineId: string, targetBaselineId: string) {
  return apiGetRaw<BaselineComparisonResult>(
    `${mapBase(projectId)}/baseline-comparison?baseBaselineId=${baseBaselineId}&targetBaselineId=${targetBaselineId}`)
}
