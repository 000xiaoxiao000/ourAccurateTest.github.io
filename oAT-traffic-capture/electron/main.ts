import { app, BrowserWindow, ipcMain, dialog, shell, protocol, net } from 'electron'
import * as path from 'node:path'
import * as util from 'node:util'
import * as fs from 'node:fs'
import { pathToFileURL } from 'node:url'
import { createProxyServer } from './proxy.js'
import { disableSystemProxy, enableSystemProxy, getSystemProxyStatus } from './systemProxy.js'
import { generateRootCert, getCertInfo, getProxyCaDir, installCertMacOS, openCertFolder, uninstallCertMacOS } from './certificate.js'
import { connectMqtt, disconnectAllMqtt, disconnectMqtt } from './protocols/mqtt.js'
import { applyCaptureRules } from './filterRules.js'
import { replayRecord } from './replay.js'
import {
  getPluginsPath,
  installBuiltinPlugin,
  listPlugins,
  loadPlugins,
  openPluginsFolder,
  runBeforeSaveHooks,
  runRecordCapturedHooks,
  uninstallBuiltinPlugin,
  uninstallPlugin
} from './plugins/pluginManager.js'
import {
  deleteSession,
  initDatabase,
  listFilterRules,
  listSessions,
  listTrafficHosts,
  loadSessionRecords,
  saveRecord,
  saveFilterRules,
  saveSession,
  updateSessionEndTime
} from './database.js'
import type { CaptureProtocolConfig, CoverageConfig, ProbeAgent, TrafficFilterRule, TrafficRecord } from './types.js'
import * as ExcelJS from 'exceljs'
import * as coverage from './coverage/index.js'

const __dirname = path.join(app.getAppPath(), 'dist-electron')

let mainWindow: BrowserWindow | null = null
let floatingWindow: BrowserWindow | null = null
let proxyServer: any = null
let activeProxyPort = 8888
let configuredProxyPort = 8888
let captureEnabled = false
let currentCaseName = ''
let currentSessionId = ''
const trafficRecords: TrafficRecord[] = []
// ===== 覆盖率采集配置（探针侧 headerkey 归因，零改业务代码） =====
let coverageConfig: CoverageConfig = {
  enabled: false,
  key: '',
  headerName: 'X-Coverage-Key',
  agentAddress: '127.0.0.1:8899',
  backend: 'jacoco',
  classfilesPath: ''
}
let filterRules: TrafficFilterRule[] = []
const DEFAULT_PROXY_PORT = 8888
const MAX_RUNTIME_LOG_LINES = 1000
type RuntimeLogLevel = 'log' | 'info' | 'warn' | 'error'
type RuntimeLogEntry = {
  id: number
  timestamp: number
  level: RuntimeLogLevel
  text: string
}
const runtimeLogs: RuntimeLogEntry[] = []
let runtimeLogId = 0
let enabledProtocols: CaptureProtocolConfig = {
  http: true,
  https: true,
  ws: true,
  wss: true
}
function pushRuntimeLog(level: RuntimeLogLevel, args: unknown[]) {
  const entry = {
    id: ++runtimeLogId,
    timestamp: Date.now(),
    level,
    text: args.length ? util.format(...args) : ''
  }
  runtimeLogs.push(entry)
  if (runtimeLogs.length > MAX_RUNTIME_LOG_LINES) {
    runtimeLogs.splice(0, runtimeLogs.length - MAX_RUNTIME_LOG_LINES)
  }
  mainWindow?.webContents.send('runtime-log-appended', entry)
}

function installRuntimeLogCapture() {
  const originalConsole = {
    log: console.log.bind(console),
    info: console.info.bind(console),
    warn: console.warn.bind(console),
    error: console.error.bind(console)
  }
  ;(['log', 'info', 'warn', 'error'] as RuntimeLogLevel[]).forEach((level) => {
    console[level] = (...args: unknown[]) => {
      pushRuntimeLog(level, args)
      originalConsole[level](...args)
    }
  })
  pushRuntimeLog('info', ['运行日志已启动'])
}

function captureConfigPath(): string {
  return path.join(app.getPath('userData'), 'capture-config.json')
}

function loadCaptureConfig(): void {
  try {
    const configPath = captureConfigPath()
    if (!fs.existsSync(configPath)) return
    const parsed = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as {
      proxyPort?: number
      coverage?: Partial<CoverageConfig>
    }
    const proxyPort = Number(parsed.proxyPort)
    if (Number.isInteger(proxyPort) && proxyPort > 0 && proxyPort <= 65535) {
      configuredProxyPort = proxyPort
    }
    // 覆盖率采集配置持久化：否则重启后 enabled=false、key=''，抓包会静默不注入
    // X-Coverage-Key → 流量记录无归属 key，影响分析反查不到接口/用例。
    if (parsed.coverage && typeof parsed.coverage === 'object') {
      const c = parsed.coverage
      coverageConfig.enabled = c.enabled === true
      if (typeof c.key === 'string') coverageConfig.key = c.key
      if (typeof c.headerName === 'string' && c.headerName.trim()) coverageConfig.headerName = c.headerName.trim()
      if (typeof c.agentAddress === 'string') coverageConfig.agentAddress = c.agentAddress
      if (typeof c.backend === 'string' && c.backend) coverageConfig.backend = c.backend
      if (typeof c.classfilesPath === 'string') coverageConfig.classfilesPath = c.classfilesPath
      // 手工登记的探针：本机扫描看不到容器/远端端口，登记后要跨重启保留
      if (Array.isArray(c.agents)) {
        coverageConfig.agents = c.agents
          .filter((a) => a && typeof a.host === 'string' && Number.isInteger(Number(a.port)))
          .map((a) => ({ id: String(a.id ?? `${a.host}:${a.port}`), host: a.host, port: Number(a.port), label: a.label }))
      }
    }
  } catch {
    configuredProxyPort = DEFAULT_PROXY_PORT
  }
}

function persistCaptureConfig(): void {
  fs.writeFileSync(
    captureConfigPath(),
    JSON.stringify({ proxyPort: configuredProxyPort, coverage: coverageConfig }, null, 2),
    'utf-8'
  )
}

function saveProxyPortConfig(port: number): number {
  const proxyPort = Number(port)
  if (!Number.isInteger(proxyPort) || proxyPort <= 0 || proxyPort > 65535) {
    throw new Error('系统代理端口必须是 1-65535 的整数')
  }
  configuredProxyPort = proxyPort
  persistCaptureConfig()
  return configuredProxyPort
}

// ===== 在线探针：登记簿 + 后台心跳 =====
// 说明：本机扫描（lsof）只能发现本机端口，容器 / 远端服务的端口看不到（用户环境 docker/k8s 且无 root），
// 所以「手工登记」是必选项而非可选项。登记簿持久化在 capture-config.json 的 coverage.agents。
function registeredAgents(): ProbeAgent[] {
  return Array.isArray(coverageConfig.agents) ? coverageConfig.agents : []
}

function setRegisteredAgents(list: ProbeAgent[]): ProbeAgent[] {
  coverageConfig.agents = list
  try {
    persistCaptureConfig()
  } catch (e) {
    console.warn('[探针登记] 持久化失败（不影响本次运行）:', e)
  }
  broadcastCaptureState()
  return list
}

function parseAddress(addr: string): { host: string; port: number } | null {
  const s = String(addr ?? '').trim()
  if (!s) return null
  const i = s.lastIndexOf(':')
  if (i <= 0) return null
  const port = Number(s.slice(i + 1))
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return null
  return { host: s.slice(0, i), port }
}

/** 参与周期性探测的目标：已登记的探针 + 当前配置的 Agent 地址（同一地址只算一次，登记项优先保留） */
function knownProbeTargets(): Array<{ id: string; host: string; port: number; label?: string; source: 'config' | 'registered' }> {
  const out: Array<{ id: string; host: string; port: number; label?: string; source: 'config' | 'registered' }> = []
  const seen = new Set<string>()
  // 登记项在前：若登记地址恰好就是当前配置地址，保留登记卡片（它带「编辑/取消登记」操作），
  // 配置卡片消失但该地址仍在列表里且带「当前」徽标 —— 否则登记项会被配置项顶掉，失去取消登记的入口
  for (const a of registeredAgents()) {
    const k = `${a.host}:${a.port}`
    if (seen.has(k)) continue
    seen.add(k)
    out.push({ id: a.id || k, host: a.host, port: a.port, label: a.label, source: 'registered' })
  }
  const cfg = parseAddress(coverageConfig.agentAddress)
  if (cfg && !seen.has(`${cfg.host}:${cfg.port}`)) {
    // label 不设：卡片直接以地址为主标题，来源标签「当前配置」由 UI 按 source 显示
    out.push({ id: 'config', host: cfg.host, port: cfg.port, source: 'config' })
  }
  return out
}

/**
 * 心跳事件：main → renderer。
 * ⚠️ 推全量结果（不止摘要）：左导航角标、覆盖率页的地址框状态灯、探针列表都消费同一份，
 *    避免每个组件各自发起一轮探测 —— agent tcpserver 是串行 handle，重复连接会互相排队。
 */
let lastProbeHeartbeat: { probes: coverage.ProbeResult[]; summary: coverage.ProbeSummary } | null = null

function sendProbeHeartbeat(p: { probes: coverage.ProbeResult[]; summary: coverage.ProbeSummary }) {
  lastProbeHeartbeat = p
  try { mainWindow?.webContents.send('coverage-probe-heartbeat', p) } catch { /* 窗口已关闭则忽略 */ }
}

const PROBE_HEARTBEAT_MS = 20000
let probeHeartbeatTimer: ReturnType<typeof setInterval> | undefined

/** 上一轮心跳是否还在跑：登记很多探针时一轮可能超过心跳间隔，必须跳过而不是叠加排队 */
let probeHeartbeatRunning = false

async function probeHeartbeatOnce(): Promise<{ probes: coverage.ProbeResult[]; summary: coverage.ProbeSummary }> {
  const targets = knownProbeTargets()
  if (!targets.length) {
    const empty = { probes: [], summary: { total: 0, online: 0, warning: 0, vanilla: 0, offline: 0, checkedAt: Date.now() } }
    sendProbeHeartbeat(empty)
    return empty
  }
  // 追尾保护：上一轮没结束就直接放弃本轮（下一轮 20s 后再来），否则超时目标多时会越积越多
  if (probeHeartbeatRunning) return { probes: lastProbeHeartbeat?.probes ?? [], summary: lastProbeHeartbeat?.summary ?? { total: 0, online: 0, warning: 0, vanilla: 0, offline: 0, checkedAt: Date.now() } }
  probeHeartbeatRunning = true
  try {
    // ⚠️ 心跳只能用「stats 命令」（无副作用），绝不能用 dump —— 带 --reset 会清掉用户正在攒的覆盖数据
    const { probes, summary } = await coverage.probeAgents({ agents: targets, timeoutMs: 1500, concurrency: 12 })
    sendProbeHeartbeat({ probes, summary })
    return { probes, summary }
  } finally {
    probeHeartbeatRunning = false
  }
}

/**
 * 低频后台心跳，让左导航角标在任何页面都准确（探测不能只在覆盖率面板打开时才做）。
 * 只探测「已登记 + 当前配置」，本机扫描留给用户手动点，避免后台偷偷扫端口。
 */
function startProbeHeartbeat(): void {
  if (probeHeartbeatTimer) return
  probeHeartbeatTimer = setInterval(() => {
    probeHeartbeatOnce().catch(() => { /* 心跳失败不打扰用户 */ })
  }, PROBE_HEARTBEAT_MS)
  probeHeartbeatTimer.unref?.()
  probeHeartbeatOnce().catch(() => {})
}

function getCaptureState() {
  return {
    isCapturing: captureEnabled,
    caseName: currentCaseName,
    port: proxyServer?.httpPort ?? configuredProxyPort ?? DEFAULT_PROXY_PORT,
    recordCount: trafficRecords.length,
    protocols: enabledProtocols,
    coverage: coverageConfig
  }
}

function broadcastCaptureState() {
  const state = getCaptureState()
  mainWindow?.webContents.send('capture-state-changed', state)
  floatingWindow?.webContents.send('capture-state-changed', state)
}

function closeProxyServer() {
  if (proxyServer) {
    proxyServer.close()
    proxyServer = null
  }
}

async function acceptCapturedRecord(record: TrafficRecord) {
  const afterPlugins = await runRecordCapturedHooks({
    ...record,
    caseName: currentCaseName,
    source: record.source ?? 'capture'
  })
  if (!afterPlugins) {
    console.info('[捕获] 丢弃（插件 hook 拦截） %s %s', record.method, record.url)
    return
  }
  if (!captureEnabled) return
  const afterRules = captureEnabled ? applyCaptureRules(afterPlugins, filterRules) : afterPlugins
  if (!afterRules) {
    console.info('[捕获] 丢弃（过滤规则命中） %s %s', record.method, record.url)
    return
  }
  const beforeSave = await runBeforeSaveHooks(afterRules)
  if (!beforeSave) return

  trafficRecords.push(beforeSave)
  saveRecord(beforeSave, captureEnabled ? currentSessionId : undefined)
  console.info(
    '[捕获] %s %s → %s (%s ms)%s 来源=%s',
    beforeSave.method,
    beforeSave.url,
    beforeSave.statusCode ?? '-',
    beforeSave.duration ?? '-',
    beforeSave.coverageKey ? ` [${coverageConfig.headerName}=${beforeSave.coverageKey}]` : '',
    beforeSave.source ?? 'capture'
  )
  mainWindow?.webContents.send('traffic-captured', beforeSave)
  floatingWindow?.webContents.send('traffic-captured', beforeSave)
  broadcastCaptureState()
}

function startProxyServer(server: any, port: number): Promise<number> {
  return new Promise((resolve, reject) => {
    server.listen({ port }, (error?: Error) => {
      if (error) {
        reject(error)
        return
      }
      resolve(server.httpPort ?? port)
    })
  })
}

async function ensureProxyServer(): Promise<number> {
  const configuredPort = Number(configuredProxyPort) > 0 ? Number(configuredProxyPort) : DEFAULT_PROXY_PORT
  if (proxyServer && activeProxyPort !== configuredPort) {
    closeProxyServer()
  }
  if (!proxyServer) {
    if (!getCertInfo().exists) {
      generateRootCert()
    }
    proxyServer = createProxyServer((record: TrafficRecord) => {
      acceptCapturedRecord(record)
    }, getProxyCaDir(), enabledProtocols, coverageConfig)

    await startProxyServer(proxyServer, configuredPort)
    activeProxyPort = proxyServer.httpPort ?? configuredPort
  } else {
    proxyServer.setEnabledProtocols?.(enabledProtocols)
  }

  return proxyServer.httpPort ?? configuredPort
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 920,
    height: 620,
    minWidth: 780,
    minHeight: 500,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true
    }
  })

  mainWindow.webContents.on('did-finish-load', () => {
    mainWindow?.webContents.setZoomFactor(0.82)
  })

  loadAppWindow(mainWindow)

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function loadAppWindow(window: BrowserWindow, query = '') {
  if (process.env.NODE_ENV === 'development') {
    window.loadURL(`http://localhost:5173${query}`)
  } else {
    window.loadFile(path.join(__dirname, '../dist/index.html'), query ? { query: { floating: '1' } } : undefined)
  }
}

function createFloatingWindow() {
  if (floatingWindow) {
    floatingWindow.show()
    floatingWindow.focus()
    return
  }

  floatingWindow = new BrowserWindow({
    width: 260,
    height: 172,
    minWidth: 260,
    minHeight: 172,
    maxWidth: 260,
    maxHeight: 172,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true
    }
  })

  floatingWindow.setAlwaysOnTop(true, 'floating')
  loadAppWindow(floatingWindow, '?floating=1')

  floatingWindow.on('closed', () => {
    floatingWindow = null
  })
}

function restoreMainWindow() {
  if (!mainWindow) {
    createWindow()
  }
  mainWindow?.show()
  mainWindow?.focus()
  floatingWindow?.close()
}

// ===== oat-report:// 协议：把本地 JaCoCo 报告文件流进 iframe =====
// 背景：dev 下页面从 http://localhost:5173 加载，http 父页的 iframe 加载 file:// 会被 webSecurity 拦成白屏；
// 注册自定义协议后 iframe 用 oat-report://local/<绝对路径> 即可正常渲染（dev 与打包后都一致）。
protocol.registerSchemesAsPrivileged([
  { scheme: 'oat-report', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }
])

function registerReportProtocol() {
  protocol.handle('oat-report', (request) => {
    try {
      // oat-report://local/Users/xxx/report/index.html → /Users/xxx/report/index.html
      const u = new URL(request.url)
      if (u.host !== 'local') return new Response('bad host', { status: 400 })
      const filePath = decodeURIComponent(u.pathname)
      if (!path.isAbsolute(filePath) || filePath.includes('\0')) return new Response('bad path', { status: 400 })
      return net.fetch(pathToFileURL(filePath).toString())
    } catch (e: any) {
      return new Response('oat-report error: ' + (e?.message ?? e), { status: 500 })
    }
  })
}

app.whenReady().then(async () => {
  installRuntimeLogCapture()
  initDatabase()
  registerReportProtocol()
  filterRules = listFilterRules()
  loadCaptureConfig()
  await loadPlugins()
  createWindow()
  startProbeHeartbeat()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  closeProxyServer()
  disconnectAllMqtt()
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

ipcMain.handle('start-capture', async (_event, caseName: string) => {
  try {
    const port = await ensureProxyServer()

    currentCaseName = caseName
    captureEnabled = true
    currentSessionId = `session-${Date.now()}`
    saveSession({ id: currentSessionId, caseName, startTime: Date.now() })
    broadcastCaptureState()
    console.info('[采集] 开始采集 用例=%s 会话=%s 代理端口=%d', caseName, currentSessionId, port)

    return { success: true, port }
  } catch (error: any) {
    captureEnabled = false
    proxyServer = null
    broadcastCaptureState()
    console.error('[采集] 开始采集失败:', error?.message ?? String(error))
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('stop-capture', async () => {
  captureEnabled = false
  if (currentSessionId) {
    updateSessionEndTime(currentSessionId, Date.now())
  }
  broadcastCaptureState()
  console.info('[采集] 停止采集 会话=%s', currentSessionId ?? '-')
  return { success: true }
})

ipcMain.handle('get-capture-state', async () => getCaptureState())

ipcMain.handle('get-runtime-logs', async () => runtimeLogs)

ipcMain.handle('clear-runtime-logs', async () => {
  runtimeLogs.length = 0
  mainWindow?.webContents.send('runtime-logs-cleared')
  return { success: true }
})

ipcMain.handle('show-floating-window', async () => {
  createFloatingWindow()
  mainWindow?.hide()
  return { success: true }
})

ipcMain.handle('restore-main-window', async () => {
  restoreMainWindow()
  return { success: true }
})

ipcMain.handle('get-traffic-records', async () => {
  return trafficRecords
})

ipcMain.handle('clear-traffic-records', async () => {
  console.info('[记录] 清空当前流量记录，共 %d 条', trafficRecords.length)
  trafficRecords.length = 0
  broadcastCaptureState()
  return { success: true }
})

ipcMain.handle('list-filter-rules', async () => filterRules)

ipcMain.handle('save-filter-rules', async (_event, rules: TrafficFilterRule[]) => {
  filterRules = rules
  saveFilterRules(filterRules)
  console.info('[过滤规则] 已保存 %d 条规则', rules.length)
  return { success: true }
})

ipcMain.handle('delete-traffic-record', async (_event, id: string) => {
  const index = trafficRecords.findIndex(r => r.id === id)
  if (index !== -1) {
    trafficRecords.splice(index, 1)
    broadcastCaptureState()
    return { success: true }
  }
  return { success: false }
})

ipcMain.handle('export-records', async (_event, format: string, records: TrafficRecord[]) => {
  console.info('[导出] 开始导出 %d 条记录，格式=%s', records.length, format)
  try {
  if (format === 'json') {
    const { filePath } = await dialog.showSaveDialog({
      defaultPath: `traffic-export-${Date.now()}.json`,
      filters: [{ name: 'JSON', extensions: ['json'] }]
    })
    if (!filePath) return { success: false }
    fs.writeFileSync(filePath, JSON.stringify(records, null, 2), 'utf-8')
    console.info('[导出] JSON 完成: %s', filePath)
    return { success: true, filePath }
  }

  if (format === 'csv') {
    const { filePath } = await dialog.showSaveDialog({
      defaultPath: `traffic-export-${Date.now()}.csv`,
      filters: [{ name: 'CSV', extensions: ['csv'] }]
    })
    if (!filePath) return { success: false }
    const headers = ['用例名称', '方法', 'URL', '协议', '状态码', '耗时(ms)', '时间']
    const rows = records.map(r => [
      r.caseName, r.method, r.url, r.protocol,
      String(r.statusCode), String(r.duration),
      new Date(r.timestamp).toLocaleString('zh-CN')
    ])
    const csv = [headers, ...rows].map(row => row.map(c => `"${c}"`).join(',')).join('\n')
    fs.writeFileSync(filePath, '\uFEFF' + csv, 'utf-8')
    console.info('[导出] CSV 完成: %s', filePath)
    return { success: true, filePath }
  }

  if (format === 'excel') {
    const { filePath } = await dialog.showSaveDialog({
      defaultPath: `traffic-export-${Date.now()}.xlsx`,
      filters: [{ name: 'Excel', extensions: ['xlsx'] }]
    })
    if (!filePath) return { success: false }

    const workbook = new ExcelJS.Workbook()
    workbook.creator = 'oAT 流量采集器'
    workbook.created = new Date()

    const sheet = workbook.addWorksheet('流量记录')
    sheet.columns = [
      { header: '用例名称', key: 'caseName', width: 20 },
      { header: '方法', key: 'method', width: 10 },
      { header: 'URL', key: 'url', width: 60 },
      { header: '协议', key: 'protocol', width: 10 },
      { header: '状态码', key: 'statusCode', width: 10 },
      { header: '耗时(ms)', key: 'duration', width: 12 },
      { header: '时间', key: 'time', width: 22 },
      { header: '请求体', key: 'requestBody', width: 40 },
      { header: '响应体', key: 'responseBody', width: 40 }
    ]

    // Style header row
    const headerRow = sheet.getRow(1)
    headerRow.font = { bold: true, color: { argb: 'FFFFFFFF' } }
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF667EEA' } }
    headerRow.alignment = { vertical: 'middle', horizontal: 'center' }
    headerRow.height = 22

    records.forEach(r => {
      sheet.addRow({
        caseName: r.caseName,
        method: r.method,
        url: r.url,
        protocol: r.protocol,
        statusCode: r.statusCode,
        duration: r.duration,
        time: new Date(r.timestamp).toLocaleString('zh-CN'),
        requestBody: r.requestBody || '',
        responseBody: r.responseBody || ''
      })
    })

    await workbook.xlsx.writeFile(filePath)
    console.info('[导出] Excel 完成: %s', filePath)
    return { success: true, filePath }
  }

  console.warn('[导出] 不支持的导出格式: %s', format)
  return { success: false }
  } catch (error: any) {
    console.error('[导出] 失败 格式=%s:', format, error?.message ?? String(error))
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('replay-record', async (_event, record: TrafficRecord) => {
  console.info('[重放] 开始 %s %s', record.method, record.url)
  const result = await replayRecord(record)
  if (result.record) {
    console.info('[重放] 完成 %s %s → %s (%s ms)', result.record.method, result.record.url, result.record.statusCode ?? '-', result.record.duration ?? '-')
  } else {
    console.warn('[重放] 失败 %s %s: %s', record.method, record.url, result.error ?? '未知错误')
  }
  if (result.record) {
    result.record.caseName = currentCaseName || record.caseName
    trafficRecords.push(result.record)
    saveRecord(result.record, currentSessionId || undefined)
    mainWindow?.webContents.send('traffic-captured', result.record)
    floatingWindow?.webContents.send('traffic-captured', result.record)
    broadcastCaptureState()
  }
  return result
})

ipcMain.handle('replay-records', async (_event, records: TrafficRecord[]) => {
  console.info('[重放] 批量开始 共 %d 条', records.length)
  const results = []
  for (const record of records) {
    const result = await replayRecord(record)
    if (result.record) {
      result.record.caseName = currentCaseName || record.caseName
      trafficRecords.push(result.record)
      saveRecord(result.record, currentSessionId || undefined)
      mainWindow?.webContents.send('traffic-captured', result.record)
      floatingWindow?.webContents.send('traffic-captured', result.record)
    }
    results.push(result)
  }
  const ok = results.filter((r) => r.record).length
  console.info('[重放] 批量完成 成功=%d 失败=%d', ok, results.length - ok)
  broadcastCaptureState()
  return results
})

ipcMain.handle('get-proxy-status', async () => {
  try {
    const status = await getSystemProxyStatus()
    if (status.protocols) {
      enabledProtocols = { ...enabledProtocols, ...status.protocols }
    }
    return { ...status, protocols: enabledProtocols }
  } catch (error: any) {
    return { enabled: false, protocols: enabledProtocols, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('enable-system-proxy', async (_event, port: number, protocols?: CaptureProtocolConfig) => {
  try {
    if (Number(port) > 0 && Number(port) !== configuredProxyPort) {
      saveProxyPortConfig(Number(port))
      closeProxyServer()
    }
    enabledProtocols = { ...enabledProtocols, ...(protocols ?? {}) }
    const proxyPort = await ensureProxyServer()
    await enableSystemProxy({
      port: proxyPort || port,
      bypass: ['localhost', '127.0.0.1', '*.local'],
      protocols: enabledProtocols
    })
    broadcastCaptureState()
    console.info('[系统代理] 已开启 端口=%d 协议=%s', proxyPort || port, Object.entries(enabledProtocols).filter(([, v]) => v).map(([k]) => k).join('/'))
    return { success: true }
  } catch (error: any) {
    console.error('[系统代理] 开启失败:', error?.message ?? String(error))
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('disable-system-proxy', async () => {
  try {
    await disableSystemProxy()
    console.info('[系统代理] 已关闭')
    return { success: true }
  } catch (error: any) {
    console.error('[系统代理] 关闭失败:', error?.message ?? String(error))
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('list-sessions', async () => listSessions())

ipcMain.handle('load-session', async (_event, sessionId: string) => {
  const records = loadSessionRecords(sessionId)
  trafficRecords.length = 0
  trafficRecords.push(...records)
  console.info('[会话] 加载 %s，共 %d 条记录', sessionId, records.length)
  broadcastCaptureState()
  return records
})

ipcMain.handle('delete-session', async (_event, sessionId: string) => {
  deleteSession(sessionId)
  console.info('[会话] 删除 %s', sessionId)
  return { success: true }
})

ipcMain.handle('connect-mqtt', async (_event, config) => {
  try {
    console.info('[MQ] 连接 %s://%s:%s topic=%s', config.protocol ?? 'mqtt', config.host, config.port, config.topic ?? '-')
    connectMqtt(
      config,
      (record) => {
        acceptCapturedRecord(record)
      },
      (id, status, error) => {
        mainWindow?.webContents.send('mqtt-status', { id, status, error })
      }
    )
    return { success: true }
  } catch (error: any) {
    return { success: false, error: error.message }
  }
})

ipcMain.handle('disconnect-mqtt', async (_event, id: string) => {
  console.info('[MQ] 断开连接 id=%s', id)
  disconnectMqtt(id)
  return { success: true }
})

ipcMain.handle('get-cert-info', async () => getCertInfo())

ipcMain.handle('generate-cert', async () => {
  try {
    closeProxyServer()
    captureEnabled = false
    const result = generateRootCert()
    broadcastCaptureState()
    return { success: true, ...result }
  } catch (error: any) {
    return { success: false, error: error.message }
  }
})

ipcMain.handle('install-cert', async () => {
  const info = getCertInfo()
  if (!info.certPath) return { success: false, error: '证书不存在，请先生成' }
  return await installCertMacOS(info.certPath)
})

ipcMain.handle('uninstall-cert', async () => {
  closeProxyServer()
  captureEnabled = false
  broadcastCaptureState()
  return uninstallCertMacOS()
})

ipcMain.handle('open-cert-folder', async () => {
  const info = getCertInfo()
  if (info.certPath) openCertFolder(info.certPath)
})

ipcMain.handle('list-plugins', async () => listPlugins())

ipcMain.handle('reload-plugins', async () => loadPlugins())

ipcMain.handle('get-plugins-path', async () => getPluginsPath())

ipcMain.handle('open-plugins-folder', async () => {
  openPluginsFolder()
  return { success: true }
})

ipcMain.handle('install-builtin-plugin', async (_event, pluginId?: 'traffic-cleanup-plugin' | 'all') => installBuiltinPlugin(pluginId))

ipcMain.handle('uninstall-builtin-plugin', async () => uninstallBuiltinPlugin())

ipcMain.handle('uninstall-plugin', async (_event, pluginId: string) => uninstallPlugin(pluginId))

ipcMain.handle('set-proxy-port', async (_event, port: number) => {
  const previousPort = configuredProxyPort
  const nextPort = saveProxyPortConfig(port)
  console.info('[代理端口] %s → %d%s', previousPort, nextPort, previousPort !== nextPort ? '（代理服务器将重启）' : '')
  if (previousPort !== nextPort) {
    closeProxyServer()
  }
  broadcastCaptureState()
  return { proxyPort: nextPort }
})

// ===== 覆盖率功能 handlers =====
ipcMain.handle('get-coverage-config', async () => coverageConfig)

ipcMain.handle('set-coverage-config', async (_event, config: CoverageConfig) => {
  // 原地修改，保持对象引用不变（代理闭包持有该引用，需实时感知 enabled/key 变化）
  Object.assign(coverageConfig, config)
  try {
    persistCaptureConfig()
  } catch (e) {
    console.warn('[覆盖率配置] 持久化失败（不影响本次运行）:', e)
  }
  broadcastCaptureState()
  // classfiles 只在生成报告时使用，与代理注入无关，不进这条日志
  console.info('[覆盖率配置] enabled=%s key=%s 头=%s agent=%s 后端=%s',
    coverageConfig.enabled, coverageConfig.key || '(空)', coverageConfig.headerName, coverageConfig.agentAddress,
    coverageConfig.backend || '(默认)')
  return { success: true }
})

ipcMain.handle('list-coverage-backends', async () => {
  const { listBackends } = await import('./coverage/backends.js')
  return listBackends()
})

ipcMain.handle('resolve-classfiles', async (_event, localPath: string) => coverage.resolveClassfiles(localPath))

ipcMain.handle('coverage-dump', async (_event, opts: any) =>
  coverage.dumpExecs(coverageConfig, opts?.backendId ?? coverageConfig.backend, opts?.values ?? {}, opts?.key ?? coverageConfig.key))

ipcMain.handle('coverage-preview', async (_event, opts: any) =>
  coverage.previewCommand(coverageConfig, opts?.backendId ?? coverageConfig.backend, opts?.values ?? {}, opts?.execs ?? []))

ipcMain.handle('coverage-keys', async () => coverage.listKeys(coverageConfig))

// 探针卡片允许临时改连某个探针（opts.agentAddress），不必先把它设成当前配置
ipcMain.handle('coverage-stats', async (_event, opts: any) =>
  coverage.getStats(opts?.agentAddress ? { ...coverageConfig, agentAddress: opts.agentAddress } : coverageConfig, opts ?? {}))

ipcMain.handle('coverage-dumpclasses', async (_event, opts: any) =>
  coverage.dumpClasses(opts?.agentAddress ? { ...coverageConfig, agentAddress: opts.agentAddress } : coverageConfig, opts ?? {}))

ipcMain.handle('coverage-setkey', async (_event, opts: any) =>
  coverage.setKey(opts?.agentAddress ? { ...coverageConfig, agentAddress: opts.agentAddress } : coverageConfig, opts ?? {}))

ipcMain.handle('coverage-run-command', async (_event, opts: any) =>
  coverage.runCommand(coverageConfig, opts?.backendId ?? coverageConfig.backend, opts?.commandId ?? '', opts?.values ?? {}, opts?.key ?? coverageConfig.key, opts?.execs ?? []))

ipcMain.handle('coverage-command-preview', async (_event, opts: any) =>
  coverage.commandPreview(coverageConfig, opts?.backendId ?? coverageConfig.backend, opts?.commandId ?? '', opts?.values ?? {}, opts?.execs ?? []))

ipcMain.handle('coverage-report', async (_event, opts: any) =>
  coverage.generateReport(coverageConfig, opts?.backendId ?? coverageConfig.backend, opts?.values ?? {}, opts?.execs ?? []))

ipcMain.handle('coverage-merge', async (_event, opts: any) => coverage.mergeExecs(coverageConfig, opts ?? {}))

ipcMain.handle('coverage-export-report', async (_event, opts: any) => coverage.exportReport(opts ?? {}))
ipcMain.handle('coverage-open-report', async (_event, opts: any) => {
  try {
    const dir = opts?.reportDir
    if (!dir) return { success: false, error: '缺少报告目录' }
    if (!fs.existsSync(dir)) return { success: false, error: '报告目录不存在: ' + dir + '（请先生成报告）' }
    const indexHtml = path.join(dir, 'index.html')
    if (!fs.existsSync(indexHtml)) return { success: false, error: '报告中没有 index.html（请确认生成时勾选了 HTML 报告）: ' + dir }
    // file:// 无法用 window.open 打开（Electron 默认拦截），改系统默认浏览器/访达打开
    const err = await shell.openPath(indexHtml)
    return err ? { success: false, error: err } : { success: true }
  } catch (e) {
    return { success: false, error: (e as Error)?.message || String(e) }
  }
})

ipcMain.handle('coverage-pick-path', async (_event, opts: any) => {
  const properties: ('openFile' | 'openDirectory')[] = opts?.pick === 'dir' ? ['openDirectory'] : ['openFile']
  // 目录或归档（zip/jar）都能当 classfiles：同时允许选文件与目录
  if (opts?.pick === 'dirOrFile') properties.push('openFile')
  const filters = opts?.pick === 'dirOrFile' ? [{ name: '构建产物', extensions: ['zip', 'jar', 'war'] }] : undefined
  const result = await dialog.showOpenDialog({ properties, filters, title: opts?.title ?? '选择路径' })
  if (result.canceled || result.filePaths.length === 0) return { success: true, path: '' }
  return { success: true, path: result.filePaths[0] }
})

// 单路径即时校验（classfiles 数 .class；源码根验包结构可达性）
ipcMain.handle('coverage-check-path', async (_event, opts: any) => coverage.checkPath(opts ?? {}))

// ===== Git 源码供给（增量报告的源码输入：新版/旧版源码；classfiles 仍从构建侧拿） =====
/** 进度事件：main → renderer（沿用既有 webContents.send 机制） */
function sendGitLog(p: { phase: string; text: string; percent?: number }) {
  try { mainWindow?.webContents.send('coverage-git-log', p) } catch { /* 窗口已关闭则忽略 */ }
}
ipcMain.handle('coverage-git-capability', async (_event, force?: boolean) => coverage.gitCapability(!!force))
ipcMain.handle('coverage-git-refs', async (_event, q: any) => coverage.gitRefs(q ?? {}))
ipcMain.handle('coverage-git-commits', async (_event, q: any) => coverage.gitCommits(q ?? {}, sendGitLog))
ipcMain.handle('coverage-git-prepare', async (_event, req: any) => coverage.gitPrepare(req ?? {}, sendGitLog))
ipcMain.handle('coverage-git-cleanup', async (_event, opts: any) => coverage.gitCleanup(opts ?? {}))

// ===== 影响分析：变更行 ∩ 谁跑过 = 受影响的接口 / 用例 =====
function sendImpactLog(p: { text: string; percent: number }) {
  try { mainWindow?.webContents.send('coverage-impact-log', p) } catch { /* 窗口已关闭则忽略 */ }
}
ipcMain.handle('coverage-impact-analyze', async (_event, req: any) => {
  try {
    return await coverage.impactAnalyze(req ?? {}, sendImpactLog)
  } catch (e: any) {
    // 绝不裸崩主进程：数据库不可用 / CLI 缺失都转成结构化错误
    return { success: false, error: String(e?.message ?? e), errorKind: 'io' }
  }
})

// ===== 在线探针：发现 / 探活 / 登记 =====
// 协议侧用 Node 原生 socket（1~10ms，超时可控），不用 CLI 子进程：
// 子进程要启 JVM（几百 ms~1s）批量探测会卡死，且 CLI 的 connect() 没设超时，不可达 IP 会挂很久。
// 渲染进程首次挂载时补一次心跳结果（避免启动瞬间推的那帧还没窗口，要白等 20s）
ipcMain.handle('coverage-probe-last', async () => ({
  success: true,
  probes: lastProbeHeartbeat?.probes ?? [],
  summary: lastProbeHeartbeat?.summary ?? null
}))

ipcMain.handle('coverage-probe-list', async (_event, opts: any) => {
  try {
    const targets = knownProbeTargets()
    const { probes, summary } = await coverage.probeAgents({
      agents: targets,
      timeoutMs: Number(opts?.timeoutMs) || 1500
    })
    // 显式刷新也推一份，让左导航角标与状态灯立即跟上
    sendProbeHeartbeat({ probes, summary })
    return { success: true, probes, summary }
  } catch (e: any) {
    return { success: false, error: String(e?.message ?? e) }
  }
})

/**
 * 远端 / 网段发现：对「主机 × 端口区间」批量握手，只留确认是探针的。
 * 判定靠探针协议本身（连上 → 回 exec 头），不需要对方开放任何健康检查接口，
 * 所以 HTTP / MySQL / redis 这些普通端口会被自然淘汰。
 */
ipcMain.handle('coverage-probe-scan-range', async (_event, opts: any) => {
  try {
    let lastProgress = 0
    const res = await coverage.scanRange({
      hostExpr: String(opts?.hosts ?? ''),
      portExpr: String(opts?.ports ?? ''),
      timeoutMs: Number(opts?.timeoutMs) || 1200,
      onProgress: (done: number, total: number) => {
        // 限流推送：每 5% 或每 20 个推一次，避免几千个目标把 IPC 打满
        if (done - lastProgress < Math.max(20, Math.floor(total / 20))) return
        lastProgress = done
        try { mainWindow?.webContents.send('coverage-probe-scan-progress', { done, total }) } catch { /* 窗口已关闭 */ }
      }
    })
    try { mainWindow?.webContents.send('coverage-probe-scan-progress', { done: res.scanned, total: res.scanned, finished: true }) } catch { /* ignore */ }

    // 自动登记：命中里「确认是 xiaoxiao 探针」的（online/warning）直接进登记簿。
    // 官方 JaCoCo（vanilla）不自动收——它不是本工具能驱动的探针。
    let autoRegistered = 0
    if (coverageConfig.probeAutoRegister !== false) {
      const list = registeredAgents()
      for (const p of res.probes) {
        if (p.status !== 'online' && p.status !== 'warning') continue
        if (list.some((a) => a.host === p.host && a.port === p.port)) continue
        list.push({ id: `reg-${Date.now()}-${p.port}`, host: p.host, port: p.port })
        setRegisteredAgents(list)
        ;(p as { source: string }).source = 'registered'
        autoRegistered++
      }
    }
    console.info('[探针发现] 扫描 %d 个目标，命中 %d 个探针，自动登记 %d 个', res.scanned, res.hits, autoRegistered)
    return { success: true, probes: res.probes, scanned: res.scanned, hits: res.hits, truncated: res.truncated, autoRegistered, error: res.error }
  } catch (e: any) {
    return { success: false, error: String(e?.message ?? e) }
  }
})

/** 已抓流量里出现过的后端主机 → 远端扫描的候选来源（用户不用手打 IP） */
ipcMain.handle('coverage-probe-traffic-hosts', async () => {
  try {
    return { success: true, hosts: listTrafficHosts() }
  } catch (e: any) {
    return { success: false, error: String(e?.message ?? e) }
  }
})

ipcMain.handle('coverage-probe-registry', async (_event, opts: any) => {
  try {
    const action = String(opts?.action ?? 'list')
    if (action === 'list') return { success: true, agents: registeredAgents() }
    if (action === 'add') {
      const parsed = parseAddress(opts?.host ? `${opts.host}:${opts.port}` : String(opts?.agentAddress ?? ''))
      if (!parsed) return { success: false, error: '地址格式应为 host:port（如 10.0.0.7:8899）' }
      const list = registeredAgents()
      if (list.some((a) => a.host === parsed.host && a.port === parsed.port)) {
        return { success: false, error: '该探针已登记：' + `${parsed.host}:${parsed.port}` }
      }
      const agent: ProbeAgent = {
        id: `reg-${Date.now()}`,
        host: parsed.host,
        port: parsed.port,
        label: opts?.label ? String(opts.label).trim() : undefined
      }
      list.push(agent)
      setRegisteredAgents(list)
      console.info('[探针登记] 新增 %s:%d label=%s', agent.host, agent.port, agent.label ?? '(无)')
      return { success: true, agents: list }
    }
    if (action === 'remove') {
      const id = String(opts?.id ?? '')
      const list = registeredAgents().filter((a) => a.id !== id)
      setRegisteredAgents(list)
      return { success: true, agents: list }
    }
    if (action === 'update') {
      // 编辑已登记探针：host/port/label 都可改；地址变更要做查重（不能改成和另一条登记重复）
      const id = String(opts?.id ?? '')
      const list = registeredAgents()
      const target = list.find((a) => a.id === id)
      if (!target) return { success: false, error: '登记项不存在或已被删除' }
      const newAddr = opts?.host != null || opts?.port != null
        ? `${opts.host ?? target.host}:${opts.port ?? target.port}`
        : undefined
      let parsed: { host: string; port: number } | null = null
      if (newAddr != null) {
        parsed = parseAddress(newAddr)
        if (!parsed) return { success: false, error: '地址格式应为 host:port（如 10.0.0.7:8899）' }
        if (list.some((a) => a.id !== id && a.host === parsed!.host && a.port === parsed!.port)) {
          return { success: false, error: '该地址已登记在另一条探针里：' + `${parsed.host}:${parsed.port}` }
        }
      }
      if (parsed) { target.host = parsed.host; target.port = parsed.port }
      if (opts?.label !== undefined) target.label = String(opts.label).trim() || undefined
      setRegisteredAgents(list)
      console.info('[探针登记] 更新 id=%s → %s:%d label=%s', id, target.host, target.port, target.label ?? '(无)')
      return { success: true, agents: list }
    }
    return { success: false, error: '未知操作: ' + action }
  } catch (e: any) {
    return { success: false, error: String(e?.message ?? e) }
  }
})
