import Proxy from 'http-mitm-proxy'
import type { CaptureProtocolConfig, TrafficRecord, WsMessage } from './types.js'

const defaultProtocols: CaptureProtocolConfig = {
  http: true,
  https: true,
  ws: true,
  wss: true
}

export function createProxyServer(
  onTraffic: (record: TrafficRecord) => void,
  sslCaDir?: string,
  enabledProtocols: CaptureProtocolConfig = defaultProtocols
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
    ctx.onResponse((ctx: any, callback: any) => {
      callback()
    })

    ctx.onResponseData((ctx: any, chunk: Buffer, callback: any) => {
      responseBody += chunk.toString()
      return callback(null, chunk)
    })

    ctx.onResponseEnd((ctx: any, callback: any) => {
      const endTime = Date.now()
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
