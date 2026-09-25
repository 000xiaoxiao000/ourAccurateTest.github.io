<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ProbeResult, ProbeSummary } from '../types/probe'
import { useTrafficStore } from '../stores/traffic'

const store = useTrafficStore()
/** 扫描发现确认是探针的地址，自动进登记簿（持久化）；默认开 */
const autoReg = computed(() => store.coverageConfig.probeAutoRegister !== false)
function setAutoReg(v: boolean) {
  store.saveCoverageConfig({ probeAutoRegister: v })
}

// ===== 地址脱敏：默认隐藏 IPv4 的后两段（截图 / 演示不暴露被测机 IP），需要时点工具条的「显示完整地址」 =====
const revealAddr = ref(false)
function maskAddr(addr: string): string {
  const raw = String(addr ?? '').trim()
  if (revealAddr.value || !raw) return raw
  const i = raw.lastIndexOf(':')
  if (i <= 0) return raw
  const host = raw.slice(0, i)
  const port = raw.slice(i)
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    const o = host.split('.')
    return `${o[0]}.${o[1]}.*.*${port}`
  }
  return host // 域名本身不涉及本机网络拓扑，原样显示
}
/** 卡片主标题：没备注名时引擎回落成 host:port —— 展示时同样脱敏 */
function displayLabel(p: ProbeResult): string {
  return p.label === p.agentAddress ? maskAddr(p.agentAddress) : p.label
}

const props = defineProps<{ agentAddress: string }>()
const emit = defineEmits<{ (e: 'use-address', address: string): void; (e: 'clear-address'): void }>()

const probes = ref<ProbeResult[]>([])
const summary = ref<ProbeSummary | null>(null)
const msg = ref('')
const busy = ref(false)
const autoRefresh = ref<0 | 10000 | 30000>(0)
const addOpen = ref(false)
const newHost = ref('')
const newPort = ref('8899')
const newLabel = ref('')
/** 展开「设置 Key」表单的卡片 id */
const keyCard = ref('')
const keyInput = ref('')
/** 展开「编辑」表单的卡片 id（仅登记项可编辑） */
const editCard = ref('')
const editHost = ref('')
const editPort = ref('')
const editLabel = ref('')
/** 展开「完整诊断」的卡片 id → 文本 */
const diagCard = ref('')
const diagText = ref('')

// ===== 远端 / 网段发现 =====
const rangeOpen = ref(false)
const rangeHosts = ref('')
const rangePorts = ref('6300-6310,8899')
const rangeBusy = ref(false)
const rangeProgress = ref('')
const rangeHits = ref<ProbeResult[]>([])
const rangeSummary = ref('')
// ===== 列表搜索 / 筛选 + 分页（登记几十上百个时全量渲染会卡） =====
const keyword = ref('')
const statusFilter = ref<'all' | 'online' | 'warning' | 'vanilla' | 'offline'>('all')
const page = ref(1)
const pageSize = ref(20)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return probes.value.filter((p) => {
    if (statusFilter.value !== 'all' && p.status !== statusFilter.value) return false
    if (!kw) return true
    // 匹配备注名 / 完整地址 / 来源标签：地址按脱敏后的显示值比对，用户看到什么就搜得到什么
    return [p.label, maskAddr(p.agentAddress), p.agentAddress, sourceText(p.source)].join(' ').toLowerCase().includes(kw)
  })
})
const totalPages = computed(() => (pageSize.value <= 0 ? 1 : Math.max(1, Math.ceil(filtered.value.length / pageSize.value))))
const paged = computed(() => (pageSize.value <= 0 ? filtered.value : filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)))

// 筛选后条数变少 / 心跳刷新后，页码要夹回合法范围，否则停在空白页
watch([() => filtered.value.length, totalPages], () => {
  if (page.value > totalPages.value) page.value = totalPages.value
  if (page.value < 1) page.value = 1
})

/** 从已抓流量反查出来的后端主机候选 */
const trafficHosts = ref<Array<{ host: string; ports: number[]; count: number }>>([])
const trafficHostsOpen = ref(false)

let offProgress: (() => void) | undefined

function applyTrafficHost(h: { host: string; ports: number[] }) {
  const list = rangeHosts.value.split(/[,，;\s]+/).filter(Boolean)
  if (!list.includes(h.host)) list.push(h.host)
  rangeHosts.value = list.join(', ')
  rangeOpen.value = true
  msg.value = `已把 ${h.host} 填入待扫描主机（端口另填，流量的 ${h.ports.join('/') || '未知'} 是业务端口，不是探针端口）`
}

async function loadTrafficHosts() {
  const r = await window.electronAPI?.coverageProbeTrafficHosts()
  if (!r?.success) {
    msg.value = '读取流量主机失败：' + (r?.error ?? '')
    return
  }
  trafficHosts.value = r.hosts ?? []
  trafficHostsOpen.value = true
  if (!trafficHosts.value.length) msg.value = '流量库里还没有记录（先抓一次包），或记录里的地址都是域名无法解析'
}

async function scanRange() {
  if (rangeBusy.value) return
  rangeBusy.value = true
  rangeProgress.value = '准备中…'
  rangeHits.value = []
  try {
    const r = await window.electronAPI?.coverageProbeScanRange({
      hosts: rangeHosts.value,
      ports: rangePorts.value,
      timeoutMs: 1200
    })
    if (!r?.success) {
      msg.value = '扫描失败：' + (r?.error ?? '未知原因')
      rangeProgress.value = ''
      return
    }
    if (r.error) {
      msg.value = r.error
      rangeProgress.value = ''
      return
    }
    rangeHits.value = r.probes ?? []
    const auto = r.autoRegistered ?? 0
    rangeSummary.value = `扫了 ${r.scanned} 个地址，命中 ${r.hits} 个探针${auto > 0 ? `，已自动登记 ${auto} 个` : ''}${r.truncated ? '（目标过多，只扫了前 1024 个，请缩小网段/端口范围）' : ''}`
    msg.value = r.hits
      ? `发现 ${r.hits} 个探针${auto > 0 ? `，已自动登记 ${auto} 个；其余为官方 JaCoCo 或需要你手动登记` : ''}`
      : '这段地址里没有探针：确认被测服务已挂 agent 且 tcpserver 端口能从本机访问（容器要 publish 端口）'
    if (r.hits) await refresh()
  } finally {
    rangeBusy.value = false
    rangeProgress.value = ''
  }
}

let timer: ReturnType<typeof setInterval> | undefined

async function refresh() {
  if (busy.value) return
  busy.value = true
  try {
    const r = await window.electronAPI?.coverageProbeList({ timeoutMs: 1800 })
    if (!r?.success) {
      msg.value = '探测失败：' + (r?.error ?? '未知原因')
      return
    }
    probes.value = r.probes ?? []
    summary.value = r.summary ?? null
  } finally {
    busy.value = false
  }
}

async function addProbe() {
  const addr = `${newHost.value.trim()}:${Number(newPort.value)}`
  const r = await window.electronAPI?.coverageProbeRegistry({ action: 'add', agentAddress: addr, label: newLabel.value.trim() || undefined })
  if (!r?.success) {
    msg.value = '登记失败：' + (r?.error ?? '')
    return
  }
  msg.value = '已登记 ' + maskAddr(addr) + '（会持久化，重启后仍在）'
  addOpen.value = false
  newHost.value = ''
  newLabel.value = ''
  await refresh()
}

async function removeProbe(p: ProbeResult) {
  const r = await window.electronAPI?.coverageProbeRegistry({ action: 'remove', id: registryId(p) })
  if (!r?.success) {
    msg.value = '取消登记失败：' + (r?.error ?? '')
    return
  }
  msg.value = '已取消登记 ' + maskAddr(p.agentAddress)
  await refresh()
}

function openEdit(p: ProbeResult) {
  editCard.value = editCard.value === p.id ? '' : p.id
  editHost.value = p.host
  editPort.value = String(p.port)
  editLabel.value = p.label !== p.agentAddress ? p.label : ''
}

async function saveEdit(p: ProbeResult) {
  const host = editHost.value.trim()
  const port = Number(editPort.value)
  if (!host || !Number.isInteger(port) || port <= 0 || port > 65535) {
    msg.value = '编辑失败：地址或端口不合法'
    return
  }
  const r = await window.electronAPI?.coverageProbeRegistry({
    action: 'update', id: registryId(p), host, port, label: editLabel.value.trim()
  })
  if (!r?.success) {
    msg.value = '保存失败：' + (r?.error ?? '')
    return
  }
  msg.value = `已更新登记：${host}:${port}${editLabel.value.trim() ? '（' + editLabel.value.trim() + '）' : ''}`
  editCard.value = ''
  await refresh()
}

/** 探测结果 id → 登记册 id：登记项用 host:port 定位（id 可能是 'config' 这类虚拟 id） */
function registryId(p: ProbeResult): string {
  return p.source === 'registered' ? (p.id.startsWith('reg-') ? p.id : `${p.host}:${p.port}`) : ''
}
function isCurrent(p: ProbeResult): boolean {
  return props.agentAddress.trim() === p.agentAddress
}
function isRegistered(p: ProbeResult): boolean {
  // 只有真正登记过的才能「取消登记」；当前配置那条不在登记册里
  return p.source === 'registered'
}

async function setAsCurrent(p: ProbeResult) {
  emit('use-address', p.agentAddress)
  msg.value = '已设为当前 Agent 地址：' + maskAddr(p.agentAddress)
}

/** 配置项专有：它不是登记项，清除的是覆盖率配置里的 Agent 地址（dump / 报告 / 设置 Key 都靠它） */
function clearCurrent() {
  if (!confirm('清除后，dump / 生成报告 / 设置 Key 都需要重新填写 Agent 地址。确定清除当前配置的地址吗？')) return
  emit('clear-address')
  msg.value = '已清除当前 Agent 地址。需要时在「工具与报告」重新填写，或在下面任意探针卡片点「设为当前」'
}

async function doSetKey(p: ProbeResult, clear = false) {
  const r = await window.electronAPI?.coverageSetkey({
    agentAddress: p.agentAddress,
    key: keyInput.value.trim() || undefined,
    clear: clear || !keyInput.value.trim() ? true : undefined
  })
  if (!r?.success) {
    msg.value = '设置 Key 失败：' + (r?.error ?? '')
    return
  }
  msg.value = clear ? `已清除 ${maskAddr(p.agentAddress)} 的全局 key` : `已将 ${maskAddr(p.agentAddress)} 的全局 key 设为 ${keyInput.value.trim()}`
  keyCard.value = ''
  await refresh()
}

async function viewDiag(p: ProbeResult) {
  if (diagCard.value === p.id) {
    diagCard.value = ''
    return
  }
  diagText.value = '加载中…'
  diagCard.value = p.id
  const r = await window.electronAPI?.coverageStats({ agentAddress: p.agentAddress, limit: 30 })
  diagText.value = r?.success ? (r.text ?? '(无输出)') : '诊断失败：' + (r?.error ?? '未知原因')
}

/** 卡片折叠状态（按 id 记，默认全部展开） */
const collapsed = ref<Record<string, boolean>>({})
function isCollapsed(id: string): boolean {
  return !!collapsed.value[id]
}
function toggleCard(id: string): void {
  collapsed.value[id] = !collapsed.value[id]
}

// ===== 状态色 / 文案 =====
// 语义：只要握手成功（能连上、确认是探针）就显示「在线」——探针活着 ≠ 已经采到数据，
// 「还没采到 Key」用独立的琥珀色小标签表达，不再把整卡判成黄色让人误以为探针没启动。
const STATUS_META: Record<string, { cls: string; text: string }> = {
  online: { cls: 'ps-online', text: '在线' },
  warning: { cls: 'ps-online', text: '在线' },
  vanilla: { cls: 'ps-vanilla', text: '官方 JaCoCo' },
  offline: { cls: 'ps-offline', text: '离线' }
}
function statusMeta(s: string) {
  return STATUS_META[s] ?? STATUS_META.offline
}
/** 状态文字用纯色文本（ps-* 同时带 background 是给圆点用的，文字复用会变成实心色块） */
function statusTextCls(s: string): string {
  return 'st-' + (s === 'vanilla' || s === 'offline' ? s : 'online')
}
/** 卡片左边框色：在线（含未采集）都是绿，只有真离线 / 非 xiaoxiao 探针才用别的颜色 */
function cardCls(s: string): string {
  return 'pb-' + (s === 'online' || s === 'warning' ? 'online' : s)
}
function sourceText(s: string): string {
  return s === 'config' ? '当前配置' : s === 'registered' ? '已登记' : '本机扫描'
}
function coveredPct(k: { probes: number; covered: number }): number {
  return k.probes > 0 ? Math.round((k.covered / k.probes) * 100) : 0
}
const onlineCount = computed(() => (summary.value?.online ?? 0) + (summary.value?.warning ?? 0))
const warningCount = computed(() => summary.value?.warning ?? 0)
const offlineCount = computed(() => (summary.value?.offline ?? 0) + (summary.value?.vanilla ?? 0))

function restartTimer() {
  if (timer) clearInterval(timer)
  if (!autoRefresh.value) return
  timer = setInterval(() => refresh(), autoRefresh.value)
}
watch(autoRefresh, restartTimer)

/** 主进程 20s 后台心跳也会推摘要：这里只更新徽标，不重排列表（避免正在看的卡片跳动） */
let unsubscribeSummary: (() => void) | undefined

onMounted(() => {
  refresh()
  offProgress = window.electronAPI?.onProbeScanProgress((p) => {
    if (!p) return
    rangeProgress.value = p.finished ? '完成' : `已扫 ${p.done}/${p.total}`
  })
  unsubscribeSummary = window.electronAPI?.onCoverageProbeHeartbeat((p) => {
    if (!p) return
    summary.value = p.summary ?? summary.value
    // 心跳是全量结果，直接用；排序在引擎侧已按 状态→端口 稳定排序，不会造成卡片乱跳
    if (p.probes?.length || probes.value.length) probes.value = p.probes ?? []
  })
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  unsubscribeSummary?.()
  offProgress?.()
})
</script>

<template>
  <div class="probe-page">
    <div class="card">
      <div class="card-head">
        <h3>在线探针 <span class="tag tag-blue">Probe Monitor</span></h3>
        <div class="pb-summary">
          <span class="badge b-all">共 {{ summary?.total ?? 0 }}</span>
          <span class="badge b-online">{{ onlineCount }} 在线</span>
          <span class="badge b-warn">{{ warningCount }} 未采到</span>
          <span class="badge b-off">{{ offlineCount }} 离线</span>
          <span v-if="summary" class="pb-time">{{ new Date(summary.checkedAt).toLocaleTimeString() }}</span>
        </div>
      </div>
      <div class="sub">
        被测服务的 JVM 挂上探针后，这里会定时逐一「敲门」：能连上、能确认是探针，就显示<b>在线</b>。心跳只读自检计数，不会影响正在收集的覆盖率数据。
      </div>

      <div class="pb-toolbar">
        <div class="btn-group">
          <button class="btn btn-primary btn-sm" @click="addOpen = !addOpen">{{ addOpen ? '取消' : '添加探针' }}</button>
          <button class="btn btn-outline btn-sm" :disabled="busy" @click="refresh">{{ busy ? '探测中…' : '刷新' }}</button>
          <button class="btn btn-outline btn-sm" @click="rangeOpen = !rangeOpen">{{ rangeOpen ? '收起远端发现' : '远端发现' }}</button>
        </div>
        <label class="pb-field">
          <span>自动刷新</span>
          <select v-model.number="autoRefresh">
            <option :value="0">关</option>
            <option :value="10000">10 秒</option>
            <option :value="30000">30 秒</option>
          </select>
        </label>
        <label class="pb-field">
          <span>状态</span>
          <select v-model="statusFilter">
            <option value="all">全部</option>
            <option value="online">在线</option>
            <option value="warning">在线·未采集</option>
            <option value="vanilla">官方 JaCoCo</option>
            <option value="offline">离线</option>
          </select>
        </label>
        <label class="pb-field">
          <span>每页</span>
          <select v-model.number="pageSize">
            <option :value="10">10</option>
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="0">全部</option>
          </select>
        </label>
        <label class="pb-check" title="扫描发现确认是探针的地址，自动加入登记簿（持久化，重启仍在）">
          <input type="checkbox" :checked="autoReg" @change="setAutoReg(($event.target as HTMLInputElement).checked)" />
          <span>发现后自动登记</span>
        </label>
        <label class="pb-check" title="勾选后地址完整显示；默认隐藏 IPv4 的后两段（如 10.1.*.*:6300），截图 / 演示不暴露被测机 IP">
          <input type="checkbox" v-model="revealAddr" />
          <span>显示完整地址</span>
        </label>
      </div>

      <div v-if="addOpen" class="pb-add">
        <label class="pb-field"><span>地址 host:port</span><input v-model="newHost" placeholder="被测机 IP（容器填映射到宿主机的端口）" style="min-width:220px" /></label>
        <label class="pb-field"><span>端口</span><input v-model="newPort" type="number" style="width:90px" /></label>
        <label class="pb-field"><span>备注名（可选）</span><input v-model="newLabel" placeholder="订单服务-测试环境" style="min-width:180px" /></label>
        <button class="btn btn-primary btn-sm" @click="addProbe">登记</button>
        <small class="muted">任何能从本机网络到达的地址都可以：远端虚机、容器 publish 出来的端口、k8s NodePort / Service 都行；登记后持久化，重启仍在</small>
      </div>

      <!-- 远端 / 网段发现：目标不在本机时的自动登记入口 -->
      <div v-if="rangeOpen" class="pb-range">
        <div class="pb-range-row">
          <label class="pb-field"><span>被测机 / 网段</span><input v-model="rangeHosts" placeholder="示例：192.0.2.10 或 192.0.2.0/24，多个用逗号分隔" style="min-width:280px" /></label>
          <label class="pb-field"><span>端口</span><input v-model="rangePorts" placeholder="6300-6310,8899" style="min-width:170px" /></label>
          <button class="btn btn-primary btn-sm" :disabled="rangeBusy" @click="scanRange">{{ rangeBusy ? '扫描中…' : '扫描并登记' }}</button>
          <button class="btn btn-outline btn-sm" @click="loadTrafficHosts">从已抓流量提取主机</button>
        </div>
        <small class="muted">
          逐个握手识别：能连上且认得探针协议（回 exec 头）就留下，HTTP / MySQL / redis 这些端口会被自动淘汰 —— 不需要对方开放任何健康检查接口。
          确认是 xiaoxiao 探针的会按上面的开关自动登记；官方 JaCoCo 只列出不自动收。
        </small>
        <div v-if="rangeProgress" class="pb-progress">扫描中… {{ rangeProgress }}</div>

        <div v-if="trafficHostsOpen && trafficHosts.length" class="pb-hosts">
          <div class="pb-hosts-head">流量里出现过的后端主机（点一下填入待扫描；端口需另填，这里是业务端口不是探针端口）</div>
          <button v-for="h in trafficHosts.slice(0, 30)" :key="h.host" class="pb-chip" @click="applyTrafficHost(h)">
            {{ h.host }}<span class="pb-chip-n">{{ h.count }}</span>
          </button>
          <span v-if="trafficHosts.length > 30" class="muted">… 还有 {{ trafficHosts.length - 30 }} 个</span>
        </div>

        <div v-if="rangeSummary" class="pb-msg">{{ rangeSummary }}</div>
        <div v-if="rangeHits.length" class="pb-hits">
          <div v-for="h in rangeHits" :key="h.id" class="pb-hit">
            <span class="pb-dot" :class="statusMeta(h.status).cls"></span>
            <span class="mono">{{ maskAddr(h.agentAddress) }}</span>
            <span class="pb-hit-s">{{ statusMeta(h.status).text }}</span>
            <button v-if="h.source === 'registered'" class="btn btn-outline btn-sm" disabled>已登记</button>
            <button v-else class="btn btn-outline btn-sm" @click="newHost = h.host; newPort = String(h.port); addOpen = true">登记</button>
          </div>
        </div>
      </div>

      <div v-if="msg" class="pb-msg">{{ msg }}</div>
    </div>

      <!-- 搜索 / 筛选：登记多了靠关键字定位（匹配备注名、地址、来源） -->
      <div class="pb-filterbar">
        <input v-model="keyword" class="pb-search" placeholder="搜索备注名 / 地址（如 web1、192.0.2.*）" />
        <span class="muted">筛出 {{ filtered.length }} / {{ probes.length }} 条</span>
        <button v-if="keyword || statusFilter !== 'all'" class="btn btn-outline btn-sm" @click="keyword = ''; statusFilter = 'all'">清空筛选</button>
      </div>

      <!-- 空态：给出可照抄的挂载参数，而不是干巴巴的「暂无数据」 -->
      <div v-if="!probes.length" class="card">
      <div class="pb-empty">
        <p class="re-title">还没有探针</p>
        <p class="re-sub">在被测服务的启动命令里挂上探针（照抄下面这行），然后点「<b>添加探针</b>」登记地址，或用「<b>远端发现</b>」按网段批量探测——目标服务不在本机时（容器 / 远端虚机）走这两条路。</p>
        <pre class="code-block pb-mount">java <b>-javaagent:xiaoxiao-jacoco-agent.jar=</b>headerkey=X-Coverage-Key,output=tcpserver,address=0.0.0.0,port=8899,includes=com.yourpkg.* -jar order-service.jar
<span class="muted"># address=0.0.0.0 供 oAT 远程 dump；includes 改成被测系统实际包名</span></pre>
      </div>
    </div>

    <p v-if="probes.length && !filtered.length" class="pb-empty-hint">没有命中的探针：换个关键字，或点「清空筛选」</p>

    <div v-for="p in paged" :key="p.id" class="card pb-card" :class="cardCls(p.status)">
      <div class="pb-head" :title="isCollapsed(p.id) ? '展开详情' : '收起详情'" @click="toggleCard(p.id)">
        <span class="pb-chev" :class="{ open: !isCollapsed(p.id) }">▸</span>
        <span class="pb-dot" :class="statusMeta(p.status).cls"></span>
        <strong class="pb-addr">{{ displayLabel(p) }}</strong>
        <span v-if="p.label !== p.agentAddress" class="mono pb-host">{{ maskAddr(p.agentAddress) }}</span>
        <span class="tag" :class="p.source === 'discovered' ? 'tag-amber' : 'tag-blue'">{{ sourceText(p.source) }}</span>
        <!-- 「当前」徽标只给登记/扫描项：配置项本身就是当前地址，再标一次是重复 -->
        <span v-if="isCurrent(p) && p.source !== 'config'" class="tag tag-green">当前</span>
        <span v-if="p.pid" class="tag">PID {{ p.pid }}</span>
        <span class="pb-status" :class="statusTextCls(p.status)">{{ statusMeta(p.status).text }}</span>
        <span v-if="p.status === 'warning'" class="tag tag-amber" title="探针在线，但还没有采集到任何归属 Key">未采集</span>
        <span class="pb-mono">{{ p.ms }}ms · {{ new Date(p.pingedAt).toLocaleTimeString() }}</span>
      </div>

      <div v-if="!isCollapsed(p.id)" class="pb-body">
        <div v-if="p.diag" class="pb-metrics">
          <div class="pb-m"><span class="pb-mv">{{ p.diag.classesInstrumented }}</span><span class="pb-ml">插桩类</span><small>共见 {{ p.diag.classesSeen }} 个类</small></div>
          <div class="pb-m"><span class="pb-mv">{{ p.keys?.length ?? 0 }}</span><span class="pb-ml">已采集 Key</span><small>按归属 key 分别攒数据</small></div>
          <div class="pb-m" :class="{ danger: p.diag.requestsTagged === 0 }"><span class="pb-mv">{{ p.diag.requestsTagged }}</span><span class="pb-ml">归因请求</span><small>钩子触发 {{ p.diag.requestsHooked }} 次</small></div>
          <div class="pb-m"><span class="pb-mv">{{ p.keyStats?.reduce((a, k) => a + k.probes, 0) ?? 0 }}</span><span class="pb-ml">探针数</span><small>覆盖 {{ p.keyStats?.reduce((a, k) => a + k.covered, 0) ?? 0 }} 个</small></div>
        </div>

        <p class="pb-note" :class="{ bad: p.status !== 'online' }">{{ p.note }}</p>
        <p v-if="p.diag?.suggestedIncludes?.length" class="pb-hint">
          建议 includes：<code>{{ p.diag.suggestedIncludes.join(' ') }}</code>
          <span v-if="p.diag.classesNoLocation > 0">· 有 {{ p.diag.classesNoLocation }} 个类因无 source location 被跳过（Spring Boot 可执行 jar 通常要加 inclnolocationclasses=true）</span>
        </p>

        <table v-if="p.keyStats?.length" class="data pb-keys">
          <thead><tr><th>Key</th><th>类</th><th>探针</th><th>已覆盖</th><th style="width:180px">覆盖率</th></tr></thead>
          <tbody>
            <tr v-for="k in p.keyStats" :key="k.key">
              <td class="mono">{{ k.key }}</td>
              <td>{{ k.classes }}</td>
              <td>{{ k.probes }}</td>
              <td>{{ k.covered }}</td>
              <td>
                <div class="pb-bar"><i :style="{ width: coveredPct(k) + '%' }"></i></div>
                <span class="pb-pct">{{ coveredPct(k) }}%</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="pb-actions">
          <button class="btn btn-outline btn-sm" @click="refresh">重新探测</button>
          <button class="btn btn-primary btn-sm" :disabled="isCurrent(p)" @click="setAsCurrent(p)">{{ isCurrent(p) ? '已是当前' : '设为当前' }}</button>
          <button class="btn btn-outline btn-sm" @click="viewDiag(p)">{{ diagCard === p.id ? '收起诊断 ▲' : '查看完整诊断' }}</button>
          <button class="btn btn-outline btn-sm" @click="keyCard = keyCard === p.id ? '' : p.id">设置 Key</button>
          <template v-if="isRegistered(p)">
            <button class="btn btn-outline btn-sm" @click="openEdit(p)">{{ editCard === p.id ? '收起编辑 ▲' : '编辑' }}</button>
            <button class="btn btn-outline btn-sm" @click="removeProbe(p)">取消登记</button>
          </template>
          <button v-else-if="p.source === 'discovered'" class="btn btn-outline btn-sm" @click="newHost = p.host; newPort = String(p.port); addOpen = true">登记该探针</button>
          <!-- 配置项不是登记项：唯一出口是清掉覆盖率配置里的 Agent 地址 -->
          <button v-else class="btn btn-outline btn-sm" @click="clearCurrent">清除当前地址</button>
        </div>

        <div v-if="editCard === p.id" class="pb-editform">
          <input v-model="editHost" placeholder="host（如 10.0.0.7 / 127.0.0.1）" style="width:200px" />
          <input v-model="editPort" type="number" placeholder="端口" style="width:110px" />
          <input v-model="editLabel" placeholder="备注名（可空，如 订单服务-测试环境）" style="width:230px" />
          <button class="btn btn-primary btn-sm" @click="saveEdit(p)">保存</button>
          <button class="btn btn-outline btn-sm" @click="editCard = ''">取消</button>
          <small class="muted">改地址 / 备注名都会持久化；若改成了当前地址，会自动成为 dump 与报告的目标</small>
        </div>

        <div v-if="keyCard === p.id" class="pb-keyform">
          <input v-model="keyInput" placeholder="归属 key（如用例 ID、用户名）；留空点「设置」= 清除全局 key" />
          <button class="btn btn-primary btn-sm" @click="doSetKey(p, false)">设置</button>
          <button class="btn btn-outline btn-sm" @click="doSetKey(p, true)">清除全局 Key</button>
          <small class="muted">进程外驱动：不需要请求头，但该时间内所有请求都会归到这个 key —— 适合串行执行的用例</small>
        </div>

        <pre v-if="diagCard === p.id" class="code-block pb-diag">{{ diagText }}</pre>
      </div>
    </div>

    <!-- 分页：心跳刷新不会重置页码与折叠状态（都按 id / 局部 ref 记住） -->
    <div v-if="pageSize > 0 && filtered.length > pageSize" class="pb-pager">
      <button class="btn btn-outline btn-sm" :disabled="page <= 1" @click="page = page - 1">上一页</button>
      <span class="pb-pager-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ filtered.length }} 条</span>
      <button class="btn btn-outline btn-sm" :disabled="page >= totalPages" @click="page = page + 1">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.probe-page { display: flex; flex-direction: column; gap: 14px; }
.pb-summary { display: flex; align-items: center; gap: 6px; }
.badge { font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--border); }
.b-all { background: var(--bg-soft); color: #475569; }
.b-online { background: #ecfdf5; color: #047857; border-color: #a7f3d0; }
.b-warn { background: #fffbeb; color: #92400e; border-color: #fde68a; }
.b-off { background: #f8fafc; color: #64748b; }
.pb-time { font-size: 11px; color: var(--text-dim); margin-left: 4px; }
.pb-toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin-bottom: 10px; }
.pb-toolbar .pb-field { flex-direction: row; align-items: center; gap: 8px; }
.pb-toolbar .pb-field select { width: auto; }
.pb-field { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: #475569; }
.pb-field input, .pb-field select { border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 10px; font-size: 13px; color: #1f2937; background: #fff; }
.pb-add { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; padding: 12px; border: 1px dashed var(--border-strong); border-radius: 8px; margin-bottom: 10px; }
.pb-add .muted { flex: 1 1 100%; }

/* 搜索 / 筛选 / 分页 */
.pb-filterbar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 10px; }
.pb-search { flex: 1 1 260px; max-width: 420px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 10px; font-size: 13px; }
.pb-search:focus { outline: none; border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12); }
.pb-empty-hint { padding: 14px; text-align: center; color: var(--text-dim); font-size: 13px; }
.pb-pager { display: flex; gap: 10px; align-items: center; justify-content: center; margin-top: 12px; }
.pb-pager-info { font-size: 12px; color: var(--text-dim); font-variant-numeric: tabular-nums; }

/* 远端 / 网段发现 */
.pb-range { padding: 12px; border: 1px dashed var(--border-strong); border-radius: 8px; margin-bottom: 10px; }
.pb-range-row { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; margin-bottom: 8px; }
.pb-range-row .pb-field { flex-direction: row; align-items: center; gap: 8px; }
.pb-range .muted { display: block; line-height: 1.6; }
.pb-progress { margin-top: 8px; font-size: 12px; color: #4338ca; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.pb-hosts { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.pb-hosts-head { flex: 1 1 100%; font-size: 12px; color: #475569; margin-bottom: 2px; }
.pb-chip { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--border-strong); background: var(--bg-soft); border-radius: 999px; padding: 3px 10px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; cursor: pointer; }
.pb-chip:hover { background: #eef2ff; border-color: #c7d2fe; }
.pb-chip-n { color: var(--text-dim); font-size: 11px; }
.pb-hits { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.pb-hit { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--border-soft); border-radius: 8px; background: var(--bg-soft); }
.pb-hit-s { font-size: 12px; color: var(--text-dim); }
.pb-hit .btn { margin-left: auto; }
.pb-check { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #475569; cursor: pointer; padding-bottom: 7px; }
.pb-check input { accent-color: #2563eb; }
.pb-msg { font-size: 12px; color: #475569; background: var(--bg-soft); border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; margin-bottom: 10px; line-height: 1.6; }
.pb-msg.warn { background: #fffbeb; border-color: #fde68a; color: #92400e; }
.pb-empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 18px 0 6px; }
.pb-empty .re-title { font-weight: 600; color: #334155; margin: 0; }
.pb-empty .re-sub { color: #64748b; font-size: 12.5px; margin: 0; max-width: 78%; text-align: center; }
.pb-mount { width: 100%; max-width: 720px; margin-top: 6px; white-space: pre-wrap; }

.pb-card { border-left: 3px solid var(--border); }
.pb-card.pb-online { border-left-color: #16a34a; }
.pb-card.pb-warning { border-left-color: #d97706; }
.pb-card.pb-vanilla { border-left-color: #2563eb; }
.pb-card.pb-offline { border-left-color: #cbd5e1; }
.pb-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; cursor: pointer; user-select: none; }
.pb-head:hover .pb-addr { color: #0f172a; }
.pb-chev { color: #94a3b8; font-size: 11px; transition: transform 0.15s ease; flex-shrink: 0; }
.pb-chev.open { transform: rotate(90deg); }
.pb-body { padding-top: 2px; }
.pb-addr { font-size: 14px; font-weight: 650; color: #1e293b; letter-spacing: -0.01em; }
.pb-host { font-size: 12px; color: #64748b; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; }
.pb-status { margin-left: auto; font-size: 12px; font-weight: 600; }
.st-online { color: #15803d; }
.st-warning { color: #b45309; }
.st-vanilla { color: #2563eb; }
.st-offline { color: #64748b; }
.pb-mono { font-size: 11.5px; color: var(--text-dim); font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-variant-numeric: tabular-nums; }
.pb-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.ps-online { background: #16a34a; color: #16a34a; }
.ps-warning { background: #d97706; color: #d97706; }
.ps-vanilla { background: #2563eb; color: #2563eb; }
.ps-offline { background: #cbd5e1; color: #94a3b8; }
.pb-head .pb-mono { color: var(--text-dim); }

.pb-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 12px 0 8px; }
.pb-m { background: var(--bg-soft); border: 1px solid var(--border-soft); border-radius: 10px; padding: 10px 12px; }
.pb-m.danger { background: #fef2f2; border-color: #fecaca; }
.pb-mv { display: block; font-size: 21px; font-weight: 700; color: #1e293b; line-height: 1.2; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.pb-m.danger .pb-mv { color: #b91c1c; }
.pb-ml { display: block; font-size: 12px; color: #475569; margin-top: 2px; }
.pb-m small { display: block; font-size: 11px; color: var(--text-dim); margin-top: 2px; }
.pb-note { font-size: 12.5px; color: #475569; line-height: 1.7; margin: 0; }
.pb-note.bad { color: #92400e; }
.pb-hint { font-size: 12px; color: #64748b; margin: 6px 0 0; }
.pb-hint code { background: var(--bg-soft); border: 1px solid var(--border); border-radius: 6px; padding: 1px 6px; }
.pb-keys { margin-top: 10px; }
.pb-bar { display: inline-block; width: 120px; height: 6px; border-radius: 3px; background: #e2e8f0; overflow: hidden; vertical-align: middle; }
.pb-bar i { display: block; height: 100%; background: #16a34a; }
.pb-pct { font-size: 11.5px; color: #475569; margin-left: 6px; }
.pb-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.pb-keyform { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; }
.pb-keyform input { flex: 1 1 260px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 10px; font-size: 13px; }
.pb-keyform .muted { flex: 1 1 100%; }
/* 编辑表单：定宽输入框（不能复用 pb-keyform 的 flex:1 1 260px —— 在容器里会把输入框撑成大方块） */
.pb-editform { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; padding: 10px 12px; background: var(--bg-soft); border: 1px dashed var(--border-strong); border-radius: 8px; }
.pb-editform input { flex: 0 1 auto; border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 10px; font-size: 13px; color: #1f2937; background: #fff; }
.pb-editform .muted { flex: 1 1 100%; }
.pb-diag { margin-top: 10px; max-height: 320px; overflow: auto; }
</style>
