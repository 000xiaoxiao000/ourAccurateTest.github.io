import mqtt, { type MqttClient } from 'mqtt'
import type { TrafficRecord } from '../types.js'

export interface MqttConnectionConfig {
  id: string
  brokerUrl: string
  topics: string[]
  username?: string
  password?: string
}

export type MqttConnectionStatus = 'connected' | 'error' | 'closed'

const connections = new Map<string, MqttClient>()

export function connectMqtt(
  config: MqttConnectionConfig,
  onMessage: (record: TrafficRecord) => void,
  onStatus: (id: string, status: MqttConnectionStatus, error?: string) => void
): void {
  disconnectMqtt(config.id)

  const client = mqtt.connect(config.brokerUrl, {
    username: config.username,
    password: config.password,
    reconnectPeriod: 5000
  })

  client.on('connect', () => {
    onStatus(config.id, 'connected')
    config.topics.forEach((topic) => client.subscribe(topic))
  })

  client.on('message', (topic, payload) => {
    onMessage({
      id: `mqtt-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      caseName: '',
      method: 'RECV',
      url: `${config.brokerUrl}/${topic}`,
      protocol: 'MQTT',
      statusCode: 200,
      duration: 0,
      timestamp: Date.now(),
      requestHeaders: { topic },
      requestBody: payload.toString()
    })
  })

  client.on('error', (error) => onStatus(config.id, 'error', error.message))
  client.on('close', () => onStatus(config.id, 'closed'))

  connections.set(config.id, client)
}

export function disconnectMqtt(id: string): void {
  connections.get(id)?.end()
  connections.delete(id)
}

export function disconnectAllMqtt(): void {
  connections.forEach((client) => client.end())
  connections.clear()
}
