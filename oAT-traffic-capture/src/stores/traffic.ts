import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { BuiltinPluginId, CoverageRelayConfig, PluginInfo, TrafficFilterRule, TrafficRecord } from '../types/traffic'

export const useTrafficStore = defineStore('traffic', () => {
  const records = ref<TrafficRecord[]>([])
  const isCapturing = ref(false)
  const currentCaseName = ref('')
  const searchQuery = ref('')
  const statusFilter = ref<'all' | 'success' | 'failed'>('all')
  const proxyPort = ref(8888)
  const coveragePort = ref(8889)
  const capturedCount = ref(0)
  const filterRules = ref<TrafficFilterRule[]>([])
  const plugins = ref<PluginInfo[]>([])
  const coverageRelayConfig = ref<CoverageRelayConfig>({
    enabled: false,
    intervalMs: 30000,
    coveragePort: 8889,
    proxyPort: 8888,
    targetType: 'relay',
    relayBaseUrl: '',
    serviceBaseUrl: '',
    projectId: '',
    appId: ''
  })

  function isSuccessRecord(record: TrafficRecord): boolean {
    if (record.coverageRelay) {
      return record.coverageRelay.status === 'success'
    }
    const code = Number(record.statusCode)
    return !isNaN(code) && code >= 200 && code < 400
  }

  const filteredRecords = computed(() => {
    const q = searchQuery.value.toLowerCase()
    return records.value.filter(r => {
      if (statusFilter.value === 'success' && !isSuccessRecord(r)) return false
      if (statusFilter.value === 'failed' && isSuccessRecord(r)) return false
      if (!q) return true
      return r.url.toLowerCase().includes(q) ||
        r.caseName.toLowerCase().includes(q) ||
        r.method.toLowerCase().includes(q) ||
        String(r.statusCode).includes(q)
    })
  })

  const stats = computed(() => {
    const total = records.value.length
    const success = records.value.filter(isSuccessRecord).length
    const failed = total - success
    const avgDuration = total > 0
      ? Math.round(records.value.reduce((acc, r) => acc + r.duration, 0) / total)
      : 0
    return { total, success, failed, avgDuration }
  })

  const coverageRelayStats = computed(() => {
    const coverageRecords = records.value.filter(record => record.coverageRelay)
    const success = coverageRecords.filter(record => record.coverageRelay?.status === 'success').length
    const failed = coverageRecords.filter(record => record.coverageRelay?.status === 'failed').length
    const skipped = coverageRecords.filter(record => record.coverageRelay?.status === 'skipped').length
    const latest = coverageRecords[0]
    const latestRelay = latest?.coverageRelay
    const intervalMs = latestRelay?.intervalMs ?? coverageRelayConfig.value.intervalMs
    const nextReportAt = latestRelay?.nextReportAt
    return {
      total: coverageRecords.length,
      success,
      failed,
      skipped,
      latest,
      latestRelay,
      intervalMs,
      nextReportAt
    }
  })

  const chartStats = computed(() => {
    const protocolMap = new Map<string, number>()
    const statusMap = new Map<string, number>()
    const hostMap = new Map<string, number>()
    const timeline = new Map<string, { label: string; count: number; avgDuration: number; totalDuration: number }>()

    for (const record of records.value) {
      protocolMap.set(record.protocol, (protocolMap.get(record.protocol) ?? 0) + 1)
      const statusKey = String(record.statusCode).slice(0, 1) + 'xx'
      statusMap.set(statusKey, (statusMap.get(statusKey) ?? 0) + 1)
      try {
        const host = new URL(record.url).host
        hostMap.set(host, (hostMap.get(host) ?? 0) + 1)
      } catch {
        hostMap.set(record.protocol, (hostMap.get(record.protocol) ?? 0) + 1)
      }

      const time = new Date(record.timestamp)
      const label = `${String(time.getHours()).padStart(2, '0')}:${String(time.getMinutes()).padStart(2, '0')}`
      const bucket = timeline.get(label) ?? { label, count: 0, avgDuration: 0, totalDuration: 0 }
      bucket.count += 1
      bucket.totalDuration += Number(record.duration) || 0
      bucket.avgDuration = Math.round(bucket.totalDuration / bucket.count)
      timeline.set(label, bucket)
    }

    return {
      protocols: [...protocolMap.entries()].map(([label, value]) => ({ label, value })),
      statuses: [...statusMap.entries()].map(([label, value]) => ({ label, value })),
      hosts: [...hostMap.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, 8)
        .map(([label, value]) => ({ label, value })),
      timeline: [...timeline.values()].slice(-30)
    }
  })

  function addRecord(record: TrafficRecord) {
    records.value.unshift(record)
    capturedCount.value += 1
  }

  function deleteRecord(id: string) {
    const idx = records.value.findIndex(r => r.id === id)
    if (idx !== -1) {
      records.value.splice(idx, 1)
      capturedCount.value = Math.max(0, capturedCount.value - 1)
    }
    window.electronAPI?.deleteTrafficRecord(id)
  }

  function clearRecords() {
    records.value = []
    capturedCount.value = 0
    window.electronAPI?.clearTrafficRecords()
  }

  function replaceRecords(nextRecords: TrafficRecord[]) {
    records.value = nextRecords
    capturedCount.value = nextRecords.length
  }

  function setStatusFilter(filter: 'all' | 'success' | 'failed') {
    statusFilter.value = filter
  }

  async function loadFilterRules() {
    filterRules.value = await window.electronAPI?.listFilterRules() ?? []
  }

  async function saveRules(rules: TrafficFilterRule[]) {
    filterRules.value = rules
    await window.electronAPI?.saveFilterRules(rules)
  }

  async function replay(record: TrafficRecord) {
    return await window.electronAPI?.replayRecord(JSON.parse(JSON.stringify(record)))
  }

  async function replayMany(recordIds: string[]) {
    const idSet = new Set(recordIds)
    const targets = filteredRecords.value.filter(record => idSet.has(record.id))
    return await window.electronAPI?.replayRecords(JSON.parse(JSON.stringify(targets)))
  }

  async function loadPlugins() {
    plugins.value = await window.electronAPI?.listPlugins() ?? []
  }

  async function reloadPlugins() {
    plugins.value = await window.electronAPI?.reloadPlugins() ?? []
  }

  async function installBuiltinPlugin(pluginId: BuiltinPluginId = 'all') {
    plugins.value = await window.electronAPI?.installBuiltinPlugin(pluginId) ?? []
  }

  async function uninstallBuiltinPlugin() {
    plugins.value = await window.electronAPI?.uninstallBuiltinPlugin() ?? []
  }

  async function uninstallPlugin(pluginId: string) {
    plugins.value = await window.electronAPI?.uninstallPlugin(pluginId) ?? []
  }

  async function loadCoverageRelayConfig() {
    coverageRelayConfig.value = await window.electronAPI?.getCoverageRelayConfig() ?? coverageRelayConfig.value
    proxyPort.value = coverageRelayConfig.value.proxyPort ?? proxyPort.value
    coveragePort.value = coverageRelayConfig.value.coveragePort ?? coveragePort.value
  }

  async function saveCoverageRelayConfig(config: CoverageRelayConfig) {
    coverageRelayConfig.value = await window.electronAPI?.setCoverageRelayConfig(config) ?? coverageRelayConfig.value
    proxyPort.value = coverageRelayConfig.value.proxyPort ?? proxyPort.value
    coveragePort.value = coverageRelayConfig.value.coveragePort ?? coveragePort.value
    return coverageRelayConfig.value
  }

  async function startCapture(caseName: string) {
    currentCaseName.value = caseName
    const result = await window.electronAPI?.startCapture(caseName)
    if (result?.success) {
      isCapturing.value = true
      proxyPort.value = result.port ?? 8888
      coveragePort.value = result.coveragePort ?? coveragePort.value
    }
    return result
  }

  async function stopCapture() {
    await window.electronAPI?.stopCapture()
    isCapturing.value = false
  }

  function syncCaptureState(state: { isCapturing: boolean; caseName: string; port: number; coveragePort?: number; recordCount?: number }) {
    isCapturing.value = state.isCapturing
    proxyPort.value = state.port
    if (typeof state.coveragePort === 'number') {
      coveragePort.value = state.coveragePort
    }
    if (typeof state.recordCount === 'number') {
      capturedCount.value = state.recordCount
    }
    if (state.caseName) {
      currentCaseName.value = state.caseName
    }
  }

  return {
    records,
    isCapturing,
    currentCaseName,
    searchQuery,
    statusFilter,
    proxyPort,
    coveragePort,
    capturedCount,
    filterRules,
    plugins,
    coverageRelayConfig,
    filteredRecords,
    stats,
    coverageRelayStats,
    chartStats,
    addRecord,
    deleteRecord,
    clearRecords,
    replaceRecords,
    setStatusFilter,
    loadFilterRules,
    saveRules,
    replay,
    replayMany,
    loadPlugins,
    reloadPlugins,
    installBuiltinPlugin,
    uninstallBuiltinPlugin,
    uninstallPlugin,
    loadCoverageRelayConfig,
    saveCoverageRelayConfig,
    startCapture,
    stopCapture,
    syncCaptureState
  }
})
