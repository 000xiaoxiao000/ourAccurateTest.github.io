import { apiDelete, apiGet, apiPost, apiPut } from './http'

export type AssetType = 'REQUIREMENT' | 'TESTCASE' | 'SOURCE' | 'EXECUTION' | 'COVERAGE' | 'DEFECT'
export type SourceType = 'FILE' | 'GIT' | 'API' | 'AGENT' | 'PASTE'
export type Freshness = 'LIVE' | 'SNAPSHOT' | 'MANUAL' | 'STALE' | 'UNKNOWN'
export type ReviewStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'WRITTEN_BACK' | 'STALE' | 'EXEMPTED'
export type Verdict = 'SATISFIED' | 'STATICALLY_CONSISTENT' | 'PARTIAL' | 'NOT_SATISFIED' | 'AMBIGUOUS' | 'NOT_VERIFIABLE' | 'EXEMPTED' | 'STALE'
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'
export type Perspective = 'PRODUCT' | 'TEST' | 'DEVELOPMENT' | 'CROSS'
export type EvidenceLevel = 'E0' | 'E1' | 'E2' | 'E3' | 'E4'
export type GateVerdict = 'PASSED' | 'FAILED' | 'WARNING' | 'EXEMPTED'
export type ImpactLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE'

// ── Asset / Baseline ─────────────────────────────────────────────────────────

export interface VerificationAsset {
  id: string
  projectId: string
  assetType: AssetType
  sourceType: SourceType
  externalId?: string
  externalUrl?: string
  sourceVersion?: string
  fileName?: string
  contentHash: string
  storageType?: string
  storageKey?: string
  contentSize?: number
  contentPreview?: string
  freshness: Freshness
  capturedAt: string
  metadata?: Record<string, unknown>
  aiGenerated?: boolean
}

export interface VerificationBaseline {
  id: string
  projectId: string
  name: string
  requirementAssetId?: string
  testcaseAssetId?: string
  sourceAssetId?: string
  executionAssetId?: string
  coverageAssetId?: string
  sourceAppId?: string
  repositoryUrl?: string
  sourceBranch?: string
  sourceCommit?: string
  analyzerVersion: string
  status: string
  freshness: Freshness
  createTime: string
  updateTime: string
  /** 基线归属范围：SYSTEM=绑定系统（新建必选）；LEGACY_PROJECT=遗留项目级（只读） */
  scope?: string
}

export interface AcceptanceCriterion {
  id: string
  requirementKey: string
  acKey: string
  title?: string
  content: string
  sourceLocator?: string
  priority?: string
  testable: boolean
  ambiguity: boolean
  confidence: number
}

export interface TestcaseProjection {
  id: string
  externalKey: string
  title: string
  preconditions?: string
  steps?: string
  testData?: string
  expected?: string
  requirementRefs?: string
  sourceLocator?: string
}

export interface TraceLink {
  id: string
  sourceType: string
  sourceId: string
  targetType: string
  targetId: string
  relationType: string
  generationMethod: string
  confidence: number
  evidenceLevel: EvidenceLevel
  reviewStatus: ReviewStatus
  evidence?: Record<string, unknown>
}

export interface VerificationFinding {
  id: string
  acId?: string
  findingType: string
  perspective: Perspective
  severity: Severity
  title: string
  description: string
  suggestion?: string
  confidence: number
  evidenceLevel: EvidenceLevel
  verdict: Verdict
  reviewStatus: ReviewStatus
  evidence?: Array<Record<string, unknown>>
  externalWorkItemUrl?: string
  reviewReason?: string
}

export interface VerificationMetrics {
  totalCriteria: number
  coveredCriteria: number
  implementedCriteria: number
  executedCriteria: number
  coveredByRuntimeCriteria: number
  closedLoopCriteria: number
  openFindings: number
  testcaseCoverageRate: number
  implementationCoverageRate: number
  executionEvidenceRate: number
  runtimeCoverageRate: number
  closedLoopRate: number
  staticCodeCount?: number
  dynamicCodeCount?: number
  coverageFileCount?: number
  coveredLines?: number
  totalLines?: number
  lineCoverageRate?: number
  coveredBranches?: number
  totalBranches?: number
  branchCoverageRate?: number
}

export interface BaselineDetail {
  baseline: VerificationBaseline
  requirementAsset?: VerificationAsset
  testcaseAsset?: VerificationAsset
  criteria: AcceptanceCriterion[]
  testcases: TestcaseProjection[]
  traceLinks: TraceLink[]
  findings: VerificationFinding[]
  metrics: VerificationMetrics
}

export interface MatrixRow {
  criterion: AcceptanceCriterion
  testcases: TestcaseProjection[]
  codeLinks: TraceLink[]
  findings: VerificationFinding[]
  verdict: Verdict
  evidenceLevel: EvidenceLevel
}

export interface VerificationOverview {
  requirements: VerificationAsset[]
  testcases: VerificationAsset[]
  sources: VerificationAsset[]
  executions: VerificationAsset[]
  coverages: VerificationAsset[]
  defects: VerificationAsset[]
  baselines: VerificationBaseline[]
}

export interface WriteBackAction {
  id: string
  projectId: string
  baselineId: string
  findingId: string
  connectorType: string
  externalUrl?: string
  status: string
  message?: string
  createdBy?: string
  createTime: string
}

export interface AnalysisJob {
  id: string
  projectId: string
  baselineId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  message?: string
  createdBy?: string
  createTime: string
  updateTime: string
  finishTime?: string
}

// ── Quality Gate ─────────────────────────────────────────────────────────────

export interface QualityGatePolicy {
  id?: string
  projectId?: string
  name: string
  minTestcaseCoverageRate: number
  minImplementationCoverageRate: number
  maxCriticalFindings: number
  maxHighFindings: number
  requireAllAmbiguitiesResolved: boolean
  requireChangeImpactVerified: boolean
  blockOnStaleBaseline: boolean
  createdBy?: string
  createTime?: string
  updateTime?: string
}

export interface GateFailure {
  ruleId: string
  description: string
  actualValue: string
  threshold: string
}

export interface GateExemption {
  id: string
  projectId: string
  baselineId: string
  ruleId: string
  reason: string
  grantedBy?: string
  expiresAt?: string
  createTime: string
}

export interface QualityGateResult {
  id: string
  projectId: string
  baselineId: string
  policyId: string
  verdict: GateVerdict
  failures: GateFailure[]
  activeExemptions: GateExemption[]
  metrics?: VerificationMetrics
  evaluatedBy?: string
  evaluatedAt: string
}

// ── Change Impact ─────────────────────────────────────────────────────────────

export interface ImpactedAc {
  acId: string
  requirementKey: string
  acKey: string
  title?: string
  impactLevel: ImpactLevel
  reason: string
  affectedTestcaseIds: string[]
  affectedSymbols: string[]
}

export interface ImpactedTestcase {
  testcaseId: string
  externalKey: string
  title: string
  impactLevel: ImpactLevel
  reason: string
}

export interface OrphanItem {
  itemId: string
  orphanType: string
  itemType: string
  title: string
  description: string
}

export interface ChangeImpactReport {
  id: string
  projectId: string
  baselineId: string
  changeDescription: string
  impactedCriteria: ImpactedAc[]
  impactedTestcases: ImpactedTestcase[]
  orphans: OrphanItem[]
  createdBy?: string
  createTime: string
}

// ── API functions ─────────────────────────────────────────────────────────────

const base = (projectId: string) => `/api/projects/${projectId}/verification`

export function fetchVerificationOverview(projectId: string) {
  return apiGet<VerificationOverview>(`${base(projectId)}/overview`)
}

export function importVerificationAsset(projectId: string, assetType: AssetType, file?: File, content?: string, sourceVersion?: string) {
  const form = new FormData()
  form.append('assetType', assetType)
  form.append('sourceType', file ? 'FILE' : 'PASTE')
  if (file) form.append('file', file)
  if (content) form.append('content', content)
  if (sourceVersion) form.append('sourceVersion', sourceVersion)
  return apiPost<VerificationAsset>(`${base(projectId)}/assets/import`, form)
}

export function importGitSourceAsset(projectId: string, payload: {
  appId?: string; repositoryUrl?: string; username?: string; password?: string
  branch?: string; commit?: string; maxFiles?: number; maxBytes?: number
}) {
  return apiPost<VerificationAsset>(`${base(projectId)}/assets/git-source`, JSON.stringify(payload), 'application/json')
}

export function syncConnectorVerificationAsset(projectId: string, payload: {
  assetType: AssetType
  connectorType?: string
  connectorId?: string
  scopeRef: string
  baseUrl?: string
  externalId?: string
  externalUrl?: string
  sourceVersion?: string
  fieldMapping?: Record<string, unknown>
}) {
  return apiPost<VerificationAsset>(`${base(projectId)}/assets/connector-sync`, JSON.stringify(payload), 'application/json')
}

export interface ConnectorOption {
  connectorId: string
  type: string
  displayName?: string
  enabled?: boolean
}

/** 列出当前租户在 ovanth 已配置的连接器（权威来源），返回真实 connectorId。 */
export function listConnectors(projectId: string) {
  return apiGet<ConnectorOption[]>(`/api/projects/${projectId}/connectors/list`)
}

export function updateVerificationAsset(projectId: string, assetId: string, payload: {
  fileName?: string; content?: string; externalId?: string; externalUrl?: string; sourceVersion?: string
}) {
  return apiPut<VerificationAsset>(`${base(projectId)}/assets/${assetId}`, JSON.stringify(payload), 'application/json')
}

export function deleteVerificationAsset(projectId: string, assetId: string) {
  return apiDelete<string>(`${base(projectId)}/assets/${assetId}`)
}

export function createVerificationBaseline(projectId: string, payload: {
  name: string; requirementAssetId?: string; testcaseAssetId?: string
  sourceAssetId?: string; executionAssetId?: string; coverageAssetId?: string
  sourceAppId?: string; repositoryUrl?: string; sourceBranch?: string; sourceCommit?: string
}) {
  return apiPost<VerificationBaseline>(`${base(projectId)}/baselines`, JSON.stringify(payload), 'application/json')
}

export function updateVerificationBaseline(projectId: string, baselineId: string, payload: {
  name: string; requirementAssetId?: string; testcaseAssetId?: string
  sourceAssetId?: string; executionAssetId?: string; coverageAssetId?: string
  sourceAppId?: string; repositoryUrl?: string; sourceBranch?: string; sourceCommit?: string
}) {
  return apiPut<VerificationBaseline>(`${base(projectId)}/baselines/${baselineId}`, JSON.stringify(payload), 'application/json')
}

export function deleteVerificationBaseline(projectId: string, baselineId: string) {
  return apiDelete<string>(`${base(projectId)}/baselines/${baselineId}`)
}

export function fetchBaselineDetail(projectId: string, baselineId: string) {
  return apiGet<BaselineDetail>(`${base(projectId)}/baselines/${baselineId}`)
}

export function analyzeBaseline(projectId: string, baselineId: string) {
  return apiPost<AnalysisJob>(`${base(projectId)}/baselines/${baselineId}/analyze`)
}

export function fetchAnalysisJob(projectId: string, jobId: string) {
  return apiGet<AnalysisJob>(`${base(projectId)}/analysis-jobs/${jobId}`)
}

export function fetchLatestAnalysisJob(projectId: string, baselineId: string) {
  return apiGet<AnalysisJob>(`${base(projectId)}/baselines/${baselineId}/analysis-jobs/latest`)
}

export function fetchTraceMatrix(projectId: string, baselineId: string) {
  return apiGet<MatrixRow[]>(`${base(projectId)}/baselines/${baselineId}/matrix`)
}

export function reviewVerificationFinding(projectId: string, findingId: string, payload: {
  status: ReviewStatus; reason?: string; externalWorkItemUrl?: string
}) {
  return apiPost<string>(`${base(projectId)}/findings/${findingId}/review`, JSON.stringify(payload), 'application/json')
}

export function writeBackVerificationFinding(projectId: string, findingId: string, payload: {
  connectorType?: string; externalUrl?: string; message?: string; targetRole?: Perspective
}) {
  return apiPost<WriteBackAction>(`${base(projectId)}/findings/${findingId}/writeback`, JSON.stringify(payload), 'application/json')
}

export function fetchWriteBackActions(projectId: string, baselineId: string) {
  return apiGet<WriteBackAction[]>(`${base(projectId)}/baselines/${baselineId}/writebacks`)
}

export function reviewTraceLink(projectId: string, traceLinkId: string, status: ReviewStatus) {
  return apiPost<string>(`${base(projectId)}/trace-links/${traceLinkId}/review`, JSON.stringify({ status }), 'application/json')
}

export function markVerificationBaselineStale(projectId: string, baselineId: string) {
  return apiPost<string>(`${base(projectId)}/baselines/${baselineId}/stale`)
}

// ── Quality Gate API ──────────────────────────────────────────────────────────

export function createQualityGatePolicy(projectId: string, payload: QualityGatePolicy) {
  return apiPost<QualityGatePolicy>(`${base(projectId)}/quality-gate/policies`, JSON.stringify(payload), 'application/json')
}

export function fetchQualityGatePolicies(projectId: string) {
  return apiGet<QualityGatePolicy[]>(`${base(projectId)}/quality-gate/policies`)
}

export function evaluateQualityGate(projectId: string, baselineId: string, policyId: string) {
  return apiPost<QualityGateResult>(
    `${base(projectId)}/baselines/${baselineId}/quality-gate/evaluate`,
    JSON.stringify({ policyId }), 'application/json')
}

export function fetchQualityGateResults(projectId: string, baselineId: string) {
  return apiGet<QualityGateResult[]>(`${base(projectId)}/baselines/${baselineId}/quality-gate/results`)
}

export type GateEnforcementMode = 'SHADOW' | 'SOFT' | 'HARD'

export interface GateDecision {
  mode: string
  effectiveVerdict: GateVerdict
  blocked: boolean
  result: QualityGateResult
  rationale: string
}

export function evaluateQualityGateWithMode(projectId: string, baselineId: string, policyId: string, mode: GateEnforcementMode) {
  return apiPost<GateDecision>(
    `${base(projectId)}/baselines/${baselineId}/quality-gate/evaluate-mode`,
    JSON.stringify({ policyId, mode }), 'application/json')
}

export function createGateExemption(projectId: string, baselineId: string, payload: {
  ruleId: string; reason: string; expiresAt?: string
}) {
  return apiPost<GateExemption>(
    `${base(projectId)}/baselines/${baselineId}/quality-gate/exemptions`,
    JSON.stringify(payload), 'application/json')
}

export function fetchGateExemptions(projectId: string, baselineId: string) {
  return apiGet<GateExemption[]>(`${base(projectId)}/baselines/${baselineId}/quality-gate/exemptions`)
}

// ── Change Impact API ─────────────────────────────────────────────────────────

export interface GitImpactReport {
  id: string
  changeSet: {
    repositoryUrl?: string
    baseCommit: string
    headCommit: string
    mergeBase?: string
    analyzerVersion?: string
    createdAt?: string
    files: Array<{
      oldPath?: string
      newPath?: string
      changeType: string
      renameScore?: number
      oldBlobId?: string
      newBlobId?: string
      oldRanges?: Array<{ startLine: number; endLine: number }>
      newRanges?: Array<{ startLine: number; endLine: number }>
      binary?: boolean
      language?: string
    }>
  }
  directChanges: Array<{
    oldKey?: string
    newKey?: string
    symbolKey: string
    changeType: string
    facets: string[]
    oldSymbol?: GitSymbolSnapshot
    newSymbol?: GitSymbolSnapshot
    evidenceRanges?: Array<{ startLine: number; endLine: number }>
  }>
  candidates: Array<{
    seedSymbol: string
    targetSymbol: string
    direction: string
    distance: number
    classification: string
    ruleScore: number
    semanticScore?: number
    confidence: number
    reason: string
    path?: { symbols: string[]; edgeTypes: string[]; confidence: number }
    evidence?: Record<string, unknown>
  }>
  llmJudgements: Array<{
    candidateId: string
    decision: 'CONFIRM' | 'REJECT' | 'UNCERTAIN'
    confidence: number
    businessReason?: string
    riskLevel?: string
    recommendedTests?: string[]
    evidenceIds?: string[]
  }>
}

export interface GitSymbolSnapshot {
  key: string
  kind: string
  language: string
  qualifiedName: string
  signature?: string
  path?: string
  range?: { startLine: number; endLine: number }
  astHash?: string
  bodyHash?: string
  apiHash?: string
  snippet?: string
  invokedNames?: string[]
}

export interface GitChangeImpactResponse {
  report: GitImpactReport
  traceability: { affectedSymbols: string[]; affectedCriteria: AcceptanceCriterion[]; affectedTestcases: TestcaseProjection[] }
}

export interface GitImpactLlmReviewProgress {
  reportId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'UNAVAILABLE' | 'NOT_FOUND'
  total: number
  completed: number
  judgements: GitImpactReport['llmJudgements']
  message?: string
}

export interface GitImpactAnalysisJob {
  jobId: string
  projectId: string
  baselineId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  stage: string
  percent: number
  message: string
  result?: GitChangeImpactResponse
  error?: string
  createdAt: string
  updatedAt: string
}

export function analyzeGitChangeImpact(projectId: string, baselineId: string, payload: { appId: string; baseCommit: string; headCommit: string }) {
  return apiPost<GitChangeImpactResponse>(
    `${base(projectId)}/baselines/${baselineId}/git-change-impact`, JSON.stringify(payload), 'application/json')
}

export function startGitChangeImpactJob(projectId: string, baselineId: string, payload: { appId: string; baseCommit: string; headCommit: string }) {
  return apiPost<GitImpactAnalysisJob>(
    `${base(projectId)}/baselines/${baselineId}/git-change-impact-jobs`, JSON.stringify(payload), 'application/json')
}

export function fetchGitChangeImpactJob(projectId: string, jobId: string) {
  return apiGet<GitImpactAnalysisJob>(`${base(projectId)}/git-change-impact-jobs/${jobId}`)
}

export function fetchGitImpactLlmReview(projectId: string, reportId: string) {
  return apiGet<GitImpactLlmReviewProgress>(`${base(projectId)}/git-change-impact/${reportId}/llm-review`)
}

export function analyzeChangeImpact(projectId: string, baselineId: string, changeDescription?: string) {
  return apiPost<ChangeImpactReport>(
    `${base(projectId)}/baselines/${baselineId}/change-impact`,
    JSON.stringify({ changeDescription }), 'application/json')
}
