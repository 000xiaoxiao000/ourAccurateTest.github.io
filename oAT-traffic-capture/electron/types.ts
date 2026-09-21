export interface TrafficRecord {
  id: string
  caseName: string
  method: string
  url: string
  protocol: string
  statusCode: number | string
  duration: number
  timestamp: number
  requestHeaders?: Record<string, string>
  requestBody?: string
  responseHeaders?: Record<string, string>
  responseBody?: string
  error?: string
  source?: 'capture' | 'manual' | 'replay' | 'plugin'
  replayOf?: string
  replayStatus?: 'pending' | 'success' | 'failed' | 'unsupported'
  replayTime?: number
  tags?: string[]
  websocketMessages?: WsMessage[]
  coverageKey?: string
}

// ===== 覆盖率功能相关类型（与主进程共享） =====

export type CoverageParamType = 'text' | 'path' | 'number' | 'boolean' | 'select'

/** 后端插件暴露给 UI 的参数定义，驱动表单渲染与命令预览 */
export interface CoverageParamSpec {
  key: string
  label: string
  type: CoverageParamType
  default?: string
  required?: boolean
  options?: string[]
  placeholder?: string
  help?: string
  pick?: 'file' | 'dir'
}

export interface CoverageConfig {
  enabled: boolean
  key: string
  headerName: string
  agentAddress: string
  backend: string
  classfilesPath: string   // 本地路径（被插桩类的字节码目录），覆盖率分母；仅本地路径
  projectDir: string       // 项目根目录：自动推导 classfiles / 源码目录的起点
  classfilesRepo: string   // classfiles 归档仓库（项目内无构建产物时按项目名匹配 zip/jar）
}

export interface CoverageExecInfo {
  file: string
  key: string
  size: number
  fetchedAt: number
  source: string
}

/** 后端插件暴露的一条 CLI 指令（全部指令 + 参数 UI 化的载体） */
export interface CoverageCommandInfo {
  id: string
  label: string
  description: string
  params: CoverageParamSpec[]
  /** 依赖的共享配置：agent / classfiles（UI 按需显示配置条） */
  needs?: string[]
}

export interface CoverageBackendInfo {
  id: string
  name: string
  languages: string[]
  status: 'builtin' | 'pending'
  description: string
  collect?: boolean
  params: CoverageParamSpec[]
  /** 该插件工具链的全部指令（每条指令带自己的参数 schema，UI 据此渲染） */
  commands: CoverageCommandInfo[]
}

export interface CaptureProtocolConfig {
  http: boolean
  https: boolean
  ws: boolean
  wss: boolean
}

export interface CaptureSession {
  id: string
  caseName: string
  startTime: number
  endTime?: number
  records: TrafficRecord[]
}

export interface WsMessage {
  id: string
  direction: 'send' | 'receive'
  type: 'text' | 'binary'
  data: string
  timestamp: number
}

export interface TrafficFilterRule {
  id: string
  name: string
  enabled: boolean
  target: 'url' | 'method' | 'protocol' | 'statusCode' | 'header' | 'body'
  operator: 'contains' | 'equals' | 'regex' | 'startsWith' | 'endsWith'
  value: string
  action: 'include' | 'exclude' | 'mark'
}

export interface ReplayResult {
  success: boolean
  record?: TrafficRecord
  error?: string
}

export interface PluginManifest {
  id: string
  name: string
  version: string
  main: string
  enabled?: boolean
  description?: string
}

export interface PluginInfo extends PluginManifest {
  enabled: boolean
  path: string
  error?: string
}
