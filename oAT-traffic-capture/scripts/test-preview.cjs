// 与应用一致的加载方式测试 coverage-preview 链路
const { app } = require('electron')
app.whenReady().then(async () => {
  try {
    const coverage = await import('../dist-electron/coverage/index.js')
    const config = { enabled: true, key: 'wanyuxin', headerName: 'X-Coverage-Key', agentAddress: '127.0.0.1:8899', backend: 'jacoco', classfilesPath: '' }
    try {
      const r = await coverage.previewCommand(config, 'jacoco', { reset: 'true', html: 'true', xml: 'false', csv: 'false', perKey: 'false', baseline: '', sourcefilesPath: '' }, [])
      console.log('PREVIEW_RESULT:', JSON.stringify(r).slice(0, 400))
    } catch (e) {
      console.log('PREVIEW_THREW:', e && e.message)
    }
    try {
      const r2 = await coverage.commandPreview(config, 'jacoco', 'report', { html: 'true' }, [])
      console.log('CMD_PREVIEW_RESULT:', JSON.stringify(r2).slice(0, 300))
    } catch (e) {
      console.log('CMD_PREVIEW_THREW:', e && e.message)
    }
  } catch (e) {
    console.log('IMPORT_THREW:', e && e.message)
  }
  app.quit()
})
