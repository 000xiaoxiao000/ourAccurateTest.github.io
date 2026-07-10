const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  startCapture: (caseName) => ipcRenderer.invoke('start-capture', caseName),
  stopCapture: () => ipcRenderer.invoke('stop-capture'),
  getCaptureState: () => ipcRenderer.invoke('get-capture-state'),
  getRuntimeLogs: () => ipcRenderer.invoke('get-runtime-logs'),
  clearRuntimeLogs: () => ipcRenderer.invoke('clear-runtime-logs'),
  onRuntimeLogAppended: (callback) => {
    const listener = (_event, entry) => callback(entry)
    ipcRenderer.on('runtime-log-appended', listener)
    return () => ipcRenderer.removeListener('runtime-log-appended', listener)
  },
  onRuntimeLogsCleared: (callback) => {
    const listener = () => callback()
    ipcRenderer.on('runtime-logs-cleared', listener)
    return () => ipcRenderer.removeListener('runtime-logs-cleared', listener)
  },
  showFloatingWindow: () => ipcRenderer.invoke('show-floating-window'),
  restoreMainWindow: () => ipcRenderer.invoke('restore-main-window'),
  getTrafficRecords: () => ipcRenderer.invoke('get-traffic-records'),
  clearTrafficRecords: () => ipcRenderer.invoke('clear-traffic-records'),
  deleteTrafficRecord: (id) => ipcRenderer.invoke('delete-traffic-record', id),
  listFilterRules: () => ipcRenderer.invoke('list-filter-rules'),
  saveFilterRules: (rules) => ipcRenderer.invoke('save-filter-rules', rules),
  replayRecord: (record) => ipcRenderer.invoke('replay-record', record),
  replayRecords: (records) => ipcRenderer.invoke('replay-records', records),
  exportRecords: (format, records) => ipcRenderer.invoke('export-records', format, records),
  onTrafficCaptured: (callback) => {
    ipcRenderer.on('traffic-captured', (_event, record) => callback(record))
  },
  onCaptureStateChanged: (callback) => {
    ipcRenderer.on('capture-state-changed', (_event, state) => callback(state))
  },
  getProxyStatus: () => ipcRenderer.invoke('get-proxy-status'),
  enableSystemProxy: (port, protocols) => ipcRenderer.invoke('enable-system-proxy', port, protocols),
  disableSystemProxy: () => ipcRenderer.invoke('disable-system-proxy'),
  listSessions: () => ipcRenderer.invoke('list-sessions'),
  loadSession: (sessionId) => ipcRenderer.invoke('load-session', sessionId),
  deleteSession: (sessionId) => ipcRenderer.invoke('delete-session', sessionId),
  connectMqtt: (config) => ipcRenderer.invoke('connect-mqtt', config),
  disconnectMqtt: (id) => ipcRenderer.invoke('disconnect-mqtt', id),
  onMqttStatus: (callback) => {
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
  installBuiltinPlugin: (pluginId) => ipcRenderer.invoke('install-builtin-plugin', pluginId),
  uninstallBuiltinPlugin: () => ipcRenderer.invoke('uninstall-builtin-plugin'),
  uninstallPlugin: (pluginId) => ipcRenderer.invoke('uninstall-plugin', pluginId),
  getCoverageRelayConfig: () => ipcRenderer.invoke('get-coverage-relay-config'),
  setCoverageRelayConfig: (config) => ipcRenderer.invoke('set-coverage-relay-config', config),
  testCoverageReport: (targetUrl, body) => ipcRenderer.invoke('test-coverage-report', targetUrl, body)
})
