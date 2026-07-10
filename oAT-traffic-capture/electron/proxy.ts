import Proxy from 'http-mitm-proxy'
import type { CaptureProtocolConfig, CoverageRelayConfig, TrafficRecord, WsMessage } from './types.js'

const defaultProtocols: CaptureProtocolConfig = {
  http: true,
  https: true,
  ws: true,
  wss: true
}

export type CoverageReporterOptions = {
  isEnabled: () => boolean
  getConfig: () => CoverageRelayConfig
}

export type CoverageReportBody = {
  serviceBaseUrl?: string
  endpointBaseUrl?: string
  relayBaseUrl?: string
  targetType?: 'relay' | 'service'
  projectId?: string
  appId?: string
  appKey?: string
  requestId?: string
  sourceType?: string
  commitId?: string
  versionNumber?: string
  branch?: string
  caseName?: string
  timestamp?: number
  intervalMs?: number
  coverage?: unknown
  coverageData?: unknown
  data?: unknown
  profile?: unknown
  coverageMissing?: boolean
}

const lastRelayedCoverageSignatures = new Map<string, string>()

export function coverageResponseHeaders(headers?: Record<string, string | string[] | undefined>): Record<string, string> {
  const origin = String(headers?.origin || '')
  const requestHeaders = String(headers?.['access-control-request-headers'] || 'content-type')
  return {
    'content-type': 'application/json',
    'access-control-allow-origin': origin || '*',
    'access-control-allow-credentials': origin ? 'true' : 'false',
    'access-control-allow-methods': 'POST, OPTIONS',
    'access-control-allow-headers': requestHeaders,
    'vary': 'Origin',
    'x-oat-coverage-relay': 'intercepted'
  }
}

function parseCoverageReportBody(requestBody: string): CoverageReportBody | null {
  if (!requestBody) return null
  try {
    return JSON.parse(requestBody) as CoverageReportBody
  } catch {
    return null
  }
}

function buildCoverageRelayTarget(
  record: Partial<TrafficRecord>,
  body: CoverageReportBody | null,
  coverageReporter?: CoverageReporterOptions
): string {
  const config = coverageReporter?.getConfig() ?? { enabled: false, intervalMs: 30000, coveragePort: 8889, proxyPort: 8888 }
  const relayBaseUrl = body?.relayBaseUrl || config.relayBaseUrl || ''
  const serviceBaseUrl = body?.serviceBaseUrl || body?.endpointBaseUrl || config.serviceBaseUrl || process.env.OAT_SERVICE_BASE_URL || ''
  const targetType = body?.targetType || config.targetType || (relayBaseUrl ? 'relay' : 'service')
  const targetBaseUrl = targetType === 'relay' ? relayBaseUrl : serviceBaseUrl
  const projectId = resolveCoverageProjectId(body, config)
  const appId = resolveCoverageAppId(body, config)
  const sourceType = normalizeCoverageSourceType(body, record.url)
  const reportPath = sourceType === 'FRONTEND'
    ? 'coverage/frontend/report'
    : `coverage/universal/${sourceType}/report`
  if (targetBaseUrl && projectId && appId) {
    return `${String(targetBaseUrl).replace(/\/$/, '')}/api/projects/${encodeURIComponent(projectId)}/apps/${encodeURIComponent(appId)}/${reportPath}`
  }
  const match = String(record.url || '').match(/\/api\/projects\/[^/]+\/apps\/[^/]+\/coverage\/(?:frontend|universal\/(?:CPP|GO|PYTHON))\/report/i)
  if (!match) return ''
  try {
    const parsed = new URL(String(record.url))
    return `${parsed.origin}${match[0]}`
  } catch {
    return match[0]
  }
}

function resolveCoverageProjectId(body: CoverageReportBody | null, config: CoverageRelayConfig): string | undefined {
  return config.projectId || body?.projectId
}

function resolveCoverageAppId(body: CoverageReportBody | null, config: CoverageRelayConfig): string | undefined {
  return config.appId || body?.appId || body?.appKey
}

function normalizeCoverageSourceType(body?: CoverageReportBody | null, url?: string): 'FRONTEND' | 'CPP' | 'GO' | 'PYTHON' {
  const fromUrl = String(url || '').match(/\/coverage\/universal\/([^/?#]+)\/report/i)?.[1]
  const raw = String(body?.sourceType || fromUrl || 'FRONTEND').trim().toUpperCase()
  if (raw === 'CPP' || raw === 'C++' || raw === 'C' || raw === 'CXX') return 'CPP'
  if (raw === 'GO' || raw === 'GOLANG') return 'GO'
  if (raw === 'PYTHON' || raw === 'PY') return 'PYTHON'
  return 'FRONTEND'
}

function coveragePayload(body: CoverageReportBody | null): unknown {
  if (!body) return undefined
  if (body.coverageData !== undefined) return body.coverageData
  if (body.data !== undefined) return body.data
  if (body.profile !== undefined) return body.profile
  return body.coverage
}

function coverageSignature(coverage: unknown): string {
  try {
    return JSON.stringify(coverage)
  } catch {
    return String(coverage)
  }
}

function coverageDedupeKey(targetUrl: string, body: CoverageReportBody | null): string {
  return [
    targetUrl,
    body?.projectId || '',
    body?.appId || body?.appKey || '',
    body?.versionNumber || '',
    body?.commitId || '',
    body?.branch || ''
  ].join('|')
}

function coverageRelayInfo(
  status: 'success' | 'failed' | 'skipped',
  body: CoverageReportBody | null,
  coverageReporter?: CoverageReporterOptions,
  options: { targetUrl?: string; httpStatus?: number; error?: string; requestId?: string } = {}
) {
  const config = coverageReporter?.getConfig() ?? { enabled: false, intervalMs: 30000, coveragePort: 8889, proxyPort: 8888 }
  const intervalMs = Number(body?.intervalMs) > 0 ? Number(body?.intervalMs) : config.intervalMs
  const now = Date.now()
  return {
    status,
    targetUrl: options.targetUrl,
    httpStatus: options.httpStatus,
    error: options.error,
    intervalMs,
    nextReportAt: now + intervalMs,
    requestId: options.requestId || body?.requestId,
    projectId: resolveCoverageProjectId(body, config),
    appId: resolveCoverageAppId(body, config),
    versionNumber: body?.versionNumber,
    commitId: body?.commitId
  }
}

export async function relayCoverageReport(
  record: Partial<TrafficRecord>,
  requestBody: string,
  coverageReporter?: CoverageReporterOptions
): Promise<{
  statusCode: number
  responseBody: string
}> {
  const body = parseCoverageReportBody(requestBody)
  const sourceType = normalizeCoverageSourceType(body, record.url)
  const payload = coveragePayload(body)
  const tags = new Set(record.tags || [])
  tags.add('COVERAGE')
  tags.add(sourceType)
  record.tags = Array.from(tags)

  if (sourceType === 'FRONTEND' && body?.coverageMissing) {
    record.coverageRelay = coverageRelayInfo('skipped', body, coverageReporter, {
      error: '页面未发现 window.__coverage__，请确认被测前端已启用 Istanbul 插桩并刷新页面'
    })
    record.statusCode = '未上送'
    return {
      statusCode: 200,
      responseBody: JSON.stringify({ result: true, skipped: true, reason: 'coverageMissing', message: '页面未发现 window.__coverage__，请确认被测前端已启用 Istanbul 插桩并刷新页面' })
    }
  }

  if (payload === undefined || payload === null || payload === '') {
    const expectedField = sourceType === 'FRONTEND' ? 'coverage' : 'coverageData/data/profile'
    record.coverageRelay = coverageRelayInfo('skipped', body, coverageReporter, { error: `请求体没有 ${expectedField} 字段` })
    record.statusCode = '未上送'
    return {
      statusCode: 400,
      responseBody: JSON.stringify({ result: false, message: `请求体没有 ${expectedField} 字段` })
    }
  }

  const targetUrl = buildCoverageRelayTarget(record, body, coverageReporter)
  if (!targetUrl) {
    record.coverageRelay = coverageRelayInfo('failed', body, coverageReporter, {
      error: '缺少 serviceBaseUrl/projectId/appId，无法确定服务端上送地址'
    })
    record.statusCode = '上送失败'
    return {
      statusCode: 400,
      responseBody: JSON.stringify({ result: false, message: '缺少 serviceBaseUrl/projectId/appId，无法确定服务端上送地址' })
    }
  }

  const signature = coverageSignature(payload)
  const dedupeKey = coverageDedupeKey(targetUrl, body)
  if (lastRelayedCoverageSignatures.get(dedupeKey) === signature) {
    record.coverageRelay = coverageRelayInfo('skipped', body, coverageReporter, {
      targetUrl,
      error: '覆盖率数据未变化，已跳过重复上送'
    })
    record.statusCode = '未上送'
    return {
      statusCode: 200,
      responseBody: JSON.stringify({ result: true, skipped: true, reason: 'unchanged', message: '覆盖率数据未变化，已跳过重复上送' })
    }
  }

  const requestId = body?.requestId || record.id
  try {
    const requestPayload = {
      requestId,
      commitId: body?.commitId,
      versionNumber: body?.versionNumber,
      branch: body?.branch,
      caseName: body?.caseName || record.caseName,
      timestamp: body?.timestamp || record.timestamp || Date.now(),
      ...(sourceType === 'FRONTEND' ? { coverage: payload } : { coverageData: payload })
    }
    const response = await fetch(targetUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(requestId ? { 'X-OAT-Request-Id': String(requestId) } : {})
      },
      body: JSON.stringify(requestPayload)
    })
    const responseText = await response.text().catch(() => '')
    if (response.ok) {
      lastRelayedCoverageSignatures.set(dedupeKey, signature)
    }
    record.coverageRelay = coverageRelayInfo(response.ok ? 'success' : 'failed', body, coverageReporter, {
      targetUrl,
      httpStatus: response.status,
      requestId: String(requestId),
      error: response.ok ? undefined : `服务端返回 HTTP ${response.status}`
    })
    record.statusCode = response.ok ? '已上送' : '上送失败'
    return {
      statusCode: response.ok ? 200 : 502,
      responseBody: responseText || JSON.stringify({
        result: response.ok,
        message: response.ok ? `${sourceType}覆盖率已由采集器上送到 oAT-service-web` : `oAT-service-web 返回 HTTP ${response.status}`
      })
    }
  } catch (error: any) {
    record.coverageRelay = coverageRelayInfo('failed', body, coverageReporter, {
      targetUrl,
      requestId: requestId ? String(requestId) : undefined,
      error: error?.message || String(error)
    })
    record.statusCode = '上送失败'
    return {
      statusCode: 502,
      responseBody: JSON.stringify({ result: false, message: error?.message || String(error) })
    }
  }
}

export function createProxyServer(
  onTraffic: (record: TrafficRecord) => void,
  sslCaDir?: string,
  enabledProtocols: CaptureProtocolConfig = defaultProtocols,
  coverageReporter?: CoverageReporterOptions
) {
  const proxyFactory = Proxy as unknown as () => any
  const proxy = proxyFactory()
  proxy.use?.((Proxy as any).gunzip)
  let protocols = { ...defaultProtocols, ...enabledProtocols }
  proxy.setEnabledProtocols = (nextProtocols: CaptureProtocolConfig) => {
    protocols = { ...defaultProtocols, ...nextProtocols }
  }
  if (sslCaDir) {
    const originalListen = proxy.listen.bind(proxy)
    proxy.listen = (options: Record<string, unknown> = {}, callback?: (...args: unknown[]) => void) => {
      originalListen({ ...options, sslCaDir }, callback)
    }
  }
  const websocketRecords = new WeakMap<object, TrafficRecord>()

  function headerRecord(headers?: Record<string, string | string[]>): Record<string, string> {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(headers ?? {})) {
      next[key] = Array.isArray(value) ? value.join(', ') : String(value)
    }
    return next
  }

  function getWebSocketUrl(ctx: any): string {
    const req = ctx.clientToProxyWebSocket?.upgradeReq
    if (!req) return ''
    if (req.url && !req.url.startsWith('/')) return req.url
    const host = req.headers?.host ?? ''
    return `${ctx.isSSL ? 'wss' : 'ws'}://${host}${req.url ?? ''}`
  }

  function getHttpUrl(ctx: any): string {
    const requestUrl = ctx.clientToProxyRequest.url ?? ''
    if (requestUrl && !requestUrl.startsWith('/')) {
      return requestUrl
    }
    const host = ctx.clientToProxyRequest.headers.host ?? ''
    return `${ctx.isSSL ? 'https' : 'http'}://${host}${requestUrl}`
  }

  function isProtocolEnabled(protocol: keyof CaptureProtocolConfig): boolean {
    return protocols[protocol] !== false
  }

  function messageData(message: any): Pick<WsMessage, 'type' | 'data'> {
    if (Buffer.isBuffer(message)) {
      return { type: 'binary', data: message.toString('base64') }
    }
    return { type: 'text', data: String(message) }
  }

  function isCoverageRelayEnabled(): boolean {
    return coverageReporter?.isEnabled() === true
  }

  function isCoverageReportUrl(url?: string): boolean {
    return /\/oat\/coverage\/report(\?|$)/i.test(url ?? '')
      || /\/api\/projects\/[^/]+\/apps\/[^/]+\/coverage\/frontend\/report(\?|$)/i.test(url ?? '')
      || /\/api\/projects\/[^/]+\/apps\/[^/]+\/coverage\/universal\/(?:CPP|GO|PYTHON)\/report(\?|$)/i.test(url ?? '')
  }


  function isHtmlResponse(ctx: any): boolean {
    const contentType = String(ctx.serverToProxyResponse?.headers?.['content-type'] ?? '').toLowerCase()
    return contentType.includes('text/html')
  }

  function isJavaScriptResponse(ctx: any): boolean {
    const contentType = String(ctx.serverToProxyResponse?.headers?.['content-type'] ?? '').toLowerCase()
    return contentType.includes('javascript') || contentType.includes('ecmascript')
  }

  function shouldInjectCoverageReporter(ctx: any, record: Partial<TrafficRecord>): boolean {
    if (!isCoverageRelayEnabled()) return false
    if (!isHtmlResponse(ctx) && !isJavaScriptResponse(ctx)) return false
    if (record.method && !['GET', ''].includes(String(record.method).toUpperCase())) return false
    return !isCoverageReportUrl(record.url)
  }

  function handleCoverageReportRequest(ctx: any, record: Partial<TrafficRecord>, startTime: number): void {
    let requestBody = ''
    ctx.clientToProxyRequest.on('data', (chunk: Buffer) => {
      requestBody += chunk.toString()
    })
    ctx.clientToProxyRequest.on('end', async () => {
      record.requestBody = requestBody
      record.responseHeaders = coverageResponseHeaders(ctx.clientToProxyRequest?.headers)
      if (String(record.method).toUpperCase() === 'OPTIONS') {
        record.duration = Date.now() - startTime
        record.statusCode = 204
        record.responseBody = ''
        if (!ctx.proxyToClientResponse.headersSent) {
          ctx.proxyToClientResponse.writeHead(204, record.responseHeaders)
        }
        ctx.proxyToClientResponse.end()
        return
      }

      const relayResult = await relayCoverageReport(record, requestBody, coverageReporter)
      record.duration = Date.now() - startTime
      record.responseBody = relayResult.responseBody
      record.responseHeaders = {
        ...record.responseHeaders,
        'content-length': Buffer.byteLength(relayResult.responseBody).toString()
      }
      if (!record.statusCode) {
        record.statusCode = relayResult.statusCode
      }
      if (record.coverageRelay) {
        onTraffic(record as TrafficRecord)
      }

      if (!ctx.proxyToClientResponse.headersSent) {
        ctx.proxyToClientResponse.writeHead(relayResult.statusCode, record.responseHeaders)
      }
      ctx.proxyToClientResponse.end(relayResult.responseBody)
    })
    ctx.clientToProxyRequest.on('error', (err: Error) => {
      record.statusCode = 'ERROR'
      record.error = err.message
      record.duration = Date.now() - startTime
      onTraffic(record as TrafficRecord)
      if (!ctx.proxyToClientResponse.headersSent) {
        ctx.proxyToClientResponse.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
      }
      ctx.proxyToClientResponse.end(err.message)
    })
    ctx.clientToProxyRequest.resume()
  }

  function buildCoverageEndpoint(_config: CoverageRelayConfig): string {
    const port = Number(_config.coveragePort) > 0 ? Number(_config.coveragePort) : 8889
    return `http://localhost:${port}/oat/coverage/report`
  }

  function buildCoverageReporterCode(): string {
    const config = coverageReporter?.getConfig() ?? { enabled: false, intervalMs: 30000 }
    const intervalMs = Number.isFinite(Number(config.intervalMs)) && Number(config.intervalMs) >= 1000
      ? Math.round(Number(config.intervalMs))
      : 30000
    const endpoint = buildCoverageEndpoint(config)
    const meta = {
      targetType: config.targetType,
      serviceBaseUrl: config.serviceBaseUrl,
      relayBaseUrl: config.relayBaseUrl,
      projectId: config.projectId,
      appId: config.appId
    }
    return `;(() => {
  if (window.__oatCoverageReporterInstalled) return;
  window.__oatCoverageReporterInstalled = true;
  const endpoint = ${JSON.stringify(endpoint)};
  const intervalMs = ${intervalMs};
  const meta = ${JSON.stringify(meta)};
  let missingReported = false;
  let inFlight = false;
  let lastSentCoverageSignature = '';
  const send = (payload, coverageSignature) => {
    if (inFlight) return;
    const body = JSON.stringify({ ...meta, ...payload });
    if (window.fetch) {
      inFlight = true;
      fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
        credentials: 'omit'
      }).then((response) => {
        return response.clone().json().catch(() => null).then((data) => {
          if (coverageSignature && response.ok && !data?.skipped && data?.result !== false) {
            lastSentCoverageSignature = coverageSignature;
          }
          if (data?.skipped || data?.result === false) {
            console.info('[oAT coverage]', data.message || data.reason || 'coverage relay response', data);
          }
        });
      }).catch(() => {}).finally(() => {
        inFlight = false;
      });
      return;
    }
  };
  const report = (diagnoseMissing = false) => {
    const coverage = window.__coverage__;
    if (!coverage) {
      if (diagnoseMissing && !missingReported) {
        missingReported = true;
        send({ coverageMissing: true, timestamp: Date.now(), intervalMs, href: location.href });
      }
      return;
    }
    const coverageSignature = JSON.stringify(coverage);
    if (coverageSignature === lastSentCoverageSignature) return;
    send({ coverage, timestamp: Date.now(), intervalMs, href: location.href }, coverageSignature);
  };
  window.setTimeout(() => report(true), 1200);
  window.setInterval(report, intervalMs);
  window.addEventListener('beforeunload', report);
})();`
  }

  function buildCoverageReporterScript(): string {
    return `<script>${buildCoverageReporterCode()}</script>`
  }

  function injectCoverageReporter(body: string, ctx: any): string {
    if (body.includes('__oatCoverageReporterInstalled')) return body
    if (isJavaScriptResponse(ctx)) {
      return `${body}\n${buildCoverageReporterCode()}\n`
    }
    const script = buildCoverageReporterScript()
    if (/<\/head>/i.test(body)) {
      return body.replace(/<\/head>/i, `${script}</head>`)
    }
    if (/<\/body>/i.test(body)) {
      return body.replace(/<\/body>/i, `${script}</body>`)
    }
    return `${body}${script}`
  }

  proxy.onRequest((ctx: any, callback: any) => {
    const protocol = ctx.isSSL ? 'https' : 'http'
    if (!isProtocolEnabled(protocol)) {
      callback()
      return
    }
    const startTime = Date.now()
    const requestId = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    
    const record: Partial<TrafficRecord> = {
      id: requestId,
      caseName: '',
      method: ctx.clientToProxyRequest.method,
      url: getHttpUrl(ctx),
      protocol: protocol.toUpperCase(),
      timestamp: Date.now(),
      requestHeaders: headerRecord(ctx.clientToProxyRequest.headers),
      source: 'capture'
    }

    if (isCoverageRelayEnabled() && isCoverageReportUrl(record.url)) {
      handleCoverageReportRequest(ctx, record, startTime)
      return
    }

    let requestBody = ''
    ctx.onRequestData((ctx: any, chunk: Buffer, callback: any) => {
      requestBody += chunk.toString()
      return callback(null, chunk)
    })

    ctx.onRequestEnd((ctx: any, callback: any) => {
      record.requestBody = requestBody
      callback()
    })

    let responseBody = ''
    const responseChunks: Buffer[] = []
    let shouldInjectReporter = false
    ctx.onResponse((ctx: any, callback: any) => {
      shouldInjectReporter = shouldInjectCoverageReporter(ctx, record)
      if (shouldInjectReporter) {
        ctx.responseContentPotentiallyModified = true
      }
      callback()
    })

    ctx.onResponseData((ctx: any, chunk: Buffer, callback: any) => {
      if (shouldInjectReporter) {
        responseChunks.push(chunk)
        return callback(null, null)
      }
      responseBody += chunk.toString()
      return callback(null, chunk)
    })

    ctx.onResponseEnd((ctx: any, callback: any) => {
      const endTime = Date.now()
      if (shouldInjectReporter) {
        responseBody = injectCoverageReporter(Buffer.concat(responseChunks).toString(), ctx)
        ctx.proxyToClientResponse.write(responseBody)
      }
      record.duration = endTime - startTime
      record.statusCode = ctx.serverToProxyResponse?.statusCode || 0
      record.responseHeaders = ctx.serverToProxyResponse?.headers
      record.responseBody = responseBody

      onTraffic(record as TrafficRecord)
      callback()
    })

    ctx.onError((ctx: any, err: Error) => {
      record.statusCode = 'ERROR'
      record.error = err.message
      record.duration = Date.now() - startTime
      onTraffic(record as TrafficRecord)
    })

    callback()
  })

  proxy.onWebSocketConnection((ctx: any, callback: any) => {
    const protocol = ctx.isSSL ? 'wss' : 'ws'
    if (!isProtocolEnabled(protocol)) {
      callback()
      return
    }
    const req = ctx.clientToProxyWebSocket?.upgradeReq
    const record: TrafficRecord = {
      id: `ws-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      caseName: '',
      method: 'WS',
      url: getWebSocketUrl(ctx),
      protocol: protocol.toUpperCase(),
      statusCode: 'OPEN',
      duration: 0,
      timestamp: Date.now(),
      requestHeaders: headerRecord(req?.headers),
      source: 'capture',
      websocketMessages: []
    }
    websocketRecords.set(ctx, record)
    callback()
  })

  proxy.onWebSocketFrame((ctx: any, type: string, fromServer: boolean, message: any, flags: any, callback: any) => {
    const record = websocketRecords.get(ctx)
    if (record && type === 'message') {
      const data = messageData(message)
      record.websocketMessages?.push({
        id: `ws-msg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        direction: fromServer ? 'receive' : 'send',
        type: data.type,
        data: data.data,
        timestamp: Date.now()
      })
    }
    callback(null, message, flags)
  })

  proxy.onWebSocketClose((ctx: any, code: any, message: any, callback: any) => {
    const record = websocketRecords.get(ctx)
    if (record) {
      record.statusCode = code || 'CLOSED'
      record.duration = Date.now() - record.timestamp
      if (message) {
        record.error = String(message)
      }
      onTraffic(record)
      websocketRecords.delete(ctx)
    }
    callback(null, code, message)
  })

  proxy.onWebSocketError((ctx: any, err: Error | undefined) => {
    const record = websocketRecords.get(ctx)
    if (record) {
      record.statusCode = 'ERROR'
      record.duration = Date.now() - record.timestamp
      record.error = err?.message
      onTraffic(record)
      websocketRecords.delete(ctx)
    }
  })

  return proxy
}
