export interface ApiResponse<T> {
  result: boolean
  success?: boolean
  message?: string
  errorMessage?: string
  data: T
}

export interface UserSummary {
  id: string
  name: string
  nickname?: string
  email?: string
  header?: string
  phone?: string
  readme?: string
}

export interface ProjectSummary {
  id: string
  name: string
  describe?: string
  create?: string
  createDisplayName?: string
  memberCount: number
  createTime?: string
  updateTime?: string
}

export interface AppSummary {
  id: string
  name: string
  srcName?: string
  language?: string
  languageConfig?: string
  describe?: string
  range?: string
  onlineCount: number
  currentVersion?: string
  currentBranch?: string
  currentCommitId?: string
  repoConfigured: boolean
  sourceType?: string
}

export interface AiSummary {
  enabled: boolean
  timeout: number
  interactivePath: string
  askApiPath: string
  feedbackApiBasePath: string
  mascotPrimary: string
}

export interface ProjectContext {
  currentUser: UserSummary
  project: ProjectSummary
  apps: AppSummary[]
  recentLogs?: ProjectLogItem[]
  currentUserRole: string
  ai: AiSummary
  onlineAppCount: number
  appCount: number
}

export interface ProjectLogItem {
  id?: string
  title?: string
  message?: string
  createTime?: string
  userName?: string
}

export interface ProjectMembersPayload {
  members: ProjectMemberSummary[]
  availableUsers: UserSummary[]
  currentUserRole: string
}

export interface ProjectMemberSummary {
  id: string
  projectId: string
  memberId: string
  memberName?: string
  memberEmail?: string
  role?: string
  star: boolean
  defaultProject: boolean
  createTime?: string
}

export interface ProjectLabelsPayload {
  usecaseLabels: LabelSummary[]
  currentUserRole: string
}

export interface LabelSummary {
  name: string
  color: string
}

export interface CollectorSourcesPayload {
  sources: CollectorSourceSummary[]
  total: number
  currentUserRole: string
}

export interface CollectorSourceSummary {
  sourceId?: string
  projectId?: string
  appId?: string
  appName?: string
  language?: string
  collectorType?: 'RESIDENT' | 'BATCH'
  lastSeenTime?: number
  reportIntervalMillis?: number
  health?: 'ONLINE' | 'SILENT' | 'OFFLINE' | 'UNKNOWN'
  sessionId?: string
  addressIp?: string
  pid?: string
}

export interface TraceItemSummary {
  traceId: string
  title?: string
  cacheTime?: number
  validity?: number
  index?: number
  appId?: string
  addressIp?: string
  clientIp?: string
  entryType?: string
  entryName?: string
  displayName?: string
  status?: string
}

export interface UsecaseImportError {
  rowNumber: number
  message: string
}

export interface UsecaseImportResult {
  successCount: number
  errors?: UsecaseImportError[]
}

export interface PulledZipItem {
  fileName: string
  cachePath: string
  size?: number
  lastModified?: number
  lastModifiedText?: string
}

export interface ApiEndpointUsecaseLink {
  id: string
  title?: string
  directory?: string
}

export interface ApiEndpointItem {
  id?: string
  endpointType?: string
  url?: string
  httpMethod?: string
  className?: string
  methodName?: string
  methodDesc?: string
  sourceType?: string
  sourceName?: string
  hitCount?: number
  mergedSourceCount?: number
  classNameList?: string[]
  methodNameList?: string[]
  methodDescList?: string[]
  sourceTypeList?: string[]
  sourceNameList?: string[]
  endpointKeyParts?: string[]
  linkedUsecases?: ApiEndpointUsecaseLink[]
}

export interface AppSettingsPayload {
  app: AppSettingsSummary
  apps: AppSummary[]
  currentUserRole: string
}

export interface AppSettingsSummary {
  id: string
  name?: string
  srcName?: string
  language?: string
  languageConfig?: string
  range?: string
  describe?: string
  properties?: string
  currentVersion?: string
  currentBranch?: string
  currentCommitId?: string
}

export interface RepositoryConfigPayload {
  app: RepositorySummary
  currentUserRole: string
}

export interface RepositorySummary {
  appId: string
  appName?: string
  repoAddress?: string
  repoUserName?: string
  repoPassword?: string
  configured: boolean
}

export interface MapElementData {
  id: string
  source?: string
  target?: string
  name?: string
  hotName?: string
  methodName?: string
  describe?: string
  database?: string
  appId?: string
  interfaceName?: string
  cyclo?: number
  [key: string]: unknown
}

export interface MapElement {
  group?: 'nodes' | 'edges' | string
  data: MapElementData
  classes?: string[]
}

export interface SearchKeywordResult {
  id: string
  targetPath: string
  title?: string
  plainTitle?: string
  titleFragment?: string
  subTitle?: string
  directoryPath?: string
  imagePath?: string
  headImage?: string
  describeFragments?: string[]
  sqlContentFragments?: string[]
  remoteContentFragments?: string[]
}

export interface SearchKeywordPayload {
  total: number
  results: SearchKeywordResult[]
}

export interface UsecaseDirectory {
  id: string
  name: string
  parentId?: string
  projectId?: string
  updateTimeText?: string
  updateTimeRelativeText?: string
}

export interface UsecaseSummary {
  id: string
  projectId: string
  title: string
  headImage?: string
  content?: string
  directory: string
  defects?: string[]
  prdRequirements?: string[]
  defectsText?: string
  prdRequirementsText?: string
  labels?: string[]
  authors?: string[]
  lastUpdateAuthor?: string
  createTime?: string
  updateTime?: string
  updateTimeText?: string
  share?: boolean
}

export interface RelationOption {
  id: string
  name: string
  url?: string
  external: boolean
}

export interface UsecaseListPayload {
  currentDirectory: string
  currentDirectoryName: string
  sort: string
  keyword?: string
  usecases: UsecaseSummary[]
  directories: UsecaseDirectory[]
  directoryTiers?: UsecaseDirectory[]
  maintainerNameMap: Record<string, string>
  apps: AppSummary[]
  currentUserRole: string
}

export interface UsecaseBootstrapPayload {
  currentDirectory: string
  currentDirectoryName: string
  labels: LabelSummary[]
  usecase?: UsecaseSummary
  selectedLabelNames: string[]
  defectsText?: string
  prdRequirementsText?: string
  currentUserRole: string
}

export interface UsecaseDetailPayload {
  usecase: UsecaseSummary
  lastUpdateAuthor?: UserSummary
  labels?: LabelSummary[]
  defects?: RelationOption[]
  prdRequirements?: RelationOption[]
  contentHtml?: string
  currentUserRole: string
}

export interface DirectoryDeletePreview {
  requiresCascade: boolean
  directoryCount: number
  usecaseCount: number
}

export interface VersionItemSummary {
  id: string
  versionNumber: string
  describe?: string
  sourceType?: string
  repoBranch?: string
  repoCommitId?: string
  programName?: string
  programFile?: string
  createTimeText?: string
  createTimeRelativeText?: string
  current?: boolean
  fileExist?: boolean
}

export interface CompareReportSummary {
  id: string
  name?: string
  jobName?: string
  sourceVersion?: string
  targetVersion?: string
  gitBranch?: string
  gitOldCommit?: string
  gitNewCommit?: string
  addClassCount: number
  updateClassCount: number
  deleteClassCount: number
  addMethodCount: number
  updateMethodCount: number
  deleteMethodCount: number
  impactCaseCount?: number
  createTimeText?: string
  createTimeRelativeText?: string
  jobLog?: string
}

export interface VersionCenterPayload {
  app: AppSummary
  versions: VersionItemSummary[]
  packageVersions?: Array<{
    versionNumber?: string
    programName?: string
    programFile?: string
    createTimeText?: string
  }>
  compareReports: CompareReportSummary[]
  currentUserRole?: string
}

export interface CompareJobPayload {
  job?: CompareJobSummary
  jobId: string
  id?: string
  message?: string
}

export interface CompareJobSummary {
  id: string
  name?: string
  jobName?: string
  finish?: boolean
  error?: string
  errorMessage?: string
  progress?: number
  progressName?: string
  log?: string
  addClassCount?: number
  updateClassCount?: number
  deleteClassCount?: number
  addMethodCount?: number
  updateMethodCount?: number
  deleteMethodCount?: number
}

export interface GitCommitOption {
  commitId: string
  shortCommitId?: string
  message?: string
  author?: string
  commitTimeText?: string
}

export interface GitJobSummary {
  id?: string
  progress: number
  progressName?: string
  message?: string
  finish?: boolean
  success?: boolean
  error?: string
  resultPath?: string
  cachePath?: string
  repoCommitId?: string
}

export interface PackageCommitVerify {
  matched?: boolean
  commitId?: string
  unavailableReason?: string
}

export interface GitPullEstimate {
  branch?: string
  commitId?: string
  estimatedDurationMs?: number
  estimatedPackageSizeBytes?: number
  packageCommitVerify?: PackageCommitVerify
}

export interface DifferenceMethodSummary {
  methodName: string
  methodDesc?: string
  model?: string
}

export interface DifferenceGroupSummary {
  className: string
  model?: string
  methods: DifferenceMethodSummary[]
}

export interface UsecaseImpactSummary {
  id: string
  title: string
  directoryPath?: string
  labels?: string[]
  differences: string[]
  available?: boolean
}

export interface EndpointImpactSummary {
  id?: string
  endpointType?: string
  httpMethod?: string
  url?: string
  className?: string
  methodName?: string
  hitCount?: number
  matchedClasses: string[]
  matchedMethods: string[]
  linkedUsecases?: Array<{ id: string; title?: string; directory?: string }>
}

export interface VersionReportDetailPayload {
  state?: 'pending' | 'ready'
  retryMessage?: string
  app?: AppSummary
  report?: CompareReportSummary
  differences?: DifferenceGroupSummary[]
  usecases?: UsecaseImpactSummary[]
  endpoints?: EndpointImpactSummary[]
  impactHints?: {
    zeroHitClasses?: string[]
  }
}

export interface AIAbilityCard {
  title: string
  value: string
  description: string
}

export interface AIQuickLink {
  title: string
  description: string
  url: string
}

export interface AIAction {
  type: string
  title: string
  description: string
  url?: string
  requireConfirm?: boolean
  confirmText?: string
  payload?: Record<string, unknown>
}

export interface AIInteractivePagePayload {
  projectId: string
  projectName: string
  projectSummary: string
  welcomeMessage: string
  mascotHint: string
  onlineAppCount: number
  appCount: number
  appNames: string[]
  starterQuestions: string[]
  abilityCards: AIAbilityCard[]
  quickLinks: AIQuickLink[]
  mascot?: Record<string, string>
  aiTimeout: number
  sessionState?: string
}

export interface AIInteractiveReply {
  question?: string
  answer?: string
  topic?: string
  suggestions?: string[]
  quickLinks?: AIQuickLink[]
  actions?: AIAction[]
  visualizationSuggestions?: Array<Record<string, unknown>>
  confidence?: number
  usedTools?: string[]
  needMoreData?: boolean
  metadata?: Record<string, unknown>
  sessionState?: string
}

export interface AIFeedbackPayload {
  projectId: string
  question?: string
  answer?: string
  rating: number
  feedbackType: 'helpful' | 'not_helpful' | 'incorrect' | 'incomplete'
  comment?: string
  usedTools?: string
  responseTime?: number
}

export interface AISelfLearningStatus {
  knowledgeBaseSize?: number
  trackedTopics?: number
  failurePatterns?: number
  pendingSuggestions?: number
  topicGuidanceCount?: number
  topicHealth?: Record<string, string>
}

export interface AIFeedbackStats {
  total?: number
  positive?: number
  negative?: number
  neutral?: number
  satisfactionRate?: string
  selfLearning?: AISelfLearningStatus
}

export interface AILearningSuggestion {
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  title: string
  description: string
  id: string
  createdTime?: number
}

export interface AILearningReport {
  timestamp?: string
  knowledgeBaseEntries?: number
  trackedTopics?: number
  failurePatternsAnalyzed?: number
  suggestionsGenerated?: number
  topicHealthScores?: Record<string, number>
  suggestions?: AILearningSuggestion[]
}

export interface ProjectMemberLite {
  memberId: string
  memberName?: string
  memberEmail?: string
  role?: string
}

export interface PublicUsecasePayload extends UsecaseDetailPayload {
  projectId: string
}
