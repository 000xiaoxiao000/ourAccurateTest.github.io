<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useTrafficStore } from './stores/traffic'
import TrafficTable from './components/TrafficTable.vue'
import DetailModal from './components/DetailModal.vue'
import FilterRulesPanel from './components/FilterRulesPanel.vue'
import MqInputModal from './components/MqInputModal.vue'
import PluginPanel from './components/PluginPanel.vue'
import CoveragePanel from './components/CoveragePanel.vue'
import ProxyControl from './components/ProxyControl.vue'
import SessionHistory from './components/SessionHistory.vue'
import TrafficStatsPanel from './components/TrafficStatsPanel.vue'
import type { TrafficRecord } from './types/traffic'
import type { RuntimeLogEntry } from './types/electron'
import type { ProbeSummary } from './types/probe'

const store = useTrafficStore()
const showDetail = ref(false)
const showMqInput = ref(false)
const showRuntimeLogs = ref(false)
const selectedRecord = ref<TrafficRecord | null>(null)
const proxyInfoVisible = ref(false)
const selectedRecordIds = ref<string[]>([])
const activePanel = ref<'none' | 'stats' | 'rules' | 'plugins'>('none')
const activeSection = ref<'capture' | 'requests' | 'sessions' | 'coverage' | 'settings'>('capture')
const pluginsPath = ref('')
const proxyPort = ref(8888)
const runtimeLogs = ref<RuntimeLogEntry[]>([])
const logLevelFilter = ref<'all' | 'debug' | 'info' | 'warn' | 'error'>('all')
const filteredRuntimeLogs = computed(() =>
  logLevelFilter.value === 'all' ? runtimeLogs.value : runtimeLogs.value.filter((e) => e.level === logLevelFilter.value)
)
const runtimeLogAutoScroll = ref(true)
const runtimeLogBody = ref<HTMLElement | null>(null)
const isFloatingMode = new URLSearchParams(window.location.search).get('floating') === '1'
const currentSectionTitle = computed(() => {
  const titles = {
    capture: '采集工作台',
    requests: '请求记录',
    sessions: '会话管理',
    coverage: '覆盖率分析',
    settings: '系统设置'
  }
  return titles[activeSection.value]
})
const currentSectionSubtitle = computed(() => {
  const subtitles = {
    capture: '实时捕获并观察请求、状态和响应详情',
    requests: '按用例、协议、状态检索历史请求并执行重放或导出',
    sessions: '加载、删除和复用历史采集会话',
    coverage: '多语言覆盖工具（前端 + 后端）：采集数据 → 原生报告 → 覆盖率视图',
    settings: '管理系统代理、证书、协议、过滤规则和插件'
  }
  return subtitles[activeSection.value]
})
const detailRecord = computed(() => selectedRecord.value ?? store.filteredRecords[0] ?? null)
let floatingClickTimer: number | null = null
let unsubscribeRuntimeLog: (() => void) | undefined
let unsubscribeRuntimeLogClear: (() => void) | undefined
let unsubscribeProbeHeartbeat: (() => void) | undefined

// ===== 在线探针：全局可见的状态（左导航角标 + 底部状态行）=====
// 探针不是业务流程的一个阶段，它是「采集能否成立」的前置条件，所以不作为第 6 个一级导航，
// 而是做成全局可见的状态提示 + 一行直达入口。
const probeSummary = ref<ProbeSummary | null>(null)
const coverageSubTab = ref<'probes' | 'tools' | 'impact'>('tools')
/** 角标：没登记过探针时不打扰新用户（total=0 → 不显示）。语义：握手成功就算在线（含未采集的） */
const probeBadge = computed(() => {
  const s = probeSummary.value
  if (!s || !s.total) return null
  const alive = s.online + s.warning
  if (alive > 0) return { cls: 'ok', text: String(alive) }
  return { cls: 'off', text: String(s.offline + s.vanilla) }
})
const probeBadgeTitle = computed(() => {
  const s = probeSummary.value
  if (!s) return ''
  const alive = s.online + s.warning
  if (alive > 0) {
    return s.warning > 0
      ? `${alive} 个探针在线（其中 ${s.warning} 个还没采到归属 Key：发一次请求，并确认覆盖率采集开关已打开）`
      : `${alive} 个探针在线，已采到 Key`
  }
  return '登记的探针全部离线'
})
function goProbes() {
  coverageSubTab.value = 'probes'
  activeSection.value = 'coverage'
}

onMounted(() => {
  window.electronAPI?.onTrafficCaptured((record: TrafficRecord) => {
    store.addRecord(record)
  })
  window.electronAPI?.onCaptureStateChanged((state) => {
    store.syncCaptureState(state)
  })
  window.electronAPI?.getCaptureState().then((state) => {
    if (state) store.syncCaptureState(state)
  })
  store.loadFilterRules()
  store.loadPlugins()
  store.loadCoverageConfig()
  window.electronAPI?.getPluginsPath().then((path) => {
    pluginsPath.value = path ?? ''
  })
  window.electronAPI?.getRuntimeLogs().then((logs) => {
    runtimeLogs.value = logs ?? []
  })
  unsubscribeRuntimeLog = window.electronAPI?.onRuntimeLogAppended((entry) => {
    runtimeLogs.value = [...runtimeLogs.value, entry].slice(-1000)
  })
  unsubscribeRuntimeLogClear = window.electronAPI?.onRuntimeLogsCleared(() => {
    runtimeLogs.value = []
  })
  // 探针状态由主进程 20s 心跳推送（不在这里发起探测，避免与面板重复握手同一个 agent）
  unsubscribeProbeHeartbeat = window.electronAPI?.onCoverageProbeHeartbeat((p) => {
    if (p?.summary) probeSummary.value = p.summary
  })
  window.electronAPI?.coverageProbeLast().then((r) => {
    if (r?.summary) probeSummary.value = r.summary
  })
})

watch([runtimeLogs, showRuntimeLogs], () => {
  if (!showRuntimeLogs.value || !runtimeLogAutoScroll.value) return
  nextTick(() => {
    const body = runtimeLogBody.value
    if (body) {
      body.scrollTop = body.scrollHeight
    }
  })
})

watch(() => store.proxyPort, (port) => {
  proxyPort.value = port
}, { immediate: true })

onUnmounted(() => {
  unsubscribeRuntimeLog?.()
  unsubscribeRuntimeLogClear?.()
  unsubscribeProbeHeartbeat?.()
})

async function generateCoverageKey() {
  store.saveCoverageConfig({ key: (crypto.randomUUID ? crypto.randomUUID().slice(0, 8) : Math.random().toString(36).slice(2, 10)) })
}

async function toggleCapture() {
  if (store.isCapturing) {
    await store.stopCapture()
  } else {
    if (!store.currentCaseName.trim()) {
      alert('请先输入用例名称')
      return
    }
    const result = await store.startCapture(store.currentCaseName)
    if (result?.success) {
      proxyInfoVisible.value = true
      setTimeout(() => { proxyInfoVisible.value = false }, 6000)
    } else {
      alert(`启动代理失败: ${result?.error ?? '主进程未返回错误信息，请查看终端日志'}`)
    }
  }
}

function handleViewDetail(record: TrafficRecord) {
  selectedRecord.value = record
  showDetail.value = true
}

function handleDelete(id: string) {
  if (confirm('确定删除这条记录？')) {
    store.deleteRecord(id)
  }
}

function handleClear() {
  if (store.records.length === 0) return
  if (confirm('确定清空所有记录？')) {
    store.clearRecords()
  }
}

async function exportData(format: 'excel' | 'csv' | 'json') {
  const selectedIds = new Set(selectedRecordIds.value)
  const records = selectedIds.size > 0
    ? store.filteredRecords.filter(record => selectedIds.has(record.id))
    : store.filteredRecords

  if (records.length === 0) {
    alert('没有可导出的记录')
    return
  }

  try {
    const exportRecords = JSON.parse(JSON.stringify(records)) as TrafficRecord[]
    const result = await window.electronAPI?.exportRecords(format, exportRecords)
    if (result?.success) {
      alert(`成功导出 ${exportRecords.length} 条数据：${result.filePath}`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    alert(`导出失败：${message}`)
  }
}

function addMqRecord(record: TrafficRecord) {
  record.caseName = store.currentCaseName || '未命名'
  store.addRecord(record)
}

function handleLoadSession(records: TrafficRecord[]) {
  store.replaceRecords([...records].reverse())
}

function toggleStatusFilter(filter: 'success' | 'failed') {
  store.setStatusFilter(store.statusFilter === filter ? 'all' : filter)
}

function formatRuntimeLogTime(timestamp: number) {
  const time = new Date(timestamp)
  const ms = String(time.getMilliseconds()).padStart(3, '0')
  return `${time.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })}.${ms}`
}

async function openRuntimeLogs() {
  runtimeLogs.value = await window.electronAPI?.getRuntimeLogs() ?? []
  showRuntimeLogs.value = true
}

async function clearRuntimeLogs() {
  await window.electronAPI?.clearRuntimeLogs()
  runtimeLogs.value = []
}

async function saveProxyPortConfig() {
  const nextProxyPort = Number(proxyPort.value)
  if (!Number.isInteger(nextProxyPort) || nextProxyPort <= 0 || nextProxyPort > 65535) {
    alert('系统代理端口必须是 1-65535 的整数')
    return
  }
  await store.saveProxyPort(nextProxyPort)
  proxyPort.value = store.proxyPort
}

async function replayRecord(record: TrafficRecord) {
  const result = await store.replay(record)
  if (!result?.success && result?.error) {
    alert(`重放失败：${result.error}`)
  }
}

async function replaySelected() {
  if (selectedRecordIds.value.length === 0) {
    alert('请先选择要重放的记录')
    return
  }
  const results = await store.replayMany(selectedRecordIds.value)
  const failed = results?.filter(result => !result.success).length ?? 0
  if (failed > 0) {
    alert(`重放完成，其中 ${failed} 条失败或不支持`)
  }
}

function togglePanel(panel: 'stats' | 'rules' | 'plugins') {
  activePanel.value = activePanel.value === panel ? 'none' : panel
}

function selectSection(section: typeof activeSection.value) {
  activeSection.value = section
  activePanel.value = 'none'
}

async function openPluginsFolder() {
  await window.electronAPI?.openPluginsFolder()
}

async function uninstallPlugin(pluginId: string) {
  await store.uninstallPlugin(pluginId)
}

async function showFloatingWindow() {
  await window.electronAPI?.showFloatingWindow()
}

async function restoreMainWindow() {
  if (floatingClickTimer) {
    window.clearTimeout(floatingClickTimer)
    floatingClickTimer = null
  }
  await window.electronAPI?.restoreMainWindow()
}

function handleFloatingClick() {
  if (floatingClickTimer) return
  floatingClickTimer = window.setTimeout(() => {
    floatingClickTimer = null
    toggleCapture()
  }, 220)
}
</script>

<template>
  <div v-if="isFloatingMode" class="floating-capture">
    <input
      v-model="store.currentCaseName"
      class="floating-case-input"
      type="text"
      placeholder="用例名称 / 流量描述"
      :disabled="store.isCapturing"
      @dblclick.stop
    />
    <div class="floating-stats" :class="{ active: store.isCapturing }">
      <span class="floating-status-dot"></span>
      <span>{{ store.isCapturing ? '捕获中' : '未捕获' }}</span>
      <strong>{{ store.capturedCount }}</strong>
      <span>条流量</span>
    </div>
    <button
      class="floating-capture-button"
      :class="{ active: store.isCapturing }"
      type="button"
      title="单击开始/停止，双击恢复主窗口"
      @click.stop="handleFloatingClick"
      @dblclick.stop="restoreMainWindow"
    >
      <span class="floating-dot"></span>
      {{ store.isCapturing ? '停止捕获' : '开始捕获' }}
    </button>
    <button class="floating-restore-button" type="button" @click="restoreMainWindow">
      恢复
    </button>
  </div>

  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">oA</span>
        <div>
          <strong>oAT 采集器</strong>
          <small>Traffic Capture</small>
        </div>
      </div>
      <nav class="nav" aria-label="主导航">
        <button type="button" :class="{ active: activeSection === 'capture' }" @click="selectSection('capture')">采集工作台</button>
        <button type="button" :class="{ active: activeSection === 'requests' }" @click="selectSection('requests')">请求记录</button>
        <button type="button" :class="{ active: activeSection === 'sessions' }" @click="selectSection('sessions')">会话管理</button>
        <button type="button" :class="{ active: activeSection === 'coverage' }" @click="selectSection('coverage')">
          <span class="nav-label">覆盖率分析</span>
          <em v-if="probeBadge" class="nav-badge" :class="'nb-' + probeBadge.cls" :title="probeBadgeTitle">{{ probeBadge.text }}</em>
        </button>
        <button type="button" :class="{ active: activeSection === 'settings' }" @click="selectSection('settings')">系统设置</button>
      </nav>
      <div class="sidebar-status">
        <span class="status-dot" :class="{ active: store.isCapturing }"></span>
        <span>{{ store.isCapturing ? '捕获中' : '未捕获' }}</span>
        <strong>{{ store.capturedCount }}</strong>
        <span>条流量</span>
      </div>
      <!-- 探针状态：和上面的「捕获中」是同一类信息（一个工具的运行状态），全局可见但不抢导航位置 -->
      <div v-if="probeSummary?.total" class="sidebar-probe">
        <span class="status-dot" :class="{ 'p-ok': (probeSummary?.online ?? 0) + (probeSummary?.warning ?? 0) > 0 }"></span>
        <span>探针 {{ (probeSummary?.online ?? 0) + (probeSummary?.warning ?? 0) }} 在线</span>
        <strong v-if="(probeSummary?.warning ?? 0) > 0" class="sp-warn" title="探针在线但还没采到归属 Key：发一次请求并确认覆盖率采集开关已打开">{{ probeSummary?.warning }} 未采集</strong>
        <strong v-if="(probeSummary?.offline ?? 0) + (probeSummary?.vanilla ?? 0) > 0" class="sp-off">{{ (probeSummary?.offline ?? 0) + (probeSummary?.vanilla ?? 0) }} 离线</strong>
        <button class="sp-jump" type="button" @click="goProbes">查看 →</button>
      </div>
    </aside>

    <main class="main-shell">
      <header class="topbar">
        <div>
          <h1>{{ currentSectionTitle }}</h1>
          <p>{{ currentSectionSubtitle }}</p>
        </div>
        <div class="topbar-actions">
          <span class="status-pill">代理 127.0.0.1:{{ store.proxyPort }}</span>
          <span class="status-pill">{{ store.isCapturing ? '正在捕获' : '等待捕获' }}</span>
          <button class="btn btn-outline" type="button" @click="selectSection('settings')">设置</button>
          <button
            class="btn"
            :class="store.isCapturing ? 'btn-danger' : 'btn-success'"
            type="button"
            @click="toggleCapture"
          >
            {{ store.isCapturing ? '停止捕获' : '开始捕获' }}
          </button>
        </div>
      </header>

      <section v-if="activeSection === 'capture'" class="page-content">
        <div class="control-card">
          <div class="control-row">
            <label class="input-group" for="caseName">
              <span>用例名称 / 流量描述</span>
              <input
                id="caseName"
                v-model="store.currentCaseName"
                type="text"
                placeholder="例如：用户登录流程、订单创建接口测试..."
                :disabled="store.isCapturing"
              />
            </label>
            <div class="btn-group">
              <button class="btn btn-outline" type="button" @click="showFloatingWindow">悬浮最小化</button>
              <button class="btn btn-outline" type="button" @click="showMqInput = true">手动录入 MQ</button>
              <button class="btn btn-outline" type="button" @click="handleClear">清空记录</button>
            </div>
          </div>
          <div v-if="proxyInfoVisible" class="proxy-tip">
            代理已启动在端口 <strong>{{ store.proxyPort }}</strong>，请配置 HTTP 代理为 <strong>127.0.0.1:{{ store.proxyPort }}</strong>
          </div>
        </div>

        <!-- 覆盖率采集配置：与代理同生命周期，开启代理即开启采集 -->
        <div class="card">
          <div class="card-head cov-head">
            <h3>覆盖率采集 <span class="tag tag-blue">与代理同生命周期</span></h3>
            <label class="cov-toggle" @click.prevent="store.saveCoverageConfig({ enabled: !store.coverageConfig.enabled })">
              <span class="switch" :class="{ on: store.coverageConfig.enabled }"></span>
              <b>{{ store.coverageConfig.enabled ? '已开启：代理注入 ' + store.coverageConfig.headerName : '已关闭：代理不注入请求头' }}</b>
            </label>
            <span class="muted">开启代理 = 开启采集；探针适应被测系统，不改动业务代码</span>
          </div>
          <div class="control-row" :class="{ 'cov-off': !store.coverageConfig.enabled }" style="margin-bottom:14px">
            <label class="input-group"><span>X-Coverage-Key（本用例归属标识）</span>
              <div style="display:flex;gap:8px">
                <input :value="store.coverageConfig.key" placeholder="uuid 或拼音/英文用户名" style="min-width:220px" @input="store.saveCoverageConfig({ key: ($event.target as HTMLInputElement).value })" />
                <button class="btn btn-outline btn-sm" @click="generateCoverageKey">自动生成</button>
              </div>
            </label>
            <label class="input-group"><span>请求头名（可配置）</span><input :value="store.coverageConfig.headerName" style="min-width:160px" @input="store.saveCoverageConfig({ headerName: ($event.target as HTMLInputElement).value })" /></label>
          </div>
          <p class="muted" style="margin:6px 0 0;font-size:12px" v-if="store.coverageConfig.enabled">
            只有经过 oAT 代理（127.0.0.1:{{ store.proxyPort }}）的请求才会注入 {{ store.coverageConfig.headerName }}；
            直连被测系统的请求（如 xxl-job 执行器心跳/回调、未走代理的访问）不携带该头，探针会丢弃（属正常现象）。
            Agent 挂载参数与远程 dump 见「覆盖率分析」页。
          </p>
        </div>

        <div class="metric-grid">
          <button type="button" class="metric-card" :class="{ active: store.statusFilter === 'all' }" @click="store.setStatusFilter('all')">
            <span>总请求</span>
            <strong>{{ store.stats.total }}</strong>
          </button>
          <button type="button" class="metric-card" :class="{ active: store.statusFilter === 'success' }" @click="toggleStatusFilter('success')">
            <span>成功</span>
            <strong class="success">{{ store.stats.success }}</strong>
          </button>
          <button type="button" class="metric-card" :class="{ active: store.statusFilter === 'failed' }" @click="toggleStatusFilter('failed')">
            <span>失败</span>
            <strong class="danger">{{ store.stats.failed }}</strong>
          </button>
          <div class="metric-card">
            <span>平均耗时</span>
            <strong>{{ store.stats.avgDuration }}ms</strong>
          </div>
        </div>

        <div class="workspace-grid">
          <section class="panel table-panel">
            <div class="panel-toolbar">
              <div class="search-box">
                <input v-model="store.searchQuery" type="text" placeholder="搜索 URL、用例名称或方法..." />
              </div>
              <div class="btn-group">
                <button class="btn btn-outline" type="button" @click="togglePanel('stats')">统计图表</button>
                <button class="btn btn-outline" type="button" @click="togglePanel('rules')">过滤规则</button>
                <button class="btn btn-outline" type="button" @click="replaySelected">重放选中</button>
                <button class="btn btn-primary" type="button" @click="exportData('excel')">导出 Excel</button>
                <button class="btn btn-outline" type="button" @click="exportData('csv')">CSV</button>
                <button class="btn btn-outline" type="button" @click="exportData('json')">JSON</button>
              </div>
            </div>
            <TrafficStatsPanel v-if="activePanel === 'stats'" :stats="store.chartStats" />
            <FilterRulesPanel v-if="activePanel === 'rules'" :rules="store.filterRules" @save="store.saveRules" />
            <TrafficTable
              :records="store.filteredRecords"
              v-model:selected-ids="selectedRecordIds"
              @delete="handleDelete"
              @replay="replayRecord"
              @view-detail="handleViewDetail"
            />
          </section>

          <aside class="right-rail">
            <section class="panel side-panel">
              <div class="panel-header">
                <strong>请求详情</strong>
                <button v-if="detailRecord" class="link-btn" type="button" @click="handleViewDetail(detailRecord)">完整详情</button>
              </div>
              <div v-if="detailRecord" class="detail-stack">
                <div class="kv"><span>用例</span><strong>{{ detailRecord.caseName || '未命名' }}</strong></div>
                <div class="kv"><span>方法</span><strong>{{ detailRecord.method }}</strong></div>
                <div class="kv"><span>状态</span><strong>{{ detailRecord.statusCode }}</strong></div>
                <div class="kv"><span>耗时</span><strong>{{ detailRecord.duration }}ms</strong></div>
                <div class="kv wide"><span>URL</span><strong>{{ detailRecord.url }}</strong></div>
                <div class="code-preview">{{ detailRecord.responseBody || detailRecord.requestBody || '暂无请求或响应正文' }}</div>
              </div>
              <div v-else class="empty-side">暂无请求记录</div>
            </section>
          </aside>
        </div>
      </section>

      <section v-else-if="activeSection === 'requests'" class="page-content single-column">
        <section class="panel table-panel">
          <div class="panel-toolbar">
            <div class="search-box">
              <input v-model="store.searchQuery" type="text" placeholder="搜索 URL、用例名称或方法..." />
            </div>
            <div class="btn-group">
              <button class="btn btn-outline" type="button" @click="replaySelected">重放选中</button>
              <button class="btn btn-primary" type="button" @click="exportData('excel')">导出 Excel</button>
              <button class="btn btn-outline" type="button" @click="exportData('csv')">导出 CSV</button>
              <button class="btn btn-outline" type="button" @click="exportData('json')">导出 JSON</button>
            </div>
          </div>
          <TrafficTable
            :records="store.filteredRecords"
            v-model:selected-ids="selectedRecordIds"
            @delete="handleDelete"
            @replay="replayRecord"
            @view-detail="handleViewDetail"
          />
        </section>
      </section>

      <section v-else-if="activeSection === 'sessions'" class="page-content single-column">
        <SessionHistory @load="handleLoadSession" />
      </section>

      <section v-else-if="activeSection === 'coverage'" class="page-content single-column">
        <CoveragePanel v-model:sub-tab="coverageSubTab" />
      </section>

      <section v-else class="page-content single-column settings-content">
        <section class="panel settings-panel">
          <div class="panel-header">
            <strong>系统代理与证书</strong>
            <span>端口 {{ store.proxyPort }}</span>
          </div>
          <div class="settings-port-row">
            <label><span>系统代理端口</span><input v-model.number="proxyPort" type="number" min="1" max="65535" step="1" /></label>
            <button class="btn btn-primary" type="button" @click="saveProxyPortConfig">保存端口</button>
          </div>
          <ProxyControl :port="proxyPort" />
        </section>
        <section class="panel settings-panel">
          <div class="panel-header">
            <strong>规则与插件</strong>
            <div class="btn-group">
              <button class="btn btn-outline" type="button" @click="togglePanel('rules')">过滤规则</button>
              <button class="btn btn-outline" type="button" @click="togglePanel('plugins')">插件扩展</button>
            </div>
          </div>
          <FilterRulesPanel v-if="activePanel === 'rules'" :rules="store.filterRules" @save="store.saveRules" />
          <PluginPanel
            v-if="activePanel === 'plugins'"
            :plugins="store.plugins"
            :plugins-path="pluginsPath"
            @reload="store.reloadPlugins"
            @open-folder="openPluginsFolder"
            @install-builtin="store.installBuiltinPlugin"
            @uninstall-builtin="store.uninstallBuiltinPlugin"
            @uninstall-plugin="(plugin) => uninstallPlugin(plugin.id)"
          />
        </section>
        <div class="settings-log-row">
          <button class="btn btn-outline runtime-log-trigger" type="button" @click="openRuntimeLogs">查看运行日志</button>
        </div>
      </section>

      <DetailModal
        :record="selectedRecord"
        :show="showDetail"
        @close="showDetail = false"
      />

      <MqInputModal
        :show="showMqInput"
        @close="showMqInput = false"
        @add="addMqRecord"
      />

      <div v-if="showRuntimeLogs" class="runtime-log-overlay" @click.self="showRuntimeLogs = false">
        <section class="runtime-log-modal" role="dialog" aria-modal="true" aria-label="运行日志">
          <header class="runtime-log-header">
            <h2>运行日志</h2>
            <div class="runtime-log-actions">
              <select v-model="logLevelFilter" class="btn btn-outline runtime-log-filter" style="appearance:auto">
                <option value="all">全部级别</option>
                <option value="debug">DEBUG</option>
                <option value="info">INFO</option>
                <option value="warn">WARN</option>
                <option value="error">ERROR</option>
              </select>
              <button class="btn btn-outline" type="button" @click="clearRuntimeLogs">清空</button>
              <button class="btn btn-outline" type="button" @click="runtimeLogAutoScroll = !runtimeLogAutoScroll">
                {{ runtimeLogAutoScroll ? '暂停滚动' : '继续滚动' }}
              </button>
              <button class="runtime-log-close" type="button" aria-label="关闭运行日志" @click="showRuntimeLogs = false">×</button>
            </div>
          </header>
          <div ref="runtimeLogBody" class="runtime-log-body">
            <div v-if="runtimeLogs.length === 0" class="runtime-log-empty">暂无运行日志</div>
            <div v-if="runtimeLogs.length > 0 && filteredRuntimeLogs.length === 0" class="runtime-log-empty">该级别暂无日志</div>
            <div
              v-for="entry in filteredRuntimeLogs"
              :key="entry.id"
              class="runtime-log-line"
              :class="entry.level"
            >
              <span>[{{ formatRuntimeLogTime(entry.timestamp) }}]</span>
              <strong>{{ entry.level.toUpperCase() }}</strong>
              <code>{{ entry.text }}</code>
            </div>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
.floating-capture {
  width: 100vw;
  height: 100vh;
  padding: 12px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  -webkit-app-region: drag;
  user-select: none;
}

.floating-case-input {
  width: 100%;
  height: 34px;
  box-sizing: border-box;
  border: 1px solid #d9d9d9;
  border-radius: 5px;
  padding: 0 10px;
  font-size: 13px;
  color: #262626;
  outline: none;
  -webkit-app-region: no-drag;
}

.floating-case-input:focus {
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.14);
}

.floating-case-input:disabled {
  background: #f5f5f5;
  color: #8c8c8c;
}

.floating-stats {
  width: 100%;
  height: 24px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 5px;
  background: #f7f7f7;
  color: #595959;
  font-size: 12px;
  -webkit-app-region: no-drag;
}

.floating-stats.active {
  background: #fff1f0;
  color: #cf1322;
}

.floating-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #bfbfbf;
}

.floating-stats.active .floating-status-dot {
  background: #ff4d4f;
  animation: pulse 1.5s ease-in-out infinite;
}

.floating-stats strong {
  color: #262626;
  font-size: 13px;
}

.floating-capture-button {
  width: 100%;
  height: 50px;
  border: 0;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #52c41a;
  color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  -webkit-app-region: no-drag;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.16);
}

.floating-capture-button.active {
  background: #ff4d4f;
}

.floating-capture-button:hover {
  filter: brightness(0.96);
}

.floating-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.95;
}

.floating-capture-button.active .floating-dot {
  animation: pulse 1.5s ease-in-out infinite;
}

.floating-restore-button {
  width: 100%;
  height: 26px;
  border: 1px solid #d9d9d9;
  border-radius: 5px;
  background: #ffffff;
  color: #595959;
  font-size: 12px;
  cursor: pointer;
  -webkit-app-region: no-drag;
}

.floating-restore-button:hover {
  background: #f5f5f5;
}


.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 184px minmax(0, 1fr);
  background: #eef1f5;
  color: #1f2937;
}

.sidebar {
  background: #17202b;
  color: #e5edf6;
  padding: 18px 14px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 4px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.brand strong,
.brand small {
  display: block;
}

.brand small {
  margin-top: 2px;
  color: #94a3b8;
  font-size: 11px;
}

.brand-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #7dd3fc;
  color: #102030;
  font-weight: 800;
}

.nav {
  display: grid;
  gap: 6px;
}

.nav button {
  height: 36px;
  border: 0;
  border-radius: 6px;
  padding: 0 12px;
  background: transparent;
  color: #b8c4d2;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* 「覆盖率分析」上的探针角标：复用探针四态色（绿=正常 / 黄=在线但采不到 key / 灰=离线） */
.nav-badge {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  min-width: 18px;
  text-align: center;
  padding: 1px 6px;
  border-radius: 999px;
  line-height: 1.5;
}
.nav-badge.nb-ok { background: #22c55e; color: #06210f; }
.nav-badge.nb-warn { background: #f59e0b; color: #2b1a02; }
.nav-badge.nb-off { background: rgba(255, 255, 255, 0.16); color: #cbd5e1; }
.nav button.active .nav-badge.nb-ok { background: #ffffff; color: #15803d; }
.nav button.active .nav-badge.nb-warn { background: #ffffff; color: #b45309; }
.nav button.active .nav-badge.nb-off { background: rgba(255, 255, 255, 0.28); color: #ffffff; }

/* 底部探针状态行：一行直达，路径最短 */
.sidebar-probe {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #cbd5e1;
  font-size: 12px;
}
.sidebar-probe .status-dot.p-ok { background: #22c55e; }
.sidebar-probe .status-dot.p-warn { background: #f59e0b; }
.sidebar-probe .sp-warn { color: #fbbf24; }
.sidebar-probe .sp-off { color: #94a3b8; }
.sidebar-probe .sp-jump {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: #60a5fa;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.sidebar-probe .sp-jump:hover { color: #93c5fd; text-decoration: underline; }

.nav button:hover,
.nav button.active {
  color: #ffffff;
  background: #2563eb;
}

.sidebar-status {
  margin-top: auto;
  min-height: 40px;
  padding: 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  gap: 7px;
  color: #cbd5e1;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
}

.status-dot.active {
  background: #22c55e;
  animation: pulse 1.5s ease-in-out infinite;
}

.main-shell {
  min-width: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
}

.topbar {
  min-height: 64px;
  padding: 12px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #ffffff;
  border-bottom: 1px solid #dfe4ea;
}

.topbar h1 {
  margin: 0 0 4px;
  font-size: 18px;
  line-height: 1.2;
}

.topbar p {
  margin: 0;
  color: #64748b;
  font-size: 12px;
}

.topbar-actions,
.btn-group,
.control-row,
.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.status-pill {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  white-space: nowrap;
}

.btn {
  height: 30px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.btn:hover,
.link-btn:hover {
  opacity: 0.86;
}

.btn-success { background: #16a34a; color: #ffffff; }
.btn-danger { background: #dc2626; color: #ffffff; }
.btn-primary { background: #2563eb; color: #ffffff; }
.btn-outline {
  background: #ffffff;
  color: #334155;
  border-color: #cbd5e1;
}

.page-content {
  min-width: 0;
  min-height: 0;
  padding: 14px 18px 18px;
  display: grid;
  gap: 14px;
  align-content: start;
}

.single-column {
  grid-template-columns: minmax(0, 1fr);
}

.control-card,
.panel {
  background: #ffffff;
  border: 1px solid #dfe4ea;
  border-radius: 8px;
}

.control-card {
  padding: 12px;
}

.control-row {
  align-items: flex-end;
}

.input-group {
  flex: 1;
  min-width: 280px;
  display: grid;
  gap: 5px;
}

.input-group span {
  color: #475569;
  font-size: 12px;
  font-weight: 600;
}

.input-group input,
.search-box input {
  height: 32px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 0 10px;
  color: #1f2937;
  background: #ffffff;
}

.input-group input:focus,
.search-box input:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}

.input-group input:disabled {
  background: #f1f5f9;
  color: #64748b;
}

.proxy-tip {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 13px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(118px, 1fr));
  gap: 10px;
}

.metric-card {
  min-height: 64px;
  padding: 9px 10px;
  border: 1px solid #d8dee8;
  border-radius: 8px;
  background: #ffffff;
  text-align: left;
}

button.metric-card {
  cursor: pointer;
}

.metric-card.active {
  border-color: #93c5fd;
  background: #eff6ff;
}

.metric-card span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.metric-card strong {
  display: block;
  margin-top: 5px;
  color: #1f2937;
  font-size: 18px;
}

.success { color: #16a34a !important; }
.danger { color: #dc2626 !important; }

.workspace-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
}

.table-panel {
  min-width: 0;
  overflow: hidden;
}

.panel-toolbar,
.panel-header {
  min-height: 48px;
  padding: 10px 12px;
  border-bottom: 1px solid #e5eaf0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.search-box input {
  width: min(320px, 100%);
}

.right-rail {
  min-width: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
}

.side-panel {
  min-width: 0;
  overflow: hidden;
}

.kv span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.link-btn {
  border: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 12px;
}

.detail-stack {
  padding: 12px;
  display: grid;
  gap: 10px;
}

.kv {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 10px;
  font-size: 13px;
}

.kv strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kv.wide {
  grid-template-columns: 1fr;
  gap: 4px;
}

.code-preview {
  max-height: 180px;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: #101828;
  color: #d1e7ff;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.empty-side {
  padding: 24px 12px;
  color: #94a3b8;
  text-align: center;
  font-size: 13px;
}

.settings-panel {
  overflow: hidden;
}

.settings-panel :deep(.proxy-control) {
  margin: 0;
  padding: 12px;
  background: #ffffff;
}

.settings-port-row {
  padding: 12px 12px 0;
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.settings-port-row label {
  min-width: 180px;
  display: grid;
  gap: 5px;
}

.settings-port-row span {
  color: #64748b;
  font-size: 12px;
}

.settings-port-row input {
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 14px;
}

.settings-panel :deep(.label) {
  color: #334155;
}

.settings-panel :deep(.hint),
.settings-panel :deep(.protocol-option small) {
  color: #64748b;
}

.settings-content {
  grid-template-rows: auto auto minmax(0, 1fr);
}

.settings-log-row {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 18px 70px 36px 12px;
}

.runtime-log-trigger {
  position: static;
  flex: 0 0 auto;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.28);
}

.settings-panel :deep(.toggle-btn),
.settings-panel :deep(.mini-btn),
.settings-panel :deep(.protocol-option) {
  border-color: #cbd5e1;
  background: #ffffff;
  color: #334155;
}

.settings-panel :deep(.toggle-btn.active) {
  background: #f0fdf4;
  border-color: #86efac;
  color: #166534;
}

.runtime-log-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 26px;
  background: rgba(15, 23, 42, 0.24);
}

.runtime-log-modal {
  width: min(1180px, calc(100vw - 52px));
  height: min(760px, calc(100vh - 52px));
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 18px;
  padding: 26px;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.24);
}

.runtime-log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.runtime-log-header h2 {
  margin: 0;
  color: #111827;
  font-size: 24px;
  line-height: 1.2;
}

.runtime-log-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.runtime-log-close {
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: #475569;
  cursor: pointer;
  font-size: 34px;
  line-height: 26px;
}

.runtime-log-body {
  min-height: 0;
  overflow: auto;
  padding: 12px 14px;
  border-radius: 8px;
  background: #1f1f1f;
  color: #d4d4d4;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.65;
}

.runtime-log-empty {
  color: #9ca3af;
}

.runtime-log-line {
  display: grid;
  grid-template-columns: 106px 58px minmax(0, 1fr);
  gap: 8px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.runtime-log-line span,
.runtime-log-line strong {
  color: #cbd5e1;
}

.runtime-log-line code {
  color: inherit;
  font-family: inherit;
}

.runtime-log-line.warn code,
.runtime-log-line.warn strong {
  color: #facc15;
}

.runtime-log-line.error code,
.runtime-log-line.error strong {
  color: #f87171;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@media (max-width: 1180px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(118px, 1fr));
  }

  .workspace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .topbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(118px, 1fr));
  }

  .settings-log-row {
    min-height: 96px;
    align-items: flex-start;
    justify-content: flex-end;
    padding: 12px 0 0;
  }

  .runtime-log-overlay {
    padding: 12px;
  }

  .runtime-log-modal {
    width: calc(100vw - 24px);
    height: calc(100vh - 24px);
    padding: 18px;
  }

  .runtime-log-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .runtime-log-line {
    grid-template-columns: 1fr;
    gap: 0;
  }
}


/* 覆盖率采集开关（App.vue 之前缺少 .switch 样式，开关从未渲染） */
.cov-head { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.cov-head .muted { flex: 1; min-width: 200px; text-align: right; }
.cov-toggle { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font-size: 13px; color: #33404d; white-space: nowrap; }
.cov-toggle b { font-weight: 600; }
.cov-toggle .switch { position: relative; display: inline-block; width: 40px; height: 22px; border-radius: 11px; background: #cbd5e1; transition: background .2s; flex-shrink: 0; cursor: pointer; }
.cov-toggle .switch::after { content: ''; position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: #fff; transition: left .2s; box-shadow: 0 1px 3px rgba(0, 0, 0, .2); }
.cov-toggle .switch.on { background: #16a34a; }
.cov-toggle .switch.on::after { left: 21px; }
.cov-off { opacity: .55; }
</style>
