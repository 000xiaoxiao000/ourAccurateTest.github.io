import fs from 'fs'
import path from 'path'
import { pathToFileURL } from 'url'
import { app } from 'electron'
import { shell } from 'electron'
import type { PluginInfo, PluginManifest, TrafficRecord } from '../types.js'

type PluginModule = {
  onRecordCaptured?: (record: TrafficRecord) => TrafficRecord | null | Promise<TrafficRecord | null>
  beforeSave?: (record: TrafficRecord, context?: PluginContext) => TrafficRecord | null | Promise<TrafficRecord | null>
}

type PluginContext = Record<string, never>

type BuiltinPluginId = 'traffic-cleanup-plugin'

type LoadedPlugin = {
  info: PluginInfo
  module?: PluginModule
}

const loadedPlugins = new Map<string, LoadedPlugin>()

function pluginsRoot(): string {
  return path.join(app.getPath('userData'), 'plugins')
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
    return await loadPlugins()
  }
  if (target === 'traffic-cleanup-plugin') {
    installTrafficCleanupPlugin()
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

export async function uninstallBuiltinPlugin(): Promise<PluginInfo[]> {
  for (const pluginName of ['traffic-cleanup-plugin']) {
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
  const context: PluginContext = {}
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
