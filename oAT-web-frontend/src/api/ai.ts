import { apiGet, apiPost } from './http'
import type { VerificationAsset } from './verification'

export interface AiTaskSubmissionResponse {
  taskId: string
}

export interface AiDraftResponse {
  taskId: string
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CONFIRMED' | 'REJECTED'
  payload: unknown
  error?: string
  aiGenerated: boolean
}

export function submitAiTask(
  projectId: string,
  intent: string,
  context: Record<string, unknown> = {},
): Promise<AiTaskSubmissionResponse> {
  return apiPost<AiTaskSubmissionResponse>(
    `/api/projects/${encodeURIComponent(projectId)}/ai/tasks`,
    JSON.stringify({ intent, context }),
    'application/json',
  )
}

/**
 * 导入区「AI 生成草稿」：无需先有资产，用户粘贴的文本直接作为 AI 上下文。
 * 草稿确认后经 confirmAiDraft 落库为新的 AI 生成资产。
 */
export function generateAiDraft(
  projectId: string,
  intent: string,
  content: string,
): Promise<AiTaskSubmissionResponse> {
  return apiPost<AiTaskSubmissionResponse>(
    `/api/projects/${encodeURIComponent(projectId)}/ai/generate`,
    JSON.stringify({ intent, content }),
    'application/json',
  )
}

export function parseRequirementWithAi(projectId: string, assetId: string): Promise<AiTaskSubmissionResponse> {
  return submitAssetAiTask(projectId, 'requirements', assetId, 'parse')
}

export function submitAssetAiTask(
  projectId: string,
  domain: 'requirements' | 'testcases' | 'defects' | 'coverage' | 'sources' | 'git' | 'versions',
  assetId: string,
  action: 'parse' | 'analyze',
): Promise<AiTaskSubmissionResponse> {
  return apiPost<AiTaskSubmissionResponse>(
    `/api/projects/${encodeURIComponent(projectId)}/ai/${domain}/${encodeURIComponent(assetId)}/${action}`,
    JSON.stringify({}),
    'application/json',
  )
}

export function getAiDraft(projectId: string, taskId: string): Promise<AiDraftResponse> {
  return apiGet<AiDraftResponse>(
    `/api/projects/${encodeURIComponent(projectId)}/ai/tasks/${encodeURIComponent(taskId)}/draft`,
  )
}

export function confirmAiDraft(
  projectId: string,
  taskId: string,
  confirmed: boolean,
  payload?: string,
): Promise<VerificationAsset | string> {
  return apiPost<VerificationAsset | string>(
    `/api/projects/${encodeURIComponent(projectId)}/ai/tasks/${encodeURIComponent(taskId)}/confirm`,
    JSON.stringify({ confirmed, payload }),
    'application/json',
  )
}
