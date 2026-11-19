// 影响分析（Impact Analysis）共享类型 —— 主进程 electron/coverage/impact.ts 与渲染进程共用同一套结构

/** 一条带 coverage key 的抓包记录（反查 key → 接口 / 用例） */
export interface ImpactTraffic {
  key: string
  method: string
  url: string
  protocol: string
  caseName: string
  timestamp: number
}

/** 分析请求（渲染进程 → 主进程；workDir / traffic 由主进程注入） */
export interface ImpactAnalyzeRequest {
  /** .exec 所在目录 */
  execDir: string
  /** 新版 classfiles；多路径用 ; 分隔 */
  classfiles: string
  /** 比对基准 A：旧版 classfiles（多路径用 ; 分隔） */
  oldClassfiles?: string
  /** 比对基准 B：新版源码（与旧版源码同时给，可替代 A） */
  newSources?: string
  /** 比对基准 B：旧版源码 */
  oldSources?: string
  /** 时间窗：只统计该时间点之后的流量（毫秒）；0 / 不传 = 全部 */
  since?: number
}

/** 受影响的接口 */
export interface ImpactApi {
  method: string
  url: string
  protocol: string
  keys: string[]
  cases: string[]
  /** 命中的变更行数 */
  hits: number
  /** 证据：类:行 */
  evidence: string[]
}

/** 受影响的用例 */
export interface ImpactCase {
  caseName: string
  hits: number
  /** 命中变更行 / 全部变更行（0~1） */
  coveredRatio: number
  keys: string[]
  /** 建议执行顺序（1 开始） */
  order: number
}

/** 没有任何用例跑到的变更行（= 需补测） */
export interface ImpactRisk {
  cls: string
  source?: string
  lines: number[]
  methods: string[]
}

export interface ImpactStage {
  text: string
  percent: number
}

export interface ImpactSummary {
  changedLines: number
  changedClasses: number
  affectedApis: number
  affectedCases: number
  uncoveredChanged: number
  keysTotal: number
  keysHit: number
  /** 时间窗内的抓包记录总数 */
  trafficTotal: number
  /** 其中带 X-Coverage-Key 的条数（0 = 抓包时采集开关没开或 key 为空） */
  trafficKeyed: number
}

export interface ImpactResult {
  success: boolean
  error?: string
  errorKind?: 'usage' | 'io' | 'notfound'
  stages: ImpactStage[]
  /** 真实执行过的命令（可复制到 CI） */
  commands: string[]
  summary: ImpactSummary
  apis: ImpactApi[]
  cases: ImpactCase[]
  /** 贪心算出的最小回归集（用例名，按建议顺序） */
  minimalSet: string[]
  minimalStats: { cases: number; lines: number; totalLines: number }
  risks: ImpactRisk[]
  warnings: string[]
  /** key 粒度自检提示 */
  keyHint?: string
}

// ===== 变更影响分析（源码结构版）=====
// 与上面的覆盖率版互补：不需要 exec，靠新旧源码差异 + 调用图回溯定位受影响接口。

export interface SourceImpactRequest {
  newSourceRoot: string
  oldSourceRoot: string
  since?: number
  maxDepth?: number
}

export interface SourceEndpoint {
  httpMethod: string
  path: string
  className: string
  methodName: string
  symbolKey: string
  locator: string
}

export interface AffectedEndpoint {
  endpoint: SourceEndpoint
  confidence: number
  seed: string
  path: string[]
  edgeTypes: string[]
  cases: string[]
  hits: number
  evidence: string[]
}

export interface AffectedCase {
  caseName: string
  endpoints: string[]
  hits: number
  order: number
}

export interface SourceChange {
  key: string
  kind: 'TYPE' | 'METHOD'
  changeType: 'ADD' | 'DELETE' | 'MODIFY'
  qualifiedName: string
  path: string
  reason: string
}

export interface SourceImpactResult {
  success: boolean
  error?: string
  errorKind?: 'usage' | 'io' | 'notfound'
  stages: ImpactStage[]
  summary: {
    scannedFiles: number
    changedSymbols: number
    changedClasses: number
    endpoints: number
    affectedEndpoints: number
    affectedCases: number
    unmatchedEndpoints: number
  }
  changes: SourceChange[]
  endpoints: SourceEndpoint[]
  affected: AffectedEndpoint[]
  cases: AffectedCase[]
  warnings: string[]
}
