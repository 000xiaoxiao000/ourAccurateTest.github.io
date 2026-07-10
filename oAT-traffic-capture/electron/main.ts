import { app, BrowserWindow, ipcMain, dialog } from 'electron'
import path from 'path'
import fs from 'fs'
import { fileURLToPath } from 'url'
import { createProxyServer } from './proxy.js'
import { coverageRelayPort, createCoverageRelayServer } from './coverageRelayServer.js'
import { disableSystemProxy, enableSystemProxy, getSystemProxyStatus } from './systemProxy.js'
import { generateRootCert, getCertInfo, getProxyCaDir, installCertMacOS, openCertFolder, uninstallCertMacOS } from './certificate.js'
import { connectMqtt, disconnectAllMqtt, disconnectMqtt } from './protocols/mqtt.js'
import { applyCaptureRules } from './filterRules.js'
import { replayRecord } from './replay.js'
import {
  getCoverageRelayConfig,
  getPluginsPath,
  installBuiltinPlugin,
  listPlugins,
  loadCoverageRelayConfig,
  loadPlugins,
  openPluginsFolder,
  runBeforeSaveHooks,
  runRecordCapturedHooks,
  setCoverageRelayConfig,
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
import type { CaptureProtocolConfig, TrafficFilterRule, TrafficRecord } from './types.js'
import ExcelJS from 'exceljs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

let mainWindow: BrowserWindow | null = null
let floatingWindow: BrowserWindow | null = null
let proxyServer: any = null
let coverageRelayServer: any = null
let activeProxyPort = 8888
let activeCoverageRelayPort = 8889
let captureEnabled = false
let currentCaseName = ''
let currentSessionId = ''
const trafficRecords: TrafficRecord[] = []
let filterRules: TrafficFilterRule[] = []
const DEFAULT_PROXY_PORT = 8888
const DEFAULT_COVERAGE_PORT = 8889
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
    text: args.map(stringifyLogArg).join(' ')
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

function getCaptureState() {
  const config = getCoverageRelayConfig()
  return {
    isCapturing: captureEnabled,
    caseName: currentCaseName,
    port: proxyServer?.httpPort ?? config.proxyPort ?? DEFAULT_PROXY_PORT,
    coveragePort: coverageRelayServer?.address?.()?.port ?? config.coveragePort ?? DEFAULT_COVERAGE_PORT,
    recordCount: trafficRecords.length,
    protocols: enabledProtocols
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

function closeCoverageRelayServer() {
  if (coverageRelayServer) {
    coverageRelayServer.close()
    coverageRelayServer = null
  }
}

function isCoverageRelayPluginEnabled(): boolean {
  const config = getCoverageRelayConfig()
  return config.enabled === true
    && listPlugins().some(plugin => plugin.id === 'oat-coverage-relay' && plugin.enabled && !plugin.error)
}

async function acceptCapturedRecord(record: TrafficRecord) {
  const afterPlugins = await runRecordCapturedHooks({
    ...record,
    caseName: currentCaseName,
    source: record.source ?? 'capture'
  })
  if (!afterPlugins) return
  const isCoverageRecord = (afterPlugins.tags ?? []).includes('COVERAGE')
  if (!captureEnabled && !isCoverageRecord) return
  const afterRules = captureEnabled ? applyCaptureRules(afterPlugins, filterRules) : afterPlugins
  if (!afterRules) return
  const beforeSave = await runBeforeSaveHooks(afterRules)
  if (!beforeSave) return

  trafficRecords.push(beforeSave)
  saveRecord(beforeSave, captureEnabled ? currentSessionId : undefined)
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
  const config = getCoverageRelayConfig()
  const configuredPort = Number(config.proxyPort) > 0 ? Number(config.proxyPort) : DEFAULT_PROXY_PORT
  if (proxyServer && activeProxyPort !== configuredPort) {
    closeProxyServer()
  }
  if (!proxyServer) {
    if (!getCertInfo().exists) {
      generateRootCert()
    }
    proxyServer = createProxyServer((record: TrafficRecord) => {
      acceptCapturedRecord(record)
    }, getProxyCaDir(), enabledProtocols, {
      isEnabled: isCoverageRelayPluginEnabled,
      getConfig: getCoverageRelayConfig
    })

    await startProxyServer(proxyServer, configuredPort)
    activeProxyPort = proxyServer.httpPort ?? configuredPort
  } else {
    proxyServer.setEnabledProtocols?.(enabledProtocols)
  }

  return proxyServer.httpPort ?? configuredPort
}

function startCoverageRelayServer(server: any, port: number): Promise<number> {
  return new Promise((resolve, reject) => {
    server.listen(port, (error?: Error) => {
      if (error) {
        reject(error)
        return
      }
      const address = server.address()
      resolve(typeof address === 'object' && address ? address.port : port)
    })
  })
}

async function ensureCoverageRelayServer(): Promise<number> {
  const config = getCoverageRelayConfig()
  const configuredPort = coverageRelayPort(config)
  if (coverageRelayServer && activeCoverageRelayPort !== configuredPort) {
    closeCoverageRelayServer()
  }
  if (!coverageRelayServer) {
    coverageRelayServer = createCoverageRelayServer((record: TrafficRecord) => {
      acceptCapturedRecord(record)
    }, {
      isEnabled: isCoverageRelayPluginEnabled,
      getConfig: getCoverageRelayConfig
    })
    activeCoverageRelayPort = await startCoverageRelayServer(coverageRelayServer, configuredPort)
  }
  return activeCoverageRelayPort
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
  const coverageConfig = loadCoverageRelayConfig()
  await loadPlugins()
  if (coverageConfig.enabled) {
    await ensureCoverageRelayServer()
  }
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  closeProxyServer()
  closeCoverageRelayServer()
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

    return { success: true, port, coveragePort: getCaptureState().coveragePort }
  } catch (error: any) {
    captureEnabled = false
    proxyServer = null
    broadcastCaptureState()
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('stop-capture', async () => {
  captureEnabled = false
  if (currentSessionId) {
    updateSessionEndTime(currentSessionId, Date.now())
  }
  broadcastCaptureState()
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
  trafficRecords.length = 0
  broadcastCaptureState()
  return { success: true }
})

ipcMain.handle('list-filter-rules', async () => filterRules)

ipcMain.handle('save-filter-rules', async (_event, rules: TrafficFilterRule[]) => {
  filterRules = rules
  saveFilterRules(filterRules)
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
  if (format === 'json') {
    const { filePath } = await dialog.showSaveDialog({
      defaultPath: `traffic-export-${Date.now()}.json`,
      filters: [{ name: 'JSON', extensions: ['json'] }]
    })
    if (!filePath) return { success: false }
    fs.writeFileSync(filePath, JSON.stringify(records, null, 2), 'utf-8')
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
    return { success: true, filePath }
  }

  return { success: false }
})

ipcMain.handle('replay-record', async (_event, record: TrafficRecord) => {
  const result = await replayRecord(record)
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
    const config = getCoverageRelayConfig()
    if (Number(port) > 0 && Number(port) !== config.proxyPort) {
      setCoverageRelayConfig({ proxyPort: Number(port) })
    }
    enabledProtocols = { ...enabledProtocols, ...(protocols ?? {}) }
    const proxyPort = await ensureProxyServer()
    await enableSystemProxy({
      port: proxyPort || port,
      bypass: ['localhost', '127.0.0.1', '*.local'],
      protocols: enabledProtocols
    })
    broadcastCaptureState()
    return { success: true }
  } catch (error: any) {
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('disable-system-proxy', async () => {
  try {
    await disableSystemProxy()
    return { success: true }
  } catch (error: any) {
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('list-sessions', async () => listSessions())

ipcMain.handle('load-session', async (_event, sessionId: string) => {
  const records = loadSessionRecords(sessionId)
  trafficRecords.length = 0
  trafficRecords.push(...records)
  broadcastCaptureState()
  return records
})

ipcMain.handle('delete-session', async (_event, sessionId: string) => {
  deleteSession(sessionId)
  return { success: true }
})

ipcMain.handle('connect-mqtt', async (_event, config) => {
  try {
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

ipcMain.handle('install-builtin-plugin', async (_event, pluginId?: 'traffic-cleanup-plugin' | 'oat-coverage-relay' | 'all') => installBuiltinPlugin(pluginId))

ipcMain.handle('uninstall-builtin-plugin', async () => uninstallBuiltinPlugin())

ipcMain.handle('uninstall-plugin', async (_event, pluginId: string) => uninstallPlugin(pluginId))

ipcMain.handle('get-coverage-relay-config', async () => loadCoverageRelayConfig())

ipcMain.handle('test-coverage-report', async (_event, targetUrl: string, body: unknown) => {
  try {
    const response = await fetch(targetUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body)
    })
    const responseBody = await response.text().catch(() => '')
    return {
      success: response.ok,
      status: response.status,
      body: responseBody,
      error: response.ok ? undefined : `HTTP ${response.status}${responseBody ? `: ${responseBody.slice(0, 300)}` : ''}`
    }
  } catch (error: any) {
    return { success: false, error: error?.message ?? String(error) }
  }
})

ipcMain.handle('set-coverage-relay-config', async (_event, config) => {
  const previousConfig = getCoverageRelayConfig()
  const nextConfig = setCoverageRelayConfig(config)
  if (Number(previousConfig.proxyPort) !== Number(nextConfig.proxyPort)) {
    closeProxyServer()
  }
  if (Number(previousConfig.coveragePort) !== Number(nextConfig.coveragePort)) {
    closeCoverageRelayServer()
  }
  if (nextConfig.enabled) {
    await ensureCoverageRelayServer()
  }
  broadcastCaptureState()
  return nextConfig
})
