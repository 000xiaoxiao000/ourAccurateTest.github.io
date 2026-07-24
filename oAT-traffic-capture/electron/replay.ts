import { WebSocket } from 'ws'
import type { ReplayResult, TrafficRecord } from './types.js'

const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'content-length',
  'host',
  'proxy-authorization',
  'proxy-connection',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade'
])

function replayId(record: TrafficRecord): string {
  return `replay-${record.id}-${Date.now()}`
}

function cleanHeaders(headers?: Record<string, string>): Record<string, string> {
  const next: Record<string, string> = {}
  for (const [key, value] of Object.entries(headers ?? {})) {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      next[key] = value
    }
  }
  return next
}

function resolveReplayUrl(record: TrafficRecord): string {
  try {
    return new URL(record.url).toString()
  } catch {
    const host = record.requestHeaders?.host || record.requestHeaders?.Host
    if (!host) {
      throw new Error(`无法重放相对 URL：${record.url}，记录缺少 Host 请求头`)
    }
    const protocol = record.protocol.toUpperCase() === 'HTTPS' ? 'https' : 'http'
    return new URL(record.url, `${protocol}://${host}`).toString()
  }
}

export async function replayRecord(record: TrafficRecord): Promise<ReplayResult> {
  const protocol = record.protocol.toUpperCase()
  if (protocol === 'HTTP' || protocol === 'HTTPS') {
    return replayHttpRecord(record)
  }
  if (protocol === 'WS' || protocol === 'WSS') {
    return replayWebSocketRecord(record)
  }
  return {
    success: false,
    record: {
      ...record,
      id: replayId(record),
      source: 'replay',
      replayOf: record.id,
      replayStatus: 'unsupported',
      replayTime: Date.now(),
      error: `${record.protocol} 暂不支持自动重放`
    },
    error: `${record.protocol} 暂不支持自动重放`
  }
}

async function replayHttpRecord(record: TrafficRecord): Promise<ReplayResult> {
  const start = Date.now()
  try {
    const url = resolveReplayUrl(record)
    const response = await fetch(url, {
      method: record.method,
      headers: cleanHeaders(record.requestHeaders),
      body: ['GET', 'HEAD'].includes(record.method.toUpperCase()) ? undefined : record.requestBody
    })
    const responseHeaders: Record<string, string> = {}
    response.headers.forEach((value, key) => {
      responseHeaders[key] = value
    })
    const responseBody = await response.text()
    return {
      success: true,
      record: {
        ...record,
        id: replayId(record),
        source: 'replay',
        replayOf: record.id,
        replayStatus: response.ok ? 'success' : 'failed',
        replayTime: Date.now(),
        url,
        statusCode: response.status,
        duration: Date.now() - start,
        responseHeaders,
        responseBody,
        error: undefined
      }
    }
  } catch (error: any) {
    return {
      success: false,
      record: {
        ...record,
        id: replayId(record),
        source: 'replay',
        replayOf: record.id,
        replayStatus: 'failed',
        replayTime: Date.now(),
        statusCode: 'ERROR',
        duration: Date.now() - start,
        error: error?.message ?? String(error)
      },
      error: error?.message ?? String(error)
    }
  }
}

async function replayWebSocketRecord(record: TrafficRecord): Promise<ReplayResult> {
  const start = Date.now()
  const messages = (record.websocketMessages ?? []).filter(message => message.direction === 'send')
  if (messages.length === 0) {
    return {
      success: false,
      record: {
        ...record,
        id: replayId(record),
        source: 'replay',
        replayOf: record.id,
        replayStatus: 'unsupported',
        replayTime: Date.now(),
        error: '没有可重放的 WebSocket 发送消息'
      },
      error: '没有可重放的 WebSocket 发送消息'
    }
  }

  return new Promise((resolve) => {
    const ws = new WebSocket(record.url, { headers: cleanHeaders(record.requestHeaders) })
    const received: TrafficRecord['websocketMessages'] = []
    const timeout = setTimeout(() => {
      ws.close()
      resolve({
        success: true,
        record: {
          ...record,
          id: replayId(record),
          source: 'replay',
          replayOf: record.id,
          replayStatus: 'success',
          replayTime: Date.now(),
          duration: Date.now() - start,
          websocketMessages: [...messages, ...(received ?? [])]
        }
      })
    }, 5000)

    ws.on('open', () => {
      messages.forEach((message, index) => {
        setTimeout(() => ws.send(message.data), index * 100)
      })
    })
    ws.on('message', (data, isBinary) => {
      received?.push({
        id: `ws-recv-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        direction: 'receive',
        type: isBinary ? 'binary' : 'text',
        data: isBinary ? Buffer.from(data as Buffer).toString('base64') : data.toString(),
        timestamp: Date.now()
      })
    })
    ws.on('error', (error) => {
      clearTimeout(timeout)
      resolve({
        success: false,
        record: {
          ...record,
          id: replayId(record),
          source: 'replay',
          replayOf: record.id,
          replayStatus: 'failed',
          replayTime: Date.now(),
          duration: Date.now() - start,
          error: error.message
        },
        error: error.message
      })
    })
  })
}
