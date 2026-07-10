import fs from 'fs'
import path from 'path'
import { pathToFileURL } from 'url'
import { app } from 'electron'
import { shell } from 'electron'
import type { CoverageRelayConfig, PluginInfo, PluginManifest, TrafficRecord } from '../types.js'

type PluginModule = {
  onRecordCaptured?: (record: TrafficRecord) => TrafficRecord | null | Promise<TrafficRecord | null>
  beforeSave?: (record: TrafficRecord, context?: PluginContext) => TrafficRecord | null | Promise<TrafficRecord | null>
}

type PluginContext = {
  coverageRelay: CoverageRelayConfig
}

type BuiltinPluginId = 'traffic-cleanup-plugin' | 'oat-coverage-relay'

type LoadedPlugin = {
  info: PluginInfo
  module?: PluginModule
}

const loadedPlugins = new Map<string, LoadedPlugin>()
let coverageRelayConfig: CoverageRelayConfig = {
  enabled: false,
  intervalMs: 30000,
  coveragePort: 8889,
  proxyPort: 8888,
  targetType: 'relay',
  relayBaseUrl: '',
  serviceBaseUrl: '',
  projectId: '',
  appId: ''
}

function pluginsRoot(): string {
  return path.join(app.getPath('userData'), 'plugins')
}

function coverageRelayConfigPath(): string {
  return path.join(app.getPath('userData'), 'coverage-relay-config.json')
}

export function loadCoverageRelayConfig(): CoverageRelayConfig {
  try {
    const configPath = coverageRelayConfigPath()
    if (!fs.existsSync(configPath)) return getCoverageRelayConfig()
    const parsed = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Partial<CoverageRelayConfig>
    return setCoverageRelayConfig(parsed, false)
  } catch {
    return getCoverageRelayConfig()
  }
}

function persistCoverageRelayConfig(): void {
  fs.writeFileSync(coverageRelayConfigPath(), JSON.stringify(coverageRelayConfig, null, 2), 'utf-8')
}

function readManifest(pluginDir: string): PluginManifest | null {
  const manifestPath = path.join(pluginDir, 'plugin.json')
  if (!fs.existsSync(manifestPath)) return null
  return JSON.parse(fs.readFileSync(manifestPath, 'utf-8')) as PluginManifest
}

function ensureModulePackage(pluginDir: string): void {
  const packagePath = path.join(pluginDir, 'package.json')
  if (fs.existsSync(packagePath)) return
  fs.writeFileSync(packagePath, JSON.stringify({ type: 'module' }, null, 2), 'utf-8')
}

export async function loadPlugins(): Promise<PluginInfo[]> {
  loadedPlugins.clear()
  const root = pluginsRoot()
  fs.mkdirSync(root, { recursive: true })
  upgradeInstalledBuiltinPlugins(root)
  const entries = fs.readdirSync(root, { withFileTypes: true }).filter(entry => entry.isDirectory())

  for (const entry of entries) {
    const pluginDir = path.join(root, entry.name)
    try {
      const manifest = readManifest(pluginDir)
      if (!manifest?.id || !manifest.name || !manifest.version || !manifest.main) continue
      const info: PluginInfo = {
        ...manifest,
        enabled: manifest.enabled !== false,
        path: pluginDir
      }
      if (info.enabled) {
        const modulePath = path.join(pluginDir, manifest.main)
        if (path.extname(modulePath) === '.js') {
          ensureModulePackage(pluginDir)
        }
        const moduleUrl = `${pathToFileURL(modulePath).href}?t=${Date.now()}`
        const module = await import(moduleUrl) as PluginModule
        loadedPlugins.set(info.id, { info, module })
      } else {
        loadedPlugins.set(info.id, { info })
      }
    } catch (error: any) {
      const fallback: PluginInfo = {
        id: entry.name,
        name: entry.name,
        version: 'unknown',
        main: '',
        enabled: false,
        path: pluginDir,
        error: error?.message ?? String(error)
      }
      loadedPlugins.set(fallback.id, { info: fallback })
    }
  }

  return listPlugins()
}

function upgradeInstalledBuiltinPlugins(root: string): void {
  if (fs.existsSync(path.join(root, 'oat-coverage-relay'))) {
    installCoverageRelayPlugin()
  }
}

export function listPlugins(): PluginInfo[] {
  return [...loadedPlugins.values()].map(plugin => plugin.info)
}

export function getPluginsPath(): string {
  const root = pluginsRoot()
  fs.mkdirSync(root, { recursive: true })
  return root
}

export function openPluginsFolder(): void {
  shell.openPath(getPluginsPath())
}

export async function installBuiltinPlugin(pluginId?: BuiltinPluginId | 'all'): Promise<PluginInfo[]> {
  const target = pluginId ?? 'all'
  if (target === 'all') {
    installTrafficCleanupPlugin()
    installCoverageRelayPlugin()
    return await loadPlugins()
  }
  if (target === 'traffic-cleanup-plugin') {
    installTrafficCleanupPlugin()
    return await loadPlugins()
  }
  if (target === 'oat-coverage-relay') {
    installCoverageRelayPlugin()
    return await loadPlugins()
  }
  throw new Error('不支持的内置插件')
}

function installTrafficCleanupPlugin(): void {
  const pluginDir = path.join(getPluginsPath(), 'traffic-cleanup-plugin')
  fs.mkdirSync(pluginDir, { recursive: true })
  const manifestPath = path.join(pluginDir, 'plugin.json')
  const entryPath = path.join(pluginDir, 'index.js')

  if (!fs.existsSync(manifestPath)) {
    fs.writeFileSync(manifestPath, JSON.stringify({
      id: 'traffic-cleanup-plugin',
      name: '流量清洗插件',
      version: '1.0.0',
      main: 'index.js',
      enabled: true,
      description: '过滤静态资源和 OPTIONS 预检请求，并标记 API、错误、慢请求'
    }, null, 2), 'utf-8')
  }

  if (!fs.existsSync(entryPath)) {
    fs.writeFileSync(entryPath, `export function onRecordCaptured(record) {
  const tags = new Set(record.tags || [])
  const url = record.url || ''
  const status = Number(record.statusCode)
  const duration = Number(record.duration) || 0

  if (/\\/api(\\/|$)/i.test(url)) {
    tags.add('API')
  }
  if (!Number.isNaN(status) && status >= 400) {
    tags.add('错误')
  }
  if (duration >= 1000) {
    tags.add('慢请求')
  }

  return {
    ...record,
    tags: Array.from(tags)
  }
}

export function beforeSave(record) {
  const url = record.url || ''
  const method = (record.method || '').toUpperCase()
  if (method === 'OPTIONS') {
    return null
  }
  if (/\\.(png|jpe?g|gif|svg|ico|css|js|map|woff2?|ttf)(\\?|$)/i.test(url)) {
    return null
  }
  return record
}
`, 'utf-8')
  }

  ensureModulePackage(pluginDir)
}

function installCoverageRelayPlugin(): void {
  const pluginDir = path.join(getPluginsPath(), 'oat-coverage-relay')
  fs.mkdirSync(pluginDir, { recursive: true })
  const manifestPath = path.join(pluginDir, 'plugin.json')
  const entryPath = path.join(pluginDir, 'index.js')

  fs.writeFileSync(manifestPath, JSON.stringify({
    id: 'oat-coverage-relay',
    name: '前端覆盖率中继插件',
    version: '1.4.1',
    main: 'index.js',
    enabled: true,
    description: '识别 Istanbul 覆盖率上报请求，附加用例名并转发到 oAT 服务端，同时保留上送状态'
  }, null, 2), 'utf-8')

  fs.writeFileSync(entryPath, `const lastRelayedCoverageSignatures = new Map()

function parseJson(value) {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

function isCoverageUrl(url) {
  return /\\/oat\\/coverage\\/report(\\?|$)/i.test(url || '')
    || /\\/api\\/projects\\/[^/]+\\/apps\\/[^/]+\\/coverage\\/frontend\\/report(\\?|$)/i.test(url || '')
    || /\\/api\\/projects\\/[^/]+\\/apps\\/[^/]+\\/coverage\\/universal\\/(CPP|GO|PYTHON)\\/report(\\?|$)/i.test(url || '')
}

function resolveDirectApiUrl(url) {
  const match = String(url || '').match(/\\/api\\/projects\\/[^/]+\\/apps\\/[^/]+\\/coverage\\/(frontend|universal\\/(CPP|GO|PYTHON))\\/report/i)
  if (!match) return ''
  try {
    const parsed = new URL(url)
    return parsed.origin + match[0]
  } catch {
    return match[0]
  }
}

function resolveRelayApiUrl(record, body) {
  const direct = resolveDirectApiUrl(record.url)
  if (direct) return direct
  return ''
}

function normalizeSourceType(body, url) {
  const fromUrl = String(url || '').match(/\\/coverage\\/universal\\/([^/?#]+)\\/report/i)?.[1]
  const raw = String(body?.sourceType || fromUrl || 'FRONTEND').trim().toUpperCase()
  if (raw === 'CPP' || raw === 'C++' || raw === 'C' || raw === 'CXX') return 'CPP'
  if (raw === 'GO' || raw === 'GOLANG') return 'GO'
  if (raw === 'PYTHON' || raw === 'PY') return 'PYTHON'
  return 'FRONTEND'
}

function coveragePayload(body) {
  if (!body) return undefined
  if (body.coverageData !== undefined) return body.coverageData
  if (body.data !== undefined) return body.data
  if (body.profile !== undefined) return body.profile
  return body.coverage
}

function resolveConfiguredApiUrl(record, body, context) {
  const relayBaseUrl = body?.relayBaseUrl || context?.coverageRelay?.relayBaseUrl || ''
  const serviceBaseUrl = body?.serviceBaseUrl || body?.endpointBaseUrl || context?.coverageRelay?.serviceBaseUrl || process.env.OAT_SERVICE_BASE_URL || ''
  const targetType = body?.targetType || context?.coverageRelay?.targetType || (relayBaseUrl ? 'relay' : 'service')
  const targetBaseUrl = targetType === 'relay' ? relayBaseUrl : serviceBaseUrl
  const projectId = context?.coverageRelay?.projectId || body?.projectId
  const appId = context?.coverageRelay?.appId || body?.appId || body?.appKey
  if (!targetBaseUrl || !projectId || !appId) return ''
  const sourceType = normalizeSourceType(body, record.url)
  const path = sourceType === 'FRONTEND' ? '/coverage/frontend/report' : '/coverage/universal/' + sourceType + '/report'
  return String(targetBaseUrl).replace(/\\/$/, '') + '/api/projects/' + encodeURIComponent(projectId) + '/apps/' + encodeURIComponent(appId) + path
}

function resolveTargetApiUrl(record, body, context) {
  return resolveConfiguredApiUrl(record, body, context) || resolveRelayApiUrl(record, body)
}

function coverageSignature(coverage) {
  try {
    return JSON.stringify(coverage)
  } catch {
    return String(coverage)
  }
}

function coverageDedupeKey(targetUrl, body) {
  return [
    targetUrl,
    body?.projectId || '',
    body?.appId || body?.appKey || '',
    body?.versionNumber || '',
    body?.commitId || '',
    body?.branch || ''
  ].join('|')
}

function relayRecord(record, body, context, status, options = {}) {
  const tags = new Set(record.tags || [])
  const sourceType = normalizeSourceType(body, record.url)
  tags.add('COVERAGE')
  tags.add(sourceType)
  tags.add(status === 'success' ? 'COVERAGE_OK' : status === 'failed' ? 'COVERAGE_FAIL' : 'COVERAGE_SKIP')
  const configuredIntervalMs = Number(context?.coverageRelay?.intervalMs) > 0 ? Number(context.coverageRelay.intervalMs) : 30000
  const intervalMs = Number(body?.intervalMs) > 0 ? Number(body.intervalMs) : configuredIntervalMs
  const now = Date.now()
  return {
    ...record,
    id: 'coverage-relay-' + now + '-' + Math.random().toString(36).slice(2, 8),
    method: 'COVERAGE',
    protocol: 'COVERAGE',
    statusCode: status === 'success' ? '已上送' : status === 'failed' ? '上送失败' : '未上送',
    duration: options.duration ?? record.duration ?? 0,
    timestamp: now,
    responseBody: options.message || '',
    tags: Array.from(tags),
    coverageRelay: {
      status,
      targetUrl: options.targetUrl,
      httpStatus: options.httpStatus,
      error: options.error,
      intervalMs,
      nextReportAt: now + intervalMs,
      requestId: body?.requestId || record.id,
      projectId: context?.coverageRelay?.projectId || body?.projectId,
      appId: context?.coverageRelay?.appId || body?.appId || body?.appKey,
      versionNumber: body?.versionNumber,
      commitId: body?.commitId
    }
  }
}

export function onRecordCaptured(record) {
  if (record.source === 'replay') return record
  if (!isCoverageUrl(record.url)) return record
  const tags = new Set(record.tags || [])
  tags.add('COVERAGE')
  tags.add(normalizeSourceType(null, record.url))
  return { ...record, tags: Array.from(tags) }
}

export async function beforeSave(record, context) {
  if (record.source === 'replay') return record
  if (record.coverageRelay) return record
  if (!(record.tags || []).includes('COVERAGE')) return record
  const start = Date.now()
  const body = parseJson(record.requestBody)
  const sourceType = normalizeSourceType(body, record.url)
  const coverage = coveragePayload(body)
  if (sourceType === 'FRONTEND' && body?.coverageMissing) {
    return relayRecord(record, body, context, 'skipped', { error: '页面未发现 window.__coverage__，请确认被测前端已启用 Istanbul 插桩并刷新页面', duration: Date.now() - start })
  }
  if (coverage === undefined || coverage === null || coverage === '') {
    const expectedField = sourceType === 'FRONTEND' ? 'coverage' : 'coverageData/data/profile'
    return relayRecord(record, body, context, 'skipped', { error: '请求体没有 ' + expectedField + ' 字段', duration: Date.now() - start })
  }
  const targetUrl = resolveTargetApiUrl(record, body, context)
  if (!targetUrl) {
    return relayRecord(record, body, context, 'failed', { error: '缺少 serviceBaseUrl/projectId/appId，无法确定服务端上送地址；请在采集器覆盖率上送区域配置默认目标', duration: Date.now() - start })
  }
  const signature = coverageSignature(coverage)
  const dedupeKey = coverageDedupeKey(targetUrl, body)
  if (lastRelayedCoverageSignatures.get(dedupeKey) === signature) {
    return relayRecord(record, body, context, 'skipped', {
      targetUrl,
      error: '覆盖率数据未变化，已跳过重复上送',
      duration: Date.now() - start,
      message: '覆盖率数据未变化，已跳过重复上送'
    })
  }
  try {
    const response = await fetch(targetUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-OAT-Request-Id': String(body.requestId || record.id || '')
      },
      body: JSON.stringify({
        requestId: body.requestId || record.id,
        commitId: body.commitId,
        versionNumber: body.versionNumber,
        branch: body.branch,
        caseName: body.caseName || record.caseName,
        timestamp: body.timestamp || record.timestamp || Date.now(),
        ...(sourceType === 'FRONTEND' ? { coverage } : { coverageData: coverage })
      })
    })
    if (response.ok) {
      lastRelayedCoverageSignatures.set(dedupeKey, signature)
    }
    return relayRecord(record, body, context, response.ok ? 'success' : 'failed', {
      targetUrl,
      httpStatus: response.status,
      error: response.ok ? undefined : '服务端返回 HTTP ' + response.status,
      duration: Date.now() - start,
      message: '覆盖率数据已中继到 ' + targetUrl
    })
  } catch (error) {
    return relayRecord(record, body, context, 'failed', {
      targetUrl,
      error: error?.message || String(error),
      duration: Date.now() - start
    })
  }
}
`, 'utf-8')

  ensureModulePackage(pluginDir)
}

export async function uninstallBuiltinPlugin(): Promise<PluginInfo[]> {
  for (const pluginName of ['traffic-cleanup-plugin', 'oat-coverage-relay']) {
    removePluginDirectory(pluginName)
  }
  return await loadPlugins()
}

function removePluginDirectory(pluginId: string): void {
  if (!pluginId || pluginId.includes('/') || pluginId.includes('\\') || pluginId === '.' || pluginId === '..') {
    throw new Error('无效的插件 ID')
  }
  const root = getPluginsPath()
  const pluginDir = path.resolve(root, pluginId)
  const relative = path.relative(root, pluginDir)
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error('插件路径越界')
  }
  if (fs.existsSync(pluginDir)) {
    fs.rmSync(pluginDir, { recursive: true, force: true })
  }
}

export async function uninstallPlugin(pluginId: string): Promise<PluginInfo[]> {
  removePluginDirectory(pluginId)
  return await loadPlugins()
}

export function getCoverageRelayConfig(): CoverageRelayConfig {
  return { ...coverageRelayConfig }
}

export function setCoverageRelayConfig(config: Partial<CoverageRelayConfig>, persist = true): CoverageRelayConfig {
  const intervalMs = Number(config.intervalMs)
  const coveragePort = Number(config.coveragePort)
  const proxyPort = Number(config.proxyPort)
  const relayBaseUrl = normalizeBaseUrl(config.relayBaseUrl, coverageRelayConfig.relayBaseUrl)
  const serviceBaseUrl = normalizeBaseUrl(config.serviceBaseUrl, coverageRelayConfig.serviceBaseUrl)
  coverageRelayConfig = {
    enabled: typeof config.enabled === 'boolean' ? config.enabled : coverageRelayConfig.enabled,
    intervalMs: Number.isFinite(intervalMs) && intervalMs >= 1000 ? Math.round(intervalMs) : coverageRelayConfig.intervalMs,
    coveragePort: Number.isInteger(coveragePort) && coveragePort > 0 && coveragePort <= 65535 ? coveragePort : coverageRelayConfig.coveragePort,
    proxyPort: Number.isInteger(proxyPort) && proxyPort > 0 && proxyPort <= 65535 ? proxyPort : coverageRelayConfig.proxyPort,
    targetType: config.targetType === 'service' ? 'service' : config.targetType === 'relay' ? 'relay' : coverageRelayConfig.targetType,
    relayBaseUrl,
    serviceBaseUrl,
    projectId: typeof config.projectId === 'string' ? config.projectId.trim() : coverageRelayConfig.projectId,
    appId: typeof config.appId === 'string' ? config.appId.trim() : coverageRelayConfig.appId
  }
  if (persist) {
    persistCoverageRelayConfig()
  }
  return getCoverageRelayConfig()
}

function normalizeBaseUrl(value: unknown, fallback: string | undefined): string {
  if (typeof value !== 'string') {
    return fallback ?? ''
  }
  return value.trim().replace(/\/+$/, '')
}

export async function runRecordCapturedHooks(record: TrafficRecord): Promise<TrafficRecord | null> {
  let next: TrafficRecord | null = record
  for (const plugin of loadedPlugins.values()) {
    if (!next || !plugin.info.enabled || !plugin.module?.onRecordCaptured) continue
    try {
      next = await plugin.module.onRecordCaptured(next)
    } catch (error: any) {
      plugin.info.error = error?.message ?? String(error)
    }
  }
  return next
}

export async function runBeforeSaveHooks(record: TrafficRecord): Promise<TrafficRecord | null> {
  let next: TrafficRecord | null = record
  const context: PluginContext = {
    coverageRelay: getCoverageRelayConfig()
  }
  for (const plugin of loadedPlugins.values()) {
    if (!next || !plugin.info.enabled || !plugin.module?.beforeSave) continue
    try {
      next = await plugin.module.beforeSave(next, context)
    } catch (error: any) {
      plugin.info.error = error?.message ?? String(error)
    }
  }
  return next
}
