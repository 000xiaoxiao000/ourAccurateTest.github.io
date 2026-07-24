<script setup lang="ts">
import { ref } from 'vue'
import type { TrafficRecord } from '../types/traffic'

defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  close: []
  add: [record: TrafficRecord]
}>()

const form = ref({
  method: 'SEND',
  url: '',
  protocol: 'AMQP',
  statusCode: 'ACK',
  requestBody: '',
  responseBody: ''
})

const protocols = ['AMQP', 'MQTT', 'Kafka', 'RocketMQ', 'RabbitMQ', 'ActiveMQ']
const methods = ['SEND', 'RECEIVE', 'PUBLISH', 'SUBSCRIBE', 'ACK', 'NACK']

function handleAdd() {
  if (!form.value.url.trim()) {
    alert('请填写 Topic / Queue 地址')
    return
  }
  const record: TrafficRecord = {
    id: `manual-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
    caseName: '',
    method: form.value.method,
    url: form.value.url,
    protocol: form.value.protocol,
    statusCode: form.value.statusCode,
    duration: 0,
    timestamp: Date.now(),
    requestBody: form.value.requestBody,
    responseBody: form.value.responseBody
  }
  emit('add', record)
  resetForm()
  emit('close')
}

function resetForm() {
  form.value = {
    method: 'SEND',
    url: '',
    protocol: 'AMQP',
    statusCode: 'ACK',
    requestBody: '',
    responseBody: ''
  }
}
</script>

<template>
  <div v-if="show" class="modal-overlay" @click.self="emit('close')">
    <div class="modal">
      <div class="modal-header">
        <h3>手动录入 MQ 流量</h3>
        <button class="modal-close" @click="emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div class="form-row">
          <div class="form-group half">
            <label>协议类型</label>
            <select v-model="form.protocol">
              <option v-for="p in protocols" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="form-group half">
            <label>操作方法</label>
            <select v-model="form.method">
              <option v-for="m in methods" :key="m" :value="m">{{ m }}</option>
            </select>
          </div>
        </div>

        <div class="form-group">
          <label>Topic / Queue 地址 <span class="required">*</span></label>
          <input
            v-model="form.url"
            type="text"
            placeholder="例如：amqp://mq.example.com/queue/orders"
          />
        </div>

        <div class="form-group">
          <label>状态 / 结果</label>
          <input
            v-model="form.statusCode"
            type="text"
            placeholder="例如：ACK、NACK、200"
          />
        </div>

        <div class="form-group">
          <label>消息内容（请求体）</label>
          <textarea
            v-model="form.requestBody"
            placeholder='例如：{"event":"ORDER_CREATED","orderId":"ORD-001"}'
            rows="4"
          ></textarea>
        </div>

        <div class="form-group">
          <label>响应内容</label>
          <textarea
            v-model="form.responseBody"
            placeholder="响应消息内容（可选）"
            rows="3"
          ></textarea>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" @click="emit('close')">取消</button>
        <button class="btn btn-primary" @click="handleAdd">添加记录</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 100;
  display: flex;
  justify-content: center;
  align-items: center;
}

.modal {
  background: white;
  border-radius: 8px;
  width: 560px;
  max-width: 95vw;
  max-height: 85vh;
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

.modal-header h3 { font-size: 16px; color: #262626; margin: 0; }

.modal-close {
  background: none; border: none;
  font-size: 24px; cursor: pointer;
  color: #8c8c8c; line-height: 1;
  padding: 0; width: 24px; height: 24px;
}

.modal-close:hover { color: #262626; }

.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}

.modal-footer {
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.form-row { display: flex; gap: 12px; }

.form-group {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
}

.form-group.half { flex: 1; }

.form-group label {
  font-size: 13px;
  font-weight: 500;
  color: #262626;
  margin-bottom: 6px;
}

.required { color: #ff4d4f; }

.form-group input,
.form-group select,
.form-group textarea {
  padding: 8px 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 14px;
  font-family: inherit;
  transition: border-color 0.2s;
}

.form-group input:focus,
.form-group select:focus,
.form-group textarea:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.15);
}

.form-group textarea { resize: vertical; }

.btn {
  padding: 8px 18px;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  font-weight: 500;
  transition: opacity 0.2s;
}

.btn:hover { opacity: 0.85; }
.btn-primary { background: #667eea; color: white; }
.btn-outline { background: white; color: #595959; border: 1px solid #d9d9d9; }
</style>
