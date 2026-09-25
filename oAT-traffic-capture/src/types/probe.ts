/**
 * 在线探针的共享类型（渲染进程侧镜像）。
 *
 * ⚠️ 主进程那份在 `electron/coverage/probes.ts`（tsconfig.electron.json 的 rootDir=./electron，
 *    主进程无法 import src/ 下的东西），两边结构必须保持一致，改一处要同步另一处。
 */

/** 已登记的探针（持久化在 capture-config.json 的 coverage.agents） */
export interface ProbeAgent {
  id: string
  host: string
  port: number
  label?: string
}

/** 本机扫描候选（来自 lsof，未登记） */
export interface ProbeCandidate {
  pid: string
  command: string
  host: string
  port: number
}

/** 探针自检计数（PerKeyProtocol 里 Diagnostics 的字段） */
export interface ProbeDiagnostics {
  classesSeen: number
  classesInstrumented: number
  requestsHooked: number
  requestsTagged: number
  classesNoLocation: number
  suggestedIncludes: string[]
  instrumentedSample: string[]
  skippedSample: string[]
}

/** 每个 key 的规模 */
export interface ProbeKeyStat {
  key: string
  classes: number
  probes: number
  covered: number
}

/** 四态色语义：绿=在线且采到 key / 黄=在线但没采到 / 蓝=官方 jacoco 降级 / 灰=离线 */
export type ProbeStatus = 'online' | 'warning' | 'vanilla' | 'offline'

export interface ProbeResult {
  id: string
  host: string
  port: number
  agentAddress: string
  label: string
  source: 'config' | 'registered' | 'discovered'
  status: ProbeStatus
  ms: number
  pingedAt: number
  note: string
  diag?: ProbeDiagnostics
  keyStats?: ProbeKeyStat[]
  keys?: string[]
  pid?: string
  hasDiagnostics?: boolean
}

export interface ProbeSummary {
  total: number
  online: number
  warning: number
  vanilla: number
  offline: number
  checkedAt: number
}

export interface ProbeScanResult {
  success: boolean
  probes?: ProbeResult[]
  discovered?: number
  error?: string
  lsofError?: string
}
