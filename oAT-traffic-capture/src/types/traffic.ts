// Traffic record type shared between main and renderer processes
export interface TrafficRecord {
  id: string
  caseName: string
  method: string
  url: string
  protocol: string
  statusCode: number | string
  duration: number
  timestamp: number
  requestHeaders?: Record<string, string>
  requestBody?: string
  responseHeaders?: Record<string, string>
  responseBody?: string
  error?: string
  source?: 'capture' | 'manual' | 'replay' | 'plugin'
  replayOf?: string
  replayStatus?: 'pending' | 'success' | 'failed' | 'unsupported'
  replayTime?: number
  tags?: string[]
  websocketMessages?: WsMessage[]
}

export interface ProxyStatus {
  running: boolean
  port: number
  error?: string
}

export interface CaptureSession {
  id: string
  caseName: string
  startTime: number
  endTime?: number
  records: TrafficRecord[]
}

export interface WsMessage {
  id: string
  direction: 'send' | 'receive'
  type: 'text' | 'binary'
  data: string
  timestamp: number
}

export interface WebSocketRecord extends TrafficRecord {
  protocol: 'WS' | 'WSS'
  messages: WsMessage[]
  connectionState: 'open' | 'closed'
}

export interface TrafficFilterRule {
  id: string
  name: string
  enabled: boolean
  target: 'url' | 'method' | 'protocol' | 'statusCode' | 'header' | 'body'
  operator: 'contains' | 'equals' | 'regex' | 'startsWith' | 'endsWith'
  value: string
  action: 'include' | 'exclude' | 'mark'
}

export interface ReplayResult {
  success: boolean
  record?: TrafficRecord
  error?: string
}

export interface PluginInfo {
  id: string
  name: string
  version: string
  main: string
  enabled: boolean
  path: string
  description?: string
  error?: string
}

export type BuiltinPluginId = 'traffic-cleanup-plugin' | 'all'
