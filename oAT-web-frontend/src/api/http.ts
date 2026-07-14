import type { ApiResponse } from './types'

const AUTH_REQUIRED_CODE = 'AUTH_REQUIRED'
const BACKEND_BASE_URL = (import.meta.env.VITE_OAT_BACKEND_BASE_URL || '').replace(/\/$/, '')

export class ApiError extends Error {
  status: number
  code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

function backendUrl(input: string) {
  if (/^https?:\/\//.test(input)) {
    return input
  }

  const normalized = input.startsWith('/') ? input : `/${input}`
  return BACKEND_BASE_URL ? `${BACKEND_BASE_URL}${normalized}` : normalized
}

async function fetchApi(input: string, init?: RequestInit) {
  try {
    return await fetch(backendUrl(input), {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(init?.headers || {}),
      },
      ...init,
    })
  } catch {
    throw new ApiError('无法连接后端服务，请确认后端已启动，并检查前端代理或跨域配置', 0)
  }
}

async function request<T>(input: string, init?: RequestInit): Promise<T> {
  const response = await fetchApi(input, init)

  if (response.status === 401) {
    throw new ApiError('未登录或登录已过期', 401, AUTH_REQUIRED_CODE)
  }

  const rawBody = await response.text()
  let payload: ApiResponse<T>

  try {
    payload = JSON.parse(rawBody) as ApiResponse<T>
  } catch {
    throw new ApiError(
      response.ok ? '服务端返回了非 JSON 响应' : `请求失败，服务端返回了异常响应 (${response.status})`,
      response.status,
    )
  }

  if (!response.ok || !payload.result) {
    throw new ApiError(
      payload.errorMessage || payload.message || '请求失败',
      response.status,
      payload.errorMessage,
    )
  }
  return payload.data
}

async function requestRawJson<T>(input: string, init?: RequestInit): Promise<T> {
  const response = await fetchApi(input, init)

  if (response.status === 401) {
    throw new ApiError('未登录或登录已过期', 401, AUTH_REQUIRED_CODE)
  }

  const rawBody = await response.text()
  let payload: T

  try {
    payload = JSON.parse(rawBody) as T
  } catch {
    throw new ApiError(
      response.ok ? '服务端返回了非 JSON 响应' : `请求失败，服务端返回了异常响应 (${response.status})`,
      response.status,
    )
  }

  if (!response.ok) {
    throw new ApiError(`请求失败 (${response.status})`, response.status)
  }
  return payload
}

export function apiGet<T>(input: string): Promise<T> {
  return request<T>(input)
}

export function apiPost<T>(input: string, body?: BodyInit | null, contentType?: string): Promise<T> {
  const headers = contentType ? { 'Content-Type': contentType } : undefined
  return request<T>(input, {
    method: 'POST',
    body,
    headers,
  })
}

export function apiPut<T>(input: string, body?: BodyInit | null, contentType?: string): Promise<T> {
  const headers = contentType ? { 'Content-Type': contentType } : undefined
  return request<T>(input, {
    method: 'PUT',
    body,
    headers,
  })
}

export function apiDelete<T>(input: string): Promise<T> {
  return request<T>(input, {
    method: 'DELETE',
  })
}

export function apiGetRaw<T>(input: string): Promise<T> {
  return requestRawJson<T>(input)
}

export function apiPostRaw<T>(input: string, body?: BodyInit | null, contentType?: string): Promise<T> {
  const headers = contentType ? { 'Content-Type': contentType } : undefined
  return requestRawJson<T>(input, {
    method: 'POST',
    body,
    headers,
  })
}

export function backendApiUrl(input: string) {
  return backendUrl(input)
}
