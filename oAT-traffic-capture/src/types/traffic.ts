// Traffic record type shared between main and renderer processes
import type { ProbeAgent } from './probe'
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
  /** 覆盖率采集：本次请求注入的 X-Coverage-Key 值（探针侧 headerkey 归因，零改业务代码） */
  coverageKey?: string
}

// ===== 覆盖率功能相关类型 =====

export type CoverageParamType = 'text' | 'path' | 'number' | 'boolean' | 'select'

/** 后端插件暴露给 UI 的参数定义，驱动表单渲染与命令预览 */
export interface CoverageParamSpec {
  key: string
  label: string
  type: CoverageParamType
  default?: string
  required?: boolean
  options?: string[]      // select 类型可选项
  placeholder?: string
  help?: string
  /** path 类型：选文件 / 目录 / 目录或归档 / 目录或 Git 仓库（dirOrGit 触发「Git 拉取」面板） */
  pick?: 'file' | 'dir' | 'dirOrFile' | 'dirOrGit'
  /** 条件显示：仅当另一参数等于指定值时才在 UI 展示 */
  showWhen?: { key: string; value: string }
}

export interface CoverageConfig {
  enabled: boolean
  /** X-Coverage-Key 值：uuid 或拼音/英文用户名 */
  key: string
  /** 注入的请求头名，默认 X-Coverage-Key */
  headerName: string
  /** xiaoxiao-jacoco agent 的 tcpserver 地址 host:port（远程 dump，无需进容器） */
  agentAddress: string
  /** 选定的语言后端 id，如 jacoco / nyc / coverage-py / go-cov / gcov-lcov */
  backend: string
  /** classfiles（覆盖率分母）本地路径，仅本地路径 */
  classfilesPath: string
  /** 手工登记的探针（本机扫描看不到容器/远端端口；登记后参与心跳探测） */
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
  /** 后端说明 */
  description: string
  /** 是否需要抓取步骤（Java 有；其余语言采集在服务运行期完成） */
  collect?: boolean
  /** 暴露给 UI 的参数定义（驱动表单 + 命令预览） */
  params: CoverageParamSpec[]
  /** 该插件工具链的全部指令（每条指令带自己的参数 schema，UI 据此渲染） */
  commands: CoverageCommandInfo[]
}

export interface ProxyStatus {
  running: boolean
  port: number
  error?: string
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

export interface WebSocketRecord extends TrafficRecord {
  protocol: 'WS' | 'WSS'
  messages: WsMessage[]
  connectionState: 'open' | 'closed'
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

export interface PluginInfo {
  id: string
  name: string
  version: string
  main: string
  enabled: boolean
  path: string
  description?: string
  error?: string
}

export type BuiltinPluginId = 'traffic-cleanup-plugin' | 'all'
