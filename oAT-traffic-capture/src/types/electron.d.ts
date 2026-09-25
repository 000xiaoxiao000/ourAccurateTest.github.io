import type {
  BuiltinPluginId,
  CoverageBackendInfo,
  CoverageConfig,
  CoverageExecInfo,
  PluginInfo,
  ReplayResult,
  TrafficFilterRule,
  TrafficRecord
} from '../types/traffic'
import type { ImpactAnalyzeRequest, ImpactResult } from '../types/impact'
import type { ProbeAgent, ProbeResult, ProbeSummary } from '../types/probe'

export interface CoveragePathProbe {
  path: string
  kind: dir | zip | jar | none
  exists: boolean
  fileCount: number
  packageHits: number
  note: string
}

// ===== Git 源码供给（增量报告的源码输入） =====
export interface GitCapability {
  available: boolean
  bin?: string
  version?: string
  sparseOk: boolean
  worktreeOk: boolean
  reason?: string
  installHint?: string
}

export interface GitCredentials {
  mode: 'system' | 'password' | 'token' | 'ssh'
  username?: string
  secret?: string
  sshKeyPath?: string
  allowInsecureSsl?: boolean
  caFilePath?: string
}

export interface GitRefItem {
  name: string
  sha?: string
  remote?: boolean
}

export interface GitCommit {
  sha: string
  shortSha: string
  author: string
  date: string
  message: string
  refs?: string[]
}

export interface GitRefQuery {
  source: 'local' | 'remote'
  repoPath?: string
  url?: string
  credentials?: GitCredentials
  ref?: string
  limit?: number
  keyword?: string
}

export interface GitPrepareRequest {
  mode: 'local' | 'remote' | 'archive'
  repoPath?: string
  url?: string
  newRef?: string
  oldRef?: string
  credentials?: GitCredentials
  archivePath?: string
  label?: string
  /** 检出落点：留空 = 应用缓存目录；填了就检到该目录（必须为空目录） */
  outDir?: string
}

export interface GitPrepareResult {
  success: boolean
  newSourceRoot?: string
  oldSourceRoot?: string
  resolvedNewRef?: string
  resolvedOldRef?: string
  reused?: boolean
  warnings: string[]
  error?: string
  errorKind?: 'auth' | 'network' | 'notfound' | 'nogit' | 'io'
}

export interface GitRefsResult {
  success: boolean
  branches: GitRefItem[]
  tags: GitRefItem[]
  error?: string
  errorKind?: 'auth' | 'network' | 'notfound' | 'nogit' | 'io'
}

export interface GitCommitsResult {
  success: boolean
  commits: GitCommit[]
  truncated: boolean
  error?: string
  errorKind?: 'auth' | 'network' | 'notfound' | 'nogit' | 'io'
}

export interface CaptureProtocolConfig {
  http: boolean
  https: boolean
  ws: boolean
  wss: boolean
}

export interface RuntimeLogEntry {
  id: number
  timestamp: number
  level: 'log' | 'info' | 'warn' | 'error'
  text: string
}

declare global {
  interface Window {
    electronAPI: {
      startCapture: (caseName: string) => Promise<{ success: boolean; port?: number; error?: string }>
      stopCapture: () => Promise<{ success: boolean }>
      getCaptureState: () => Promise<{ isCapturing: boolean; caseName: string; port: number; recordCount: number; protocols?: CaptureProtocolConfig; coverage: CoverageConfig }>
      getRuntimeLogs: () => Promise<RuntimeLogEntry[]>
      clearRuntimeLogs: () => Promise<{ success: boolean }>
      onRuntimeLogAppended: (callback: (entry: RuntimeLogEntry) => void) => () => void
      onRuntimeLogsCleared: (callback: () => void) => () => void
      showFloatingWindow: () => Promise<{ success: boolean }>
      restoreMainWindow: () => Promise<{ success: boolean }>
      getTrafficRecords: () => Promise<TrafficRecord[]>
      clearTrafficRecords: () => Promise<{ success: boolean }>
      deleteTrafficRecord: (id: string) => Promise<{ success: boolean }>
      listFilterRules: () => Promise<TrafficFilterRule[]>
      saveFilterRules: (rules: TrafficFilterRule[]) => Promise<{ success: boolean }>
      replayRecord: (record: TrafficRecord) => Promise<ReplayResult>
      replayRecords: (records: TrafficRecord[]) => Promise<ReplayResult[]>
      exportRecords: (format: string, records: TrafficRecord[]) => Promise<{ success: boolean; filePath?: string }>
      onTrafficCaptured: (callback: (record: TrafficRecord) => void) => void
      onCaptureStateChanged: (callback: (state: { isCapturing: boolean; caseName: string; port: number; recordCount: number; protocols?: CaptureProtocolConfig; coverage: CoverageConfig }) => void) => void
      getProxyStatus: () => Promise<{ enabled: boolean; port?: number; protocols?: CaptureProtocolConfig }>
      enableSystemProxy: (port: number, protocols: CaptureProtocolConfig) => Promise<{ success: boolean; error?: string }>
      disableSystemProxy: () => Promise<{ success: boolean; error?: string }>
      listSessions: () => Promise<Array<{
        id: string
        case_name: string
        start_time: number
        end_time: number | null
        record_count: number
      }>>
      loadSession: (sessionId: string) => Promise<TrafficRecord[]>
      deleteSession: (sessionId: string) => Promise<{ success: boolean }>
      connectMqtt: (config: {
        id: string
        brokerUrl: string
        topics: string[]
        username?: string
        password?: string
      }) => Promise<{ success: boolean; error?: string }>
      disconnectMqtt: (id: string) => Promise<{ success: boolean }>
      onMqttStatus: (callback: (data: { id: string; status: string; error?: string }) => void) => void
      getCertInfo: () => Promise<{ exists: boolean; certPath?: string; expiresAt?: string }>
      generateCert: () => Promise<{ success: boolean; certPath?: string; keyPath?: string; error?: string }>
      installCert: () => Promise<{ success: boolean; error?: string }>
      uninstallCert: () => Promise<{ success: boolean; error?: string }>
      openCertFolder: () => Promise<void>
      listPlugins: () => Promise<PluginInfo[]>
      reloadPlugins: () => Promise<PluginInfo[]>
      getPluginsPath: () => Promise<string>
      openPluginsFolder: () => Promise<{ success: boolean }>
      installBuiltinPlugin: (pluginId?: BuiltinPluginId) => Promise<PluginInfo[]>
      uninstallBuiltinPlugin: () => Promise<PluginInfo[]>
      uninstallPlugin: (pluginId: string) => Promise<PluginInfo[]>
      setProxyPort: (port: number) => Promise<{ proxyPort: number }>
      // ===== 覆盖率功能 IPC =====
      getCoverageConfig: () => Promise<CoverageConfig>
      setCoverageConfig: (config: CoverageConfig) => Promise<{ success: boolean }>
      listCoverageBackends: () => Promise<CoverageBackendInfo[]>
      resolveClassfiles: (localPath: string) => Promise<{ success: boolean; resolvedPath?: string; error?: string }>
      coverageDump: (opts: { backendId?: string; values?: Record<string, string>; key?: string }) => Promise<{ success: boolean; execs?: CoverageExecInfo[]; error?: string }>
      coveragePreview: (opts: { backendId?: string; values?: Record<string, string>; execs?: string[] }) => Promise<{ success: boolean; text?: string; error?: string }>
      coveragePickPath: (opts: { pick?: 'file' | 'dir' | 'dirOrFile'; title?: string }) => Promise<{ success: boolean; path?: string }>
      coverageKeys: () => Promise<{ success: boolean; keys?: string[]; error?: string }>
      coverageStats: (opts: { limit?: number; agentAddress?: string }) => Promise<{ success: boolean; text?: string; error?: string }>
      coverageDumpclasses: (opts: { zip?: string }) => Promise<{ success: boolean; file?: string; error?: string }>
      coverageSetkey: (opts: { key?: string; clear?: boolean; agentAddress?: string }) => Promise<{ success: boolean; error?: string }>
      coverageRunCommand: (opts: { backendId?: string; commandId: string; values?: Record<string, string>; key?: string; execs?: string[] }) => Promise<{ success: boolean; text?: string; outputs?: string[]; reportDir?: string; hasHtml?: boolean; execs?: CoverageExecInfo[]; error?: string; stdout?: string; stderr?: string }>
      coverageCommandPreview: (opts: { backendId?: string; commandId: string; values?: Record<string, string>; execs?: string[] }) => Promise<{ success: boolean; text?: string; error?: string }>
      coverageReport: (opts: { backendId?: string; values?: Record<string, string>; execs: string[] }) => Promise<{ success: boolean; reportDir?: string; hasHtml?: boolean; error?: string }>
      coverageMerge: (opts: { execs: string[]; destfile: string }) => Promise<{ success: boolean; file?: string; error?: string }>
      coverageExportReport: (opts: { reportDir: string }) => Promise<{ success: boolean; filePath?: string; error?: string }>
      coverageOpenReport: (opts: { reportDir: string }) => Promise<{ success: boolean; error?: string }>
      coverageCheckPath: (opts: { path: string; role: classfiles | sourcefiles; classfilesPath?: string }) => Promise<CoveragePathProbe>
      // ===== Git 源码供给 =====
      coverageGitCapability: (force?: boolean) => Promise<GitCapability>
      coverageGitRefs: (q: GitRefQuery) => Promise<GitRefsResult>
      coverageGitCommits: (q: GitRefQuery) => Promise<GitCommitsResult>
      coverageGitPrepare: (req: GitPrepareRequest) => Promise<GitPrepareResult>
      coverageGitCleanup: (opts?: { all?: boolean }) => Promise<{ success: boolean; freed?: number; error?: string }>
      onCoverageGitLog: (callback: (p: { phase: string; text: string; percent?: number }) => void) => () => void
      // ===== 在线探针 =====
      coverageProbeLast: () => Promise<{ success: boolean; probes: ProbeResult[]; summary: ProbeSummary | null }>
      coverageProbeList: (opts?: { timeoutMs?: number }) => Promise<{ success: boolean; probes?: ProbeResult[]; summary?: ProbeSummary; error?: string }>
      coverageProbeScanRange: (opts?: { hosts?: string; ports?: string; timeoutMs?: number }) => Promise<{ success: boolean; probes?: ProbeResult[]; scanned?: number; hits?: number; truncated?: boolean; autoRegistered?: number; error?: string }>
      coverageProbeTrafficHosts: () => Promise<{ success: boolean; hosts?: Array<{ host: string; ports: number[]; count: number; lastTs: number }>; error?: string }>
      onProbeScanProgress: (callback: (p: { done: number; total: number; finished?: boolean }) => void) => () => void
      coverageProbeRegistry: (opts: { action: 'list' | 'add' | 'remove' | 'update'; id?: string; host?: string; port?: number; agentAddress?: string; label?: string }) => Promise<{ success: boolean; agents?: ProbeAgent[]; error?: string }>
      /** 主进程 20s 心跳推送全量结果：角标 / 状态灯 / 列表共用一份，避免各自重复探测 */
      onCoverageProbeHeartbeat: (callback: (p: { probes: ProbeResult[]; summary: ProbeSummary }) => void) => () => void
      // ===== 影响分析 =====
      coverageImpactAnalyze: (req: ImpactAnalyzeRequest) => Promise<ImpactResult>
      onCoverageImpactLog: (callback: (p: { text: string; percent: number }) => void) => () => void
    }
  }
}

export {}
