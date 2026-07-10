<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { CaptureProtocolConfig } from '../types/electron'

const props = defineProps<{ port: number }>()

const isElectronWindow = ref(false)
const systemProxyEnabled = ref(false)
const loading = ref(false)
const certLoading = ref(false)
const certInfo = ref<{ exists: boolean; certPath?: string; expiresAt?: string }>({ exists: false })
const protocols = ref<CaptureProtocolConfig>({
  http: true,
  https: true,
  ws: true,
  wss: true
})
const fallbackError = '主进程未返回错误信息，请查看终端日志'
const missingElectronApiError = '当前窗口未连接 Electron 主进程。请关闭旧窗口，使用 npm run dev 启动的桌面窗口，不要在浏览器里打开 localhost 页面。'
const protocolOptions: Array<{ key: keyof CaptureProtocolConfig; label: string; note: string }> = [
  { key: 'http', label: 'HTTP', note: '普通接口流量' },
  { key: 'https', label: 'HTTPS', note: '加密接口流量' },
  { key: 'ws', label: 'WS', note: 'WebSocket 明文' },
  { key: 'wss', label: 'WSS', note: 'WebSocket 加密' }
]

const selectedProtocolLabels = computed(() => protocolOptions
  .filter(option => protocols.value[option.key])
  .map(option => option.label)
  .join(' / '))

onMounted(async () => {
  isElectronWindow.value = Boolean(window.electronAPI)
  if (!window.electronAPI) return

  const status = await window.electronAPI.getProxyStatus()
  systemProxyEnabled.value = status?.enabled ?? false
  if (status?.protocols) {
    protocols.value = { ...protocols.value, ...status.protocols }
  }
  await refreshCertInfo()
})

async function refreshCertInfo() {
  if (!window.electronAPI) return
  certInfo.value = await window.electronAPI.getCertInfo()
}

function hasSelectedProtocol() {
  return Object.values(protocols.value).some(Boolean)
}

async function applySystemProxy() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }
  if (!hasSelectedProtocol()) {
    alert('请至少选择一种要代理和捕获的协议')
    return
  }

  loading.value = true
  try {
    const selectedProtocols: CaptureProtocolConfig = {
      http: protocols.value.http,
      https: protocols.value.https,
      ws: protocols.value.ws,
      wss: protocols.value.wss
    }
    const result = await api.enableSystemProxy(props.port, selectedProtocols)
    if (result?.success) {
      systemProxyEnabled.value = true
    } else {
      alert(`应用系统代理失败（可能需要管理员权限）：${result?.error ?? fallbackError}`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    alert(`系统代理操作失败：${message || fallbackError}`)
  } finally {
    loading.value = false
  }
}

async function disableSystemProxy() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }

  loading.value = true
  try {
    const result = await api.disableSystemProxy()
    if (result?.success) {
      systemProxyEnabled.value = false
    } else {
      alert(`关闭系统代理失败：${result?.error ?? fallbackError}`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    alert(`系统代理操作失败：${message || fallbackError}`)
  } finally {
    loading.value = false
  }
}

async function generateCert() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }

  certLoading.value = true
  try {
    const result = await api.generateCert()
    if (!result?.success) {
      alert(`生成证书失败：${result?.error ?? fallbackError}`)
      return
    }
    await refreshCertInfo()
    alert(`证书已生成：${result.certPath}`)
  } finally {
    certLoading.value = false
  }
}

async function installCert() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }

  certLoading.value = true
  try {
    if (!certInfo.value.exists) {
      const generated = await api.generateCert()
      if (!generated?.success) {
        alert(`生成证书失败：${generated?.error ?? fallbackError}`)
        return
      }
      await refreshCertInfo()
    }
    const result = await api.installCert()
    if (result?.success) {
      alert('证书已安装到系统信任列表。请重启浏览器或被测客户端。')
    } else {
      alert(`安装证书失败：${result?.error ?? fallbackError}`)
    }
  } finally {
    certLoading.value = false
  }
}

async function uninstallCert() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }
  if (!confirm('确定从系统钥匙串中卸载 oAT 根证书？卸载后 HTTPS/WSS 捕获将失效。')) return

  certLoading.value = true
  try {
    const result = await api.uninstallCert()
    if (result?.success) {
      alert('证书信任已卸载。必要时请重启浏览器或被测客户端。')
    } else {
      alert(`卸载证书失败：${result?.error ?? fallbackError}`)
    }
  } finally {
    certLoading.value = false
  }
}

async function openCertFolder() {
  const api = window.electronAPI
  if (!api) {
    alert(missingElectronApiError)
    return
  }
  if (!certInfo.value.exists) {
    await generateCert()
  }
  await api.openCertFolder()
}
</script>

<template>
  <div class="proxy-control">
    <span class="label">系统代理</span>
    <button
      class="toggle-btn"
      :class="{ active: systemProxyEnabled, loading, unavailable: !isElectronWindow }"
      :disabled="loading || !isElectronWindow"
      @click="applySystemProxy"
    >
      <span class="dot"></span>
      {{
        !isElectronWindow
          ? '浏览器预览'
          : loading
            ? '处理中...'
            : systemProxyEnabled
              ? `更新代理 (127.0.0.1:${props.port})`
              : '启用代理'
      }}
    </button>
    <button
      class="mini-btn"
      :disabled="loading || !isElectronWindow || !systemProxyEnabled"
      @click="disableSystemProxy"
    >
      关闭代理
    </button>
    <span class="hint">
      {{ isElectronWindow ? `当前选择：${selectedProtocolLabels || '未选择'}` : '系统代理只能在 Electron 桌面窗口中启用' }}
    </span>
    <div class="protocol-actions">
      <label v-for="option in protocolOptions" :key="option.key" class="protocol-option">
        <input v-model="protocols[option.key]" type="checkbox" :disabled="loading || !isElectronWindow" />
        <span>
          <strong>{{ option.label }}</strong>
          <small>{{ option.note }}</small>
        </span>
      </label>
      <span class="hint protocol-hint">HTTP/WS 使用系统 Web 代理，HTTPS/WSS 使用系统 Secure Web 代理。</span>
    </div>
    <div class="cert-actions">
      <span class="label">HTTPS 证书</span>
      <button class="mini-btn" :disabled="certLoading || !isElectronWindow" @click="generateCert">
        {{ certInfo.exists ? '重新生成' : '生成证书' }}
      </button>
      <button class="mini-btn" :disabled="certLoading || !isElectronWindow" @click="installCert">安装信任</button>
      <button class="mini-btn" :disabled="certLoading || !isElectronWindow" @click="uninstallCert">卸载信任</button>
      <button class="mini-btn" :disabled="certLoading || !isElectronWindow" @click="openCertFolder">打开目录</button>
      <span class="hint">
        {{ certInfo.exists ? `已生成，有效期至 ${certInfo.expiresAt}` : '未生成' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.proxy-control {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.92);
}

.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 14px;
  border: 1px solid rgba(255, 255, 255, 0.45);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.12);
  color: white;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
}

.toggle-btn.active {
  background: #f6ffed;
  border-color: #52c41a;
  color: #237804;
}

.toggle-btn.loading {
  opacity: 0.65;
  cursor: not-allowed;
}

.toggle-btn.unavailable {
  opacity: 0.7;
  cursor: not-allowed;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.65);
}

.toggle-btn.active .dot {
  background: #52c41a;
}

.hint {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.72);
}

.cert-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}

.protocol-actions {
  display: flex;
  align-items: stretch;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}

.protocol-option {
  min-width: 118px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  color: white;
  cursor: pointer;
}

.protocol-option input {
  width: 14px;
  height: 14px;
}

.protocol-option span {
  display: grid;
  gap: 2px;
}

.protocol-option strong {
  font-size: 12px;
  line-height: 1.1;
}

.protocol-option small {
  color: rgba(255, 255, 255, 0.7);
  font-size: 11px;
}

.protocol-hint {
  align-self: center;
}

.mini-btn {
  height: 26px;
  padding: 0 10px;
  border: 1px solid rgba(255, 255, 255, 0.45);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.12);
  color: white;
  font-size: 12px;
  cursor: pointer;
}

.mini-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.2);
}

.mini-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
