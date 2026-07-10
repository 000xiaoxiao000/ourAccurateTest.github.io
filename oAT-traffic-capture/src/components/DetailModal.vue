<script setup lang="ts">
import { computed } from 'vue'
import type { TrafficRecord } from '../types/traffic'

const props = defineProps<{
  record: TrafficRecord | null
  show: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const hasRequestBody = computed(() => props.record?.requestBody && props.record.requestBody.trim())
const hasResponseBody = computed(() => props.record?.responseBody && props.record.responseBody.trim())
const websocketMessages = computed(() => props.record?.websocketMessages ?? [])

function formatHeaders(headers?: Record<string, string>): string {
  if (!headers) return ''
  return Object.entries(headers)
    .map(([key, value]) => `${key}: ${value}`)
    .join('\n')
}

function getMethodClass(method: string): string {
  const map: Record<string, string> = {
    GET: 'm-get',
    POST: 'm-post',
    PUT: 'm-put',
    DELETE: 'm-del',
    SEND: 'm-post'
  }
  return map[method] || 'm-get'
}

function getStatusClass(status: number | string): string {
  const code = Number(status)
  if (isNaN(code)) return 's-2xx'
  if (code >= 500) return 's-5xx'
  if (code >= 400) return 's-4xx'
  return 's-2xx'
}
</script>

<template>
  <div v-if="show" class="modal-overlay" @click.self="emit('close')">
    <div class="modal">
      <div class="modal-header">
        <h3>请求详情</h3>
        <button class="modal-close" @click="emit('close')">×</button>
      </div>
      <div v-if="record" class="modal-body">
        <div class="detail-row">
          <span class="detail-label">用例名称</span>
          <span class="detail-value">{{ record.caseName || '未命名' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">请求方法</span>
          <span class="detail-value">
            <span class="badge" :class="getMethodClass(record.method)">
              {{ record.method }}
            </span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-label">请求 URL</span>
          <span class="detail-value url-value">{{ record.url }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">协议</span>
          <span class="detail-value">{{ record.protocol }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">状态码</span>
          <span class="detail-value">
            <span class="badge" :class="getStatusClass(record.statusCode)">
              {{ record.statusCode }}
            </span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-label">耗时</span>
          <span class="detail-value">{{ record.duration }}ms</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">时间</span>
          <span class="detail-value">{{ new Date(record.timestamp).toLocaleString('zh-CN') }}</span>
        </div>

        <div v-if="record.requestHeaders" class="detail-section">
          <h4>请求头</h4>
          <div class="code-block">{{ formatHeaders(record.requestHeaders) }}</div>
        </div>

        <div v-if="hasRequestBody" class="detail-section">
          <h4>请求体</h4>
          <div class="code-block">{{ record.requestBody }}</div>
        </div>

        <div v-if="record.responseHeaders" class="detail-section">
          <h4>响应头</h4>
          <div class="code-block">{{ formatHeaders(record.responseHeaders) }}</div>
        </div>

        <div v-if="hasResponseBody" class="detail-section">
          <h4>响应体</h4>
          <div class="code-block">{{ record.responseBody }}</div>
        </div>

        <div v-if="record.error" class="detail-section">
          <h4>错误信息</h4>
          <div class="code-block error">{{ record.error }}</div>
        </div>

        <div v-if="websocketMessages.length > 0" class="detail-section">
          <h4>WebSocket 消息</h4>
          <div class="ws-list">
            <div v-for="message in websocketMessages" :key="message.id" class="ws-message" :class="message.direction">
              <span>{{ message.direction === 'send' ? '发送' : '接收' }}</span>
              <time>{{ new Date(message.timestamp).toLocaleTimeString('zh-CN') }}</time>
              <code>{{ message.data }}</code>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 100;
  display: flex;
  justify-content: center;
  align-items: center;
}

.modal {
  background: white;
  border-radius: 8px;
  width: 680px;
  max-width: 95vw;
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.modal-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.modal-header h3 {
  font-size: 16px;
  color: #262626;
  margin: 0;
}

.modal-close {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #8c8c8c;
  line-height: 1;
  padding: 0;
  width: 24px;
  height: 24px;
}

.modal-close:hover {
  color: #262626;
}

.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.detail-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  align-items: flex-start;
}

.detail-label {
  width: 90px;
  color: #8c8c8c;
  font-size: 13px;
  flex-shrink: 0;
  padding-top: 2px;
}

.detail-value {
  flex: 1;
  font-size: 13px;
  color: #262626;
  word-break: break-all;
}

.url-value {
  word-break: break-all;
}

.detail-section {
  margin-top: 20px;
}

.detail-section h4 {
  font-size: 13px;
  color: #8c8c8c;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.code-block {
  background: #f6f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 12px;
  font-family: 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.code-block.error {
  background: #fff1f0;
  border-color: #ffa39e;
  color: #cf1322;
}

.ws-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ws-message {
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 8px;
  display: grid;
  grid-template-columns: 44px 82px 1fr;
  gap: 8px;
  align-items: start;
  font-size: 12px;
}

.ws-message.send {
  border-color: #91d5ff;
  background: #e6f7ff;
}

.ws-message.receive {
  border-color: #b7eb8f;
  background: #f6ffed;
}

.ws-message code {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Monaco', 'Consolas', monospace;
}

.badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  min-width: 56px;
}

.m-get { background: #e6f7ff; color: #1890ff; }
.m-post { background: #f6ffed; color: #52c41a; }
.m-put { background: #fff7e6; color: #fa8c16; }
.m-del { background: #fff1f0; color: #ff4d4f; }

.s-2xx { background: #f6ffed; color: #52c41a; }
.s-4xx { background: #fff7e6; color: #fa8c16; }
.s-5xx { background: #fff1f0; color: #ff4d4f; }
</style>
