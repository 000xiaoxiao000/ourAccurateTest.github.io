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

export function analyzeChangeImpact(projectId: string, baselineId: string, changeDescription?: string) {
  return apiPost<ChangeImpactReport>(
    `${base(projectId)}/baselines/${baselineId}/change-impact`,
    JSON.stringify({ changeDescription }), 'application/json')
}
