// 探针类型的定义处在 coverage/probes.ts，这里单向 re-export：
// 主进程与 preload 统一从 types.js 取，避免在三个文件里各抄一份接口
import type { ProbeAgent } from './coverage/probes.js'
export type { LocalCandidate, ProbeAgent, ProbeDiagnostics, ProbeKeyStat, ProbeResult, ProbeStatus, ProbeSummary } from './coverage/probes.js'

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
  /** path 类型：选文件 / 目录 / 目录或归档 / 目录或 Git 仓库（dirOrGit 触发「Git 拉取」面板） */
  pick?: 'file' | 'dir' | 'dirOrFile' | 'dirOrGit'
  /** 条件显示：仅当另一参数等于指定值时才在 UI 展示（如 perKeyFilter 依赖 perKey 开关） */
  showWhen?: { key: string; value: string }
}

export interface CoverageConfig {
  enabled: boolean
  key: string
  headerName: string
  agentAddress: string
  backend: string
  classfilesPath: string   // 本地路径（被插桩类的字节码目录），覆盖率分母；仅本地路径
  /** 手工登记的探针（本机扫描看不到容器/远端端口，登记后持久化并参与心跳探测） */
  agents?: ProbeAgent[]
  /** 扫描本机时自动登记确认是探针的地址（默认 true） */
  probeAutoRegister?: boolean
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
