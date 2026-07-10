import { apiGet, apiPost } from './http'
import type {
  CompareJobPayload,
  CompareJobSummary,
  GitCommitOption,
  GitJobSummary,
  GitPullEstimate,
  PackageCommitVerify,
  VersionCenterPayload,
  VersionReportDetailPayload,
} from './types'

export function fetchVersionCenter(projectId: string, appId: string) {
  return apiGet<VersionCenterPayload>(`/api/projects/${projectId}/apps/${appId}/version-center`)
}

export function startCompareJob(
  projectId: string,
  appId: string,
  payload: {
    mode: string
    sourceFile?: string
    targetFile?: string
    packageName?: string
    branch?: string
    oldCommit?: string
    newCommit?: string
  },
) {
  return apiPost<CompareJobPayload>(
    `/api/projects/${projectId}/apps/${appId}/compare-jobs`,
    JSON.stringify(payload),
    'application/json',
  )
}

export function fetchCompareJob(projectId: string, appId: string, jobId: string) {
  return apiGet<CompareJobSummary>(`/api/projects/${projectId}/apps/${appId}/compare-jobs/${jobId}`)
}

export function fetchCompareReport(projectId: string, reportId: string) {
  return apiGet<VersionReportDetailPayload>(`/api/projects/${projectId}/version/reports/${reportId}`)
}

export function createVersion(projectId: string, appId: string, payload: Record<string, string>) {
  const body = new URLSearchParams()
  Object.entries(payload).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      body.set(key, value)
    }
  })
  return apiPost<string>(
    `/api/projects/${projectId}/apps/${appId}/versions`,
    body.toString(),
    'application/x-www-form-urlencoded;charset=UTF-8',
  )
}

export function setCurrentVersion(
  projectId: string,
  appId: string,
  payload: { versionNumber: string; branch?: string; commitId?: string },
) {
  const body = new URLSearchParams()
  body.set('versionNumber', payload.versionNumber)
  if (payload.branch) {
    body.set('branch', payload.branch)
  }
  if (payload.commitId) {
    body.set('commitId', payload.commitId)
  }
  return apiPost<string>(
    `/api/projects/${projectId}/apps/${appId}/versions/current`,
    body.toString(),
    'application/x-www-form-urlencoded;charset=UTF-8',
  )
}

export function deleteVersion(projectId: string, appId: string, id: string) {
  const body = new URLSearchParams()
  body.set('id', id)
  return apiPost<string>(
    `/api/projects/${projectId}/apps/${appId}/versions/delete`,
    body.toString(),
    'application/x-www-form-urlencoded;charset=UTF-8',
  )
}

export function deleteCompareReport(projectId: string, appId: string, reportId: string) {
  const body = new URLSearchParams()
  body.set('reportId', reportId)
  return apiPost<string>(
    `/api/projects/${projectId}/apps/${appId}/version-reports/delete`,
    body.toString(),
    'application/x-www-form-urlencoded;charset=UTF-8',
  )
}

export function deleteVersionFile(projectId: string, appId: string, filePath: string) {
  const query = new URLSearchParams()
  query.set('filePath', filePath)
  return apiGet<string>(`/api/projects/${projectId}/apps/${appId}/version-files/delete?${query.toString()}`)
}

export function fetchGitPullEstimate(
  projectId: string,
  appId: string,
  params: { branch?: string; commitId?: string; versionNumber?: string; excludePaths?: string },
) {
  const query = new URLSearchParams()
  if (params.branch) {
    query.set('branch', params.branch)
  }
  if (params.commitId) {
    query.set('commitId', params.commitId)
  }
  if (params.versionNumber) {
    query.set('versionNumber', params.versionNumber)
  }
  if (params.excludePaths) {
    query.set('excludePaths', params.excludePaths)
  }
  return apiGet<GitPullEstimate>(`/api/projects/${projectId}/apps/${appId}/git/pull-check?${query.toString()}`)
}

export function fetchGitRecentCommits(projectId: string, appId: string, branch: string, limit = 20) {
  const query = new URLSearchParams()
  query.set('branch', branch)
  query.set('limit', String(limit))
  return apiGet<GitCommitOption[]>(`/api/projects/${projectId}/apps/${appId}/git/commits?${query.toString()}`)
}

export function fetchGitLatestCommit(projectId: string, appId: string, branch: string) {
  const query = new URLSearchParams()
  query.set('branch', branch)
  return apiGet<string>(`/api/projects/${projectId}/apps/${appId}/git/latest-commit?${query.toString()}`)
}

export function startGitPull(
  projectId: string,
  appId: string,
  payload: { branch?: string; commitId?: string; excludePaths?: string; versionNumber?: string },
) {
  const body = new URLSearchParams()
  if (payload.branch) {
    body.set('branch', payload.branch)
  }
  if (payload.commitId) {
    body.set('commitId', payload.commitId)
  }
  if (payload.excludePaths) {
    body.set('excludePaths', payload.excludePaths)
  }
  if (payload.versionNumber) {
    body.set('versionNumber', payload.versionNumber)
  }
  return apiPost<string>(
    `/api/projects/${projectId}/apps/${appId}/git/pull`,
    body.toString(),
    'application/x-www-form-urlencoded;charset=UTF-8',
  )
}

export function fetchGitPullStatus(projectId: string, appId: string, jobId: string) {
  const query = new URLSearchParams()
  query.set('jobId', jobId)
  return apiGet<GitJobSummary>(`/api/projects/${projectId}/apps/${appId}/git/jobs/${jobId}`)
}

export function deleteGitCode(projectId: string, appId: string, cachePath: string) {
  const query = new URLSearchParams()
  query.set('cachePath', cachePath)
  return apiGet<string>(`/api/projects/${projectId}/apps/${appId}/git/cache?${query.toString()}`)
}

export function verifyUploadedPackageCommit(
  projectId: string,
  appId: string,
  payload: { programFile: string; commitId?: string },
) {
  const query = new URLSearchParams()
  query.set('programFile', payload.programFile)
  if (payload.commitId) {
    query.set('commitId', payload.commitId)
  }
  return apiGet<PackageCommitVerify>(`/api/projects/${projectId}/apps/${appId}/packages/commit-verify?${query.toString()}`)
}
