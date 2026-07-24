import { contextBridge, ipcRenderer } from 'electron'
import type { TrafficFilterRule, TrafficRecord } from './types.js'

contextBridge.exposeInMainWorld('electronAPI', {
  startCapture: (caseName: string) => ipcRenderer.invoke('start-capture', caseName),
  stopCapture: () => ipcRenderer.invoke('stop-capture'),
  getCaptureState: () => ipcRenderer.invoke('get-capture-state'),
  getRuntimeLogs: () => ipcRenderer.invoke('get-runtime-logs'),
  clearRuntimeLogs: () => ipcRenderer.invoke('clear-runtime-logs'),
  onRuntimeLogAppended: (callback: (entry: any) => void) => {
    const listener = (_event: Electron.IpcRendererEvent, entry: any) => callback(entry)
    ipcRenderer.on('runtime-log-appended', listener)
    return () => ipcRenderer.removeListener('runtime-log-appended', listener)
  },
  onRuntimeLogsCleared: (callback: () => void) => {
    const listener = () => callback()
    ipcRenderer.on('runtime-logs-cleared', listener)
    return () => ipcRenderer.removeListener('runtime-logs-cleared', listener)
  },
  showFloatingWindow: () => ipcRenderer.invoke('show-floating-window'),
  restoreMainWindow: () => ipcRenderer.invoke('restore-main-window'),
  getTrafficRecords: () => ipcRenderer.invoke('get-traffic-records'),
  clearTrafficRecords: () => ipcRenderer.invoke('clear-traffic-records'),
  deleteTrafficRecord: (id: string) => ipcRenderer.invoke('delete-traffic-record', id),
  listFilterRules: () => ipcRenderer.invoke('list-filter-rules'),
  saveFilterRules: (rules: TrafficFilterRule[]) => ipcRenderer.invoke('save-filter-rules', rules),
  replayRecord: (record: TrafficRecord) => ipcRenderer.invoke('replay-record', record),
  replayRecords: (records: TrafficRecord[]) => ipcRenderer.invoke('replay-records', records),
  exportRecords: (format: string, records: TrafficRecord[]) => 
    ipcRenderer.invoke('export-records', format, records),
  onTrafficCaptured: (callback: (record: TrafficRecord) => void) => {
    ipcRenderer.on('traffic-captured', (_event, record) => callback(record))
  },
  onCaptureStateChanged: (callback: (state: { isCapturing: boolean; caseName: string; port: number; recordCount: number }) => void) => {
    ipcRenderer.on('capture-state-changed', (_event, state) => callback(state))
  },
  getProxyStatus: () => ipcRenderer.invoke('get-proxy-status'),
  enableSystemProxy: (port: number, protocols: any) => ipcRenderer.invoke('enable-system-proxy', port, protocols),
  disableSystemProxy: () => ipcRenderer.invoke('disable-system-proxy'),
  listSessions: () => ipcRenderer.invoke('list-sessions'),
  loadSession: (sessionId: string) => ipcRenderer.invoke('load-session', sessionId),
  deleteSession: (sessionId: string) => ipcRenderer.invoke('delete-session', sessionId),
  connectMqtt: (config: any) => ipcRenderer.invoke('connect-mqtt', config),
  disconnectMqtt: (id: string) => ipcRenderer.invoke('disconnect-mqtt', id),
  onMqttStatus: (callback: (data: any) => void) => {
    ipcRenderer.on('mqtt-status', (_event, data) => callback(data))
  },
  getCertInfo: () => ipcRenderer.invoke('get-cert-info'),
  generateCert: () => ipcRenderer.invoke('generate-cert'),
  installCert: () => ipcRenderer.invoke('install-cert'),
  uninstallCert: () => ipcRenderer.invoke('uninstall-cert'),
  openCertFolder: () => ipcRenderer.invoke('open-cert-folder'),
  listPlugins: () => ipcRenderer.invoke('list-plugins'),
  reloadPlugins: () => ipcRenderer.invoke('reload-plugins'),
  getPluginsPath: () => ipcRenderer.invoke('get-plugins-path'),
  openPluginsFolder: () => ipcRenderer.invoke('open-plugins-folder'),
  installBuiltinPlugin: (pluginId?: 'traffic-cleanup-plugin' | 'all') => ipcRenderer.invoke('install-builtin-plugin', pluginId),
  uninstallBuiltinPlugin: () => ipcRenderer.invoke('uninstall-builtin-plugin'),
  uninstallPlugin: (pluginId: string) => ipcRenderer.invoke('uninstall-plugin', pluginId),
  setProxyPort: (port: number) => ipcRenderer.invoke('set-proxy-port', port)
})
