import { apiGet, apiGetRaw, apiPost } from './http'
import type {
  AppSummary,
  AppSettingsPayload,
  CollectorSourcesPayload,
  CompareJobPayload,
  CompareJobSummary,
  GitCommitOption,
  GitJobSummary,
  GitPullEstimate,
  MapElement,
  LabelSummary,
  PackageCommitVerify,
  ProjectContext,
  ProjectLabelsPayload,
  ProjectMembersPayload,
  ProjectSummary,
  RepositoryConfigPayload,
  SearchKeywordPayload,
  UserSummary,
  VersionCenterPayload,
  VersionReportDetailPayload,
} from './types'

export function fetchCurrentUser() {
  return apiGet<UserSummary>('/api/auth/me')
}

export function fetchAccountProfile() {
  return apiGet<UserSummary>('/api/account/profile')
}

export function updateAccountProfile(payload: {
  name: string
  nickname?: string
  email: string
  phone?: string
  readme?: string
}) {
  return apiPost<UserSummary>('/api/account/profile', JSON.stringify(payload), 'application/json')
}

export function updateAccountPassword(payload: {
  oldPassword: string
  newPassword: string
  newPasswordConfirm: string
}) {
  return apiPost<string>('/api/account/password', JSON.stringify(payload), 'application/json')
}

export function login(payload: { nameOrEmail: string; password: string }) {
  return apiPost<UserSummary>('/api/auth/login', JSON.stringify(payload), 'application/json')
}

export function register(payload: {
  name: string
  nickname?: string
  email: string
  password: string
  againPassword: string
}) {
  return apiPost<string>('/api/auth/register', JSON.stringify(payload), 'application/json')
}

export function logout() {
  return apiPost<string>('/api/auth/logout')
}

export function fetchProjects() {
  return apiGet<ProjectSummary[]>('/api/projects')
}

export function createProject(payload: { name: string; describe?: string }) {
  return apiPost<ProjectSummary>('/api/projects', JSON.stringify(payload), 'application/json')
}

export function updateProject(projectId: string, payload: { name: string; describe?: string }) {
  return apiPost<ProjectSummary>(`/api/projects/${projectId}`, JSON.stringify(payload), 'application/json')
}

export function deleteProject(projectId: string, payload: { password: string }) {
  return apiPost<string>(`/api/projects/${projectId}/delete`, JSON.stringify(payload), 'application/json')
}

export function fetchProjectContext(projectId: string) {
  return apiGet<ProjectContext>(`/api/projects/${projectId}/context`)
}

export function fetchProjectApps(projectId: string) {
  return apiGet<AppSummary[]>(`/api/projects/${projectId}/apps`)
}

export function createProjectApp(
  projectId: string,
  payload: {
    name: string
    srcName?: string
    language?: string
    languageConfig?: string
    range?: string
    describe?: string
    properties?: string
    currentVersion?: string
    currentBranch?: string
    currentCommitId?: string
  },
) {
  return apiPost<AppSummary>(`/api/projects/${projectId}/apps`, JSON.stringify(payload), 'application/json')
}

export function deleteProjectApp(projectId: string, appId: string, payload: { password: string }) {
  return apiPost<string>(`/api/projects/${projectId}/apps/${appId}/delete`, JSON.stringify(payload), 'application/json')
}

export function fetchProjectMembers(projectId: string) {
  return apiGet<ProjectMembersPayload>(`/api/projects/${projectId}/members`)
}

export function addProjectMembers(projectId: string, userIds: string[]) {
  return apiPost<ProjectMembersPayload>(
    `/api/projects/${projectId}/members/add`,
    JSON.stringify({ userIds }),
    'application/json',
  )
}

export function removeProjectMember(projectId: string, projectMemberId: string) {
  return apiPost<ProjectMembersPayload>(
    `/api/projects/${projectId}/members/${projectMemberId}/remove`,
    '',
    'application/json',
  )
}

export function updateProjectMemberRole(projectId: string, projectMemberId: string, role: string) {
  return apiPost<ProjectMembersPayload>(
    `/api/projects/${projectId}/members/${projectMemberId}/role`,
    JSON.stringify({ role }),
    'application/json',
  )
}

export function fetchProjectLabels(projectId: string) {
  return apiGet<ProjectLabelsPayload>(`/api/projects/${projectId}/labels`)
}

export function upsertProjectLabel(projectId: string, payload: { type: string; name: string; color: string }) {
  return apiPost<ProjectLabelsPayload>(
    `/api/projects/${projectId}/labels/upsert`,
    JSON.stringify(payload),
    'application/json',
  )
}

export function deleteProjectLabel(projectId: string, payload: { type: string; name: string }) {
  return apiPost<ProjectLabelsPayload>(
    `/api/projects/${projectId}/labels/delete`,
    JSON.stringify(payload),
    'application/json',
  )
}

export function fetchCollectorSources(projectId: string) {
  return apiGet<CollectorSourcesPayload>(`/api/projects/${projectId}/collector-sources`)
}

export function fetchAppSettings(projectId: string, appId: string) {
  return apiGet<AppSettingsPayload>(`/api/projects/${projectId}/apps/${appId}/settings`)
}

export function saveAppSettings(
  projectId: string,
  appId: string,
  payload: {
    name: string
    srcName: string
    language?: string
    languageConfig?: string
    range: string
    describe: string
    properties: string
    currentVersion: string
    currentBranch: string
    currentCommitId: string
  },
) {
  return apiPost<AppSettingsPayload>(
    `/api/projects/${projectId}/apps/${appId}/settings`,
    JSON.stringify(payload),
    'application/json',
  )
}

export function fetchRepositoryConfig(projectId: string, appId: string) {
  return apiGet<RepositoryConfigPayload>(`/api/projects/${projectId}/apps/${appId}/repository`)
}

export function saveRepositoryConfig(
  projectId: string,
  appId: string,
  payload: {
    repoAddress: string
    repoUserName: string
    repoPassword: string
  },
) {
  return apiPost<RepositoryConfigPayload>(
    `/api/projects/${projectId}/apps/${appId}/repository`,
    JSON.stringify(payload),
    'application/json',
  )
}

export function fetchRepositoryBranches(projectId: string, appId: string) {
  return apiGet<string[]>(`/api/projects/${projectId}/apps/${appId}/repository/branches`)
}

export function fetchRepositoryBranchesPreview(
  projectId: string,
  payload: { repoUrl: string; username?: string; password?: string },
) {
  const query = new URLSearchParams()
  query.set('repoUrl', payload.repoUrl)
  if (payload.username) {
    query.set('username', payload.username)
  }
  if (payload.password) {
    query.set('password', payload.password)
  }
  return apiGet<string[]>(`/api/projects/${projectId}/repository/branches?${query.toString()}`)
}

export * from './version'

export function uploadResource(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return apiPost<string>('/api/resource/upload', formData)
}

export function searchKeyword(projectId: string, keyword: string, type?: 'usecase') {
  const query = new URLSearchParams()
  query.set('keyword', keyword)
  if (type) query.set('type', type)
  return apiGet<SearchKeywordPayload>(`/api/projects/${projectId}/search/keyword?${query.toString()}`)
}

export function fetchMapHome(projectId: string) {
  return apiGetRaw<MapElement[]>(`/api/projects/${projectId}/map/home`)
}

export function fetchMapApp(projectId: string, appId: string, layers: string[]) {
  const query = new URLSearchParams()
  query.set('appId', appId)
  if (layers.length) {
    query.set('layers', layers.join(','))
  }
  return apiGetRaw<MapElement[]>(`/api/projects/${projectId}/map/apps/${appId}?${query.toString()}`)
}

export interface SourceTreeMethod {
  methodName: string
  methodDesc?: string
  lineNumber?: number
}

export interface SourceTreeClass {
  id: string
  className: string
  filePath?: string
  methods: SourceTreeMethod[]
}

export function fetchMapSourceTree(projectId: string, appId?: string, sourceAssetId?: string) {
  const query = new URLSearchParams()
  if (appId) query.set('appId', appId)
  if (sourceAssetId) query.set('sourceAssetId', sourceAssetId)
  return apiGetRaw<SourceTreeClass[]>(`/api/projects/${projectId}/map/source-tree?${query.toString()}`)
}

export function fetchMapCode(projectId: string, traceId: string) {
  const query = new URLSearchParams()
  query.set('traceId', traceId)
  return apiGetRaw<MapElement[]>(`/api/projects/${projectId}/map/code?${query.toString()}`)
}
