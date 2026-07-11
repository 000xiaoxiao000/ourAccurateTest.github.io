import { apiGet, apiPost } from './http'

export type AssetType = 'REQUIREMENT' | 'TESTCASE' | 'SOURCE' | 'EXECUTION' | 'COVERAGE' | 'DEFECT'
export type ReviewStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'WRITTEN_BACK' | 'STALE' | 'EXEMPTED'
export type Verdict = 'SATISFIED' | 'STATICALLY_CONSISTENT' | 'PARTIAL' | 'NOT_SATISFIED' | 'AMBIGUOUS' | 'NOT_VERIFIABLE' | 'EXEMPTED' | 'STALE'
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'

export interface VerificationAsset {
  id: string
  projectId: string
  assetType: AssetType
  sourceType: string
  externalId?: string
  externalUrl?: string
  sourceVersion?: string
  fileName?: string
  contentHash: string
  freshness: string
  capturedAt: string
  metadata?: Record<string, unknown>
}

export interface VerificationBaseline {
  id: string
  projectId: string
  name: string
  requirementAssetId: string
  testcaseAssetId: string
  sourceAssetId?: string
  executionAssetId?: string
  coverageAssetId?: string
  sourceAppId?: string
  repositoryUrl?: string
  sourceBranch?: string
  sourceCommit?: string
  analyzerVersion: string
  status: string
  freshness: string
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
  evidenceLevel: string
  reviewStatus: ReviewStatus
  evidence?: Record<string, unknown>
}

export interface VerificationFinding {
  id: string
  acId?: string
  findingType: string
  perspective: 'PRODUCT' | 'TEST' | 'DEVELOPMENT' | 'CROSS'
  severity: Severity
  title: string
  description: string
  suggestion?: string
  confidence: number
  evidenceLevel: string
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
}

export interface BaselineDetail {
  baseline: VerificationBaseline
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
  evidenceLevel: string
}

export interface VerificationOverview {
  requirements: VerificationAsset[]
  testcases: VerificationAsset[]
  sources: VerificationAsset[]
  executions: VerificationAsset[]
  coverages: VerificationAsset[]
  baselines: VerificationBaseline[]
}

export interface GateResult {
  id: string
  baselineId: string
  status: 'PASSED' | 'WARNING' | 'FAILED'
  policy: Record<string, unknown>
  metrics: VerificationMetrics
  reasons: string[]
  createTime: string
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
  appId?: string
  repositoryUrl?: string
  username?: string
  password?: string
  branch?: string
  commit?: string
  maxFiles?: number
  maxBytes?: number
}) {
  return apiPost<VerificationAsset>(`${base(projectId)}/assets/git-source`, JSON.stringify(payload), 'application/json')
}

export function createVerificationBaseline(projectId: string, payload: {
  name: string
  requirementAssetId: string
  testcaseAssetId: string
  sourceAssetId?: string
  executionAssetId?: string
  coverageAssetId?: string
  sourceAppId?: string
  repositoryUrl?: string
  sourceBranch?: string
  sourceCommit?: string
}) {
  return apiPost<VerificationBaseline>(`${base(projectId)}/baselines`, JSON.stringify(payload), 'application/json')
}

export function fetchBaselineDetail(projectId: string, baselineId: string) {
  return apiGet<BaselineDetail>(`${base(projectId)}/baselines/${baselineId}`)
}

export function analyzeBaseline(projectId: string, baselineId: string) {
  return apiPost<BaselineDetail>(`${base(projectId)}/baselines/${baselineId}/analyze`)
}

export function fetchTraceMatrix(projectId: string, baselineId: string) {
  return apiGet<MatrixRow[]>(`${base(projectId)}/baselines/${baselineId}/matrix`)
}

export function reviewVerificationFinding(projectId: string, findingId: string, payload: {
  status: ReviewStatus
  reason?: string
  externalWorkItemUrl?: string
}) {
  return apiPost<string>(`${base(projectId)}/findings/${findingId}/review`, JSON.stringify(payload), 'application/json')
}

export function writeBackVerificationFinding(projectId: string, findingId: string, payload: {
  connectorType?: string
  externalUrl: string
  message?: string
}) {
  return apiPost<WriteBackAction>(`${base(projectId)}/findings/${findingId}/writeback`, JSON.stringify(payload), 'application/json')
}

export function fetchWriteBackActions(projectId: string, baselineId: string) {
  return apiGet<WriteBackAction[]>(`${base(projectId)}/baselines/${baselineId}/writebacks`)
}

export function reviewTraceLink(projectId: string, traceLinkId: string, status: ReviewStatus) {
  return apiPost<string>(`${base(projectId)}/trace-links/${traceLinkId}/review`, JSON.stringify({ status }), 'application/json')
}

export function evaluateQualityGate(projectId: string, baselineId: string) {
  return apiPost<GateResult>(`${base(projectId)}/baselines/${baselineId}/quality-gate`, JSON.stringify({
    minimumTestcaseCoverage: 1,
    minimumImplementationCoverage: 1,
    minimumExecutionEvidence: 0,
    minimumRuntimeCoverage: 0,
    maximumOpenHighFindings: 0,
    blocking: false,
  }), 'application/json')
}

export function markVerificationBaselineStale(projectId: string, baselineId: string) {
  return apiPost<string>(`${base(projectId)}/baselines/${baselineId}/stale`)
}
