import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron'
import path from 'path'
import util from 'util'
import fs from 'fs'
import { fileURLToPath } from 'url'
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
  loadSessionRecords,
  saveRecord,
  saveFilterRules,
  saveSession,
  updateSessionEndTime
} from './database.js'
import type { CaptureProtocolConfig, CoverageConfig, TrafficFilterRule, TrafficRecord } from './types.js'
import ExcelJS from 'exceljs'
import * as coverage from './coverage/index.js'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

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
  classfilesPath: '',
  projectDir: '',
  classfilesRepo: '/Users/xiaoxiao/oATagent/classfiles'
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

function stringifyLogArg(arg: unknown): string {
  if (arg instanceof Error) {
    return arg.stack || arg.message
  }
  if (typeof arg === 'string') {
    return arg
  }
  try {
    return JSON.stringify(arg)
  } catch {
    return String(arg)
  }
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
    const parsed = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as { proxyPort?: number }
    const proxyPort = Number(parsed.proxyPort)
    if (Number.isInteger(proxyPort) && proxyPort > 0 && proxyPort <= 65535) {
      configuredProxyPort = proxyPort
    }
  } catch {
    configuredProxyPort = DEFAULT_PROXY_PORT
  }
}

function saveProxyPortConfig(port: number): number {
  const proxyPort = Number(port)
  if (!Number.isInteger(proxyPort) || proxyPort <= 0 || proxyPort > 65535) {
    throw new Error('系统代理端口必须是 1-65535 的整数')
  }
  configuredProxyPort = proxyPort
  fs.writeFileSync(captureConfigPath(), JSON.stringify({ proxyPort: configuredProxyPort }, null, 2), 'utf-8')
  return configuredProxyPort
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

app.whenReady().then(async () => {
  installRuntimeLogCapture()
  initDatabase()
  filterRules = listFilterRules()
  loadCaptureConfig()
  await loadPlugins()
  createWindow()

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

ipcMain.handle('coverage-stats', async (_event, opts: any) => coverage.getStats(coverageConfig, opts ?? {}))

ipcMain.handle('coverage-dumpclasses', async (_event, opts: any) => coverage.dumpClasses(coverageConfig, opts ?? {}))

ipcMain.handle('coverage-setkey', async (_event, opts: any) => coverage.setKey(coverageConfig, opts ?? {}))

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

// 由项目根目录自动推导 classfiles（覆盖率分母）与源码目录，并做可达性校验
ipcMain.handle('coverage-detect-project', async (_event, opts: any) => coverage.detectProject(opts ?? {}))
ipcMain.handle('coverage-check-path', async (_event, opts: any) => coverage.checkPath(opts ?? {}))
