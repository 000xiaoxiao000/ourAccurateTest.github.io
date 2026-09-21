<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useTrafficStore } from '../stores/traffic'
import type { CoverageBackendInfo, CoverageExecInfo, CoverageParamSpec } from '../types/traffic'
import type { CoveragePathProbe } from '../types/electron'

const store = useTrafficStore()
const execs = ref<CoverageExecInfo[]>([])
const backendId = ref(store.coverageConfig.backend || 'jacoco')
const reportUrl = ref('')
const reportDir = ref('')
const msg = ref('')

// ===== 指令级 UI 状态：每个后端(插件)声明全部指令，每条指令有自己的参数 schema / 真实命令 / 可执行 =====
const toolsOpen = ref(true)

/** 当前选中指令是否依赖某项共享配置（决定配置条是否显示） */
function cmdNeeds(what: 'agent' | 'classfiles'): boolean {
  return !!activeCommand.value?.needs?.includes(what)
}
const activeCommandId = ref('')
const activeCommand = computed(() => selectedBackend.value?.commands.find((c) => c.id === activeCommandId.value))
// 每条指令持有自己的参数值（目录对应指令），切 tab 不串不丢
const commandValuesMap = ref<Record<string, Record<string, string>>>({})
const commandValues = computed(() => commandValuesMap.value[activeCommandId.value] ?? {})
const commandPreviewText = ref('')
const commandOutput = ref('')
const busyCmd = ref(false)
const detecting = ref(false)
const cfProbe = ref<CoveragePathProbe | null>(null)
const srcProbe = ref<CoveragePathProbe | null>(null)
const cfCandidates = ref<CoveragePathProbe[]>([])

const backends = computed(() => store.coverageBackends)
const selectedBackend = computed<CoverageBackendInfo | undefined>(
  () => backends.value.find((b) => b.id === backendId.value)
)

function cfg() {
  return store.coverageConfig
}

/** Vue reactive Proxy 无法被 Electron IPC 结构化克隆，必须先转纯对象 */
function plain<T>(v: T): T {
  return JSON.parse(JSON.stringify(v))
}

function initAllCommandValues(b?: CoverageBackendInfo) {
  const map: Record<string, Record<string, string>> = {}
  for (const c of b?.commands ?? []) {
    const vals: Record<string, string> = {}
    for (const p of c.params) vals[p.key] = p.default ?? ''
    map[c.id] = vals
  }
  commandValuesMap.value = map
}

async function selectBackend(id: string) {
  backendId.value = id
  await store.saveCoverageConfig({ backend: id })
  if (!selectedBackend.value?.commands.find((c) => c.id === activeCommandId.value)) {
    activeCommandId.value = selectedBackend.value?.commands[0]?.id ?? ''
  }
  initAllCommandValues(selectedBackend.value)
  await refreshCommandPreview()
}

async function refreshCommandPreview() {
  if (!activeCommand.value) { commandPreviewText.value = ''; return }
  try {
    const r = await window.electronAPI?.coverageCommandPreview({
      backendId: backendId.value,
      commandId: activeCommandId.value,
      values: plain(commandValues.value),
      execs: execs.value.map((e) => e.file)
    })
    commandPreviewText.value = r?.success ? (r.text ?? '') : '# 预览失败：' + (r?.error ?? '')
  } catch (e: any) {
    // IPC reject 也不能让预览卡停留在「加载中」
    commandPreviewText.value = '# 预览失败：' + (e?.message || String(e))
  }
}

function selectCommand(id: string) {
  activeCommandId.value = id
  commandOutput.value = ''
  refreshCommandPreview()
}

async function runCoverageCommand() {
  if (!activeCommand.value || busyCmd.value) return
  busyCmd.value = true; msg.value = ''
  let r: any
  try {
    r = await window.electronAPI?.coverageRunCommand({
      backendId: backendId.value,
      commandId: activeCommandId.value,
      values: plain(commandValues.value),
      key: cfg().key,
      execs: execs.value.map((e) => e.file)
    })
  } finally {
    busyCmd.value = false
  }
  if (r?.success) {
    commandOutput.value = r.text ?? '(无标准输出)'
    for (const e of r.execs ?? []) if (!execs.value.find((x) => x.file === e.file)) execs.value.push(e)
    if (r.reportDir) { reportDir.value = r.reportDir; reportUrl.value = r.hasHtml === false ? '' : 'file://' + r.reportDir + '/index.html' }
    msg.value = '指令执行完成'
  } else {
    commandOutput.value = ((r?.stdout || r?.stderr || '') + '\n✗ ' + (r?.error ?? '执行失败')).trim()
  }
}

async function pickCommandPath(p: CoverageParamSpec) {
  const r = await window.electronAPI?.coveragePickPath({ pick: p.pick, title: p.label })
  if (r?.success && r.path) {
    commandValues.value[p.key] = r.path
    await refreshCommandPreview()
  }
}

async function refreshBackends() {
  await store.loadCoverageBackends()
  if (!backends.value.find((b) => b.id === backendId.value)) {
    backendId.value = backends.value[0]?.id ?? 'jacoco'
  }
  if (!selectedBackend.value?.commands.find((c) => c.id === activeCommandId.value)) {
    activeCommandId.value = selectedBackend.value?.commands[0]?.id ?? ''
  }
  initAllCommandValues(selectedBackend.value)
  await refreshCommandPreview()
}

async function doExport() {
  if (!reportDir.value) { msg.value = '还没有报告可导出'; return }
  const r = await window.electronAPI?.coverageExportReport({ reportDir: reportDir.value })
  msg.value = r?.success ? '已导出：' + (r.filePath ?? '') : '导出失败：' + (r?.error ?? '')
}

async function openReport() {
  if (!reportDir.value) { msg.value = '还没有报告可打开'; return }
  const r = await window.electronAPI?.coverageOpenReport({ reportDir: reportDir.value })
  if (r && !r.success) msg.value = '打开失败：' + (r.error ?? '')
}

function onClassfilesChange() {
  store.saveCoverageConfig({ classfilesPath: cfg().classfilesPath })
  scheduleProbe()
}

async function pickClassfiles() {
  // 目录、zip、jar 都可以当 classfiles（CLI 按 entry 分析归档）
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dirOrFile', title: '选择 classfiles（构建产物目录 / zip / jar）' })
  if (r?.success && r.path) {
    store.saveCoverageConfig({ classfilesPath: r.path })
    scheduleProbe()
  }
}

async function pickProjectDir() {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择项目根目录' })
  if (r?.success && r.path) store.saveCoverageConfig({ projectDir: r.path })
}

/** 由项目根目录自动推导 classfiles（覆盖率分母）与源码目录（精确到 src/main/java 一层） */
async function autoDetect() {
  detecting.value = true
  try {
    const r = await window.electronAPI?.coverageDetectProject({ projectDir: cfg().projectDir, classfilesRepo: cfg().classfilesRepo })
    if (!r) { msg.value = '自动推导失败：无返回'; return }
    if (!r.success && !r.classfilesPath && !r.sourcefilesPath) { msg.value = '自动推导失败：' + (r.error ?? '未知原因'); return }
    if (r.classfilesPath) store.saveCoverageConfig({ classfilesPath: r.classfilesPath })
    if (r.sourcefilesPath) commandValues.value['sourcefilesPath'] = r.sourcefilesPath
    cfProbe.value = r.classfiles ?? null
    srcProbe.value = r.sourcefiles ?? null
    cfCandidates.value = r.classfilesCandidates ?? []
    msg.value = [
      '✓ 已自动推导（项目根目录：' + r.projectDir + '）',
      'classfiles = ' + (r.classfilesPath || '(未找到，需手动指定)'),
      '源码目录   = ' + (r.sourcefilesPath || '(未找到，报告将无源码跳转)'),
      ...(r.warnings?.length ? ['', '提示：', ...r.warnings.map((w) => '· ' + w)] : [])
    ].join('\n')
    await refreshCommandPreview()
  } finally {
    detecting.value = false
  }
}

/** 一键切换到探测到的其他候选（如仓库 zip ↔ target/classes） */
async function useCandidate(p: string) {
  store.saveCoverageConfig({ classfilesPath: p })
  await refreshCommandPreview()
  await runProbes()
}

/** 路径即时校验（防抖，避免每敲一个字符就扫描磁盘） */
let probeTimer: ReturnType<typeof setTimeout> | undefined
function scheduleProbe() {
  if (probeTimer) clearTimeout(probeTimer)
  probeTimer = setTimeout(runProbes, 600)
}
async function runProbes() {
  if (!cmdNeeds('classfiles')) return
  const p = await window.electronAPI?.coverageCheckPath({ path: cfg().classfilesPath, role: 'classfiles' })
  cfProbe.value = p ?? null
  const sp = (commandValues.value['sourcefilesPath'] || '').trim()
  srcProbe.value = sp
    ? (await window.electronAPI?.coverageCheckPath({ path: sp, role: 'sourcefiles', classfilesPath: cfg().classfilesPath }) ?? null)
    : null
}
watch(() => [cfg().classfilesPath, commandValues.value['sourcefilesPath'], activeCommandId.value], scheduleProbe)

/** 校验徽标：绿=可用，黄=可用但有隐患，红=不可用（报告会空/无源码） */
function probeBadge(p: CoveragePathProbe | null, optional = false) {
  if (!p) return null
  if (!p.path) return optional ? null : { cls: 'pb-red', text: '✗ 未填写' }
  if (!p.exists) return { cls: optional ? 'pb-amber' : 'pb-red', text: '✗ ' + p.note }
  if (p.fileCount <= 0) return { cls: 'pb-red', text: '✗ ' + p.note }
  if (p.packageHits === 0 && optional) return { cls: 'pb-amber', text: '⚠ ' + p.note }
  return { cls: 'pb-green', text: '✓ ' + p.note }
}
const cfBadge = computed(() => probeBadge(cfProbe.value))
const srcBadge = computed(() => probeBadge(srcProbe.value, true))

onMounted(refreshBackends)
watch(() => store.coverageConfig.backend, (id) => { if (id !== backendId.value) selectBackend(id) })
</script>

<template>
  <div class="coverage-page">
    <div class="flow-strip">
      <div class="flow-step done"><span class="no">✓</span><div><b>① 采集</b><small>代理注入 Key / 工具链运行期采集</small></div></div>
      <div class="flow-step" :class="{ cur: execs.length === 0 }"><span class="no">2</span><div><b>② 生成报告</b><small>按语言调用原生工具</small></div></div>
      <div class="flow-step" :class="{ cur: !!reportDir }"><span class="no">3</span><div><b>③ 查看覆盖率报告</b><small>直接打开后端生成的原生报告</small></div></div>
    </div>

    <!-- 覆盖工具（每种支持语言一个，可收缩/展开） -->
    <div class="card">
      <div class="card-head" style="cursor:pointer; user-select:none" @click="toolsOpen = !toolsOpen">
        <h3>覆盖工具 <span class="tag tag-blue">Coverage Tools</span><span class="muted" style="font-size:12px;margin-left:8px">每种支持语言一个工具（采集→报告），含前端（Istanbul）与后端（JaCoCo 等）</span></h3>
        <span class="fold-btn">{{ toolsOpen ? '收起 ▲' : '展开 ▼' }}</span>
      </div>
      <div v-show="toolsOpen">
      <div class="sub">指令与参数全部 UI 化编辑，实时预览真实命令，oAT 直接使用各工具生成的原生报告</div>
      <div class="plugin-grid">
        <div v-for="b in backends" :key="b.id" class="plugin-card" :class="{ sel: backendId === b.id }" @click="selectBackend(b.id)">
          <h4>{{ b.name }} <span class="tag" :class="b.status === 'builtin' ? 'tag-green' : 'tag-amber'">{{ b.status === 'builtin' ? '就绪' : '待安装' }}</span></h4>
          <p>{{ b.languages.join(' / ') }}</p>
          <p class="desc">{{ b.description }}</p>
        </div>
      </div>
      </div>
    </div>

    <!-- 全部指令：该工具声明的所有指令，逐条参数 UI 化 + 真实命令预览 + 可执行（参数与目录都对应到各指令） -->
    <div class="card" v-if="selectedBackend && selectedBackend.commands.length">
      <div class="card-head">
        <h3>{{ selectedBackend.name }} · 全部指令</h3>
        <span class="tag tag-blue">{{ selectedBackend.commands.length }} 条</span>
      </div>
      <div class="sub">该工具声明的所有指令（指令、参数均由插件提供），点击指令名切换，实时预览真实命令行，可直接执行</div>

      <!-- Agent 连接配置：仅当前指令需要连 Agent 时出现（dump/keys/stats/setkey/dumpclasses 等） -->
      <div v-if="cmdNeeds('agent')" class="conn-bar">
        <label class="input-group"><span>Agent 地址</span><input :value="cfg().agentAddress" style="min-width:170px" @input="store.saveCoverageConfig({ agentAddress: ($event.target as HTMLInputElement).value }); refreshCommandPreview()"></label>
        <label class="input-group"><span>归属 Key</span><input :value="cfg().key" style="min-width:170px" @input="store.saveCoverageConfig({ key: ($event.target as HTMLInputElement).value }); refreshCommandPreview()"></label>
        <details class="mount-tip">
          <summary>被测服务挂载探针的启动参数</summary>
          <p class="code-block" style="margin:8px 0 0">java <b>-javaagent:xiaoxiao-jacoco-agent.jar=</b>headerkey={{ cfg().headerName }},output=tcpserver,address=0.0.0.0,port={{ (cfg().agentAddress.split(':')[1] || 8899) }},includes=com.oat.* -jar order-service.jar
<span class="muted"># includes 改成被测系统实际包名（如 com.xxl.job.*）；address=0.0.0.0 供 oAT 远程 dump（Agent 地址=被测机IP:port）</span></p>
        </details>
      </div>

      <div class="cmd-tabs">
        <button v-for="c in selectedBackend.commands" :key="c.id" type="button" class="cmd-tab" :class="{ on: activeCommandId === c.id }" @click="selectCommand(c.id)">
          {{ c.label }}
        </button>
      </div>
      <div v-if="activeCommand" class="cmd-detail">
        <p class="sub" style="margin-top:10px"><b>{{ activeCommand.label }}</b>：{{ activeCommand.description }}</p>
        <div class="param-grid" v-if="activeCommand.params.length">
          <label v-for="p in activeCommand.params" :key="p.key" class="param" :class="'pt-' + p.type">
            <span class="pl">{{ p.label }}<i v-if="p.required" class="req">*</i></span>
            <input v-if="p.type === 'text' || p.type === 'number'" :type="p.type === 'number' ? 'number' : 'text'" v-model="commandValues[p.key]" :placeholder="p.placeholder" @input="refreshCommandPreview()" />
            <select v-else-if="p.type === 'select'" v-model="commandValues[p.key]" @change="refreshCommandPreview()">
              <option v-for="o in p.options" :key="o" :value="o">{{ o }}</option>
            </select>
            <span v-else-if="p.type === 'boolean'" class="bool">
              <label class="switch" :class="{ on: commandValues[p.key] === 'true' }">
                <input type="checkbox" v-model="commandValues[p.key]" true-value="true" false-value="false" @change="refreshCommandPreview()" hidden />
              </label>
              <em>{{ commandValues[p.key] === 'true' ? '开' : '关' }}</em>
            </span>
            <span v-else-if="p.type === 'path'" class="path">
              <input v-model="commandValues[p.key]" :placeholder="p.placeholder || '选择路径'" @input="refreshCommandPreview()" />
              <button class="btn btn-outline btn-sm" @click="pickCommandPath(p)">选择</button>
            </span>
            <small v-if="p.help" class="ph">{{ p.help }}</small>
            <small v-if="p.key === 'sourcefilesPath' && srcBadge" class="pb" :class="srcBadge.cls">{{ srcBadge.text }}</small>
          </label>
        </div>
        <p v-else class="muted" style="margin:6px 0">该指令无参数</p>
        <!-- 项目根目录：填一次，自动推导 classfiles（覆盖率分母）与源码目录 -->
        <div v-if="cmdNeeds('classfiles')" class="param-grid">
          <label class="param pt-path">
            <span class="pl">项目根目录（自动推导）</span>
            <span class="path">
              <input :value="cfg().projectDir" placeholder="选择路径" @input="store.saveCoverageConfig({ projectDir: ($event.target as HTMLInputElement).value })" />
              <button class="btn btn-outline btn-sm" @click="pickProjectDir">选择</button>
              <button class="btn btn-primary btn-sm" :disabled="detecting" @click="autoDetect">{{ detecting ? '推导中…' : '自动推导' }}</button>
            </span>
            <small class="ph">填项目根即可：自动找 classfiles（target/classes → build/classes → classfiles 仓库里的同名 zip）与源码根（src/main/java 这一层）</small>
          </label>
        </div>

        <!-- classfiles 数据源（报告分母）：与其他参数同款样式，排在路径参数之后 -->
        <div v-if="cmdNeeds('classfiles')" class="param-grid">
          <label class="param pt-path">
            <span class="pl">classfiles 本地路径<span v-if="cfBadge" class="pb" :class="cfBadge.cls">{{ cfBadge.text }}</span></span>
            <span class="path">
              <input :value="cfg().classfilesPath" placeholder="/path/to/target/classes 或 classfiles/xxx.zip" @input="store.saveCoverageConfig({ classfilesPath: ($event.target as HTMLInputElement).value }); onClassfilesChange(); refreshCommandPreview()" />
              <button class="btn btn-outline btn-sm" @click="pickClassfiles">选择</button>
            </span>
            <small class="ph">报告分母字节码，仅本地路径；可为构建产物目录、zip 或 jar，多模块用 ; 分隔</small>
            <span v-if="cfCandidates.length > 1" class="cand-row">
              <small class="ph" style="margin-right:4px">候选：</small>
              <button v-for="c in cfCandidates" :key="c.path" type="button" class="cand-chip" :class="{ on: c.path === cfg().classfilesPath }" :title="c.note" @click="useCandidate(c.path)">
                {{ c.path.split('/').pop() }} · {{ c.fileCount }} 个 .class
              </button>
            </span>
          </label>
        </div>

        <div class="cmd-preview">
          <div class="cp-head">真实命令预览</div>
          <pre class="code-block">{{ commandPreviewText || '# 加载中…' }}</pre>
        </div>
        <div class="control-row" style="margin-top:12px">
          <button class="btn btn-primary" :disabled="busyCmd" @click="runCoverageCommand">{{ busyCmd ? '执行中…' : '执行该指令' }}</button>
        </div>
        <pre v-if="commandOutput" class="code-block" style="margin-top:12px; max-height:260px; overflow:auto">{{ commandOutput }}</pre>

        <!-- 产出：dump 抓到的 .exec 列表（jacoco） -->
        <div v-if="selectedBackend.id === 'jacoco'" style="margin-top:16px">
          <div class="cp-head">已抓取的 .exec 产出</div>
          <table class="data" v-if="execs.length" style="margin-top:8px">
            <thead><tr><th>文件</th><th>Key</th><th>来源</th><th>大小</th><th>抓取时间</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="e in execs" :key="e.file">
                <td class="mono">{{ e.file.split('/').pop() }}</td>
                <td class="mono" style="color:#2563eb">{{ e.key }}</td>
                <td>{{ e.source }}</td>
                <td>{{ (e.size / 1024).toFixed(0) }} KB</td>
                <td class="mono">{{ new Date(e.fetchedAt).toLocaleTimeString() }}</td>
                <td><span class="tag tag-green">就绪</span></td>
              </tr>
            </tbody>
          </table>
          <p class="muted" v-else style="margin-top:8px">尚无产出。切到「dump · 抓取 .exec」执行，产出会自动列到这里</p>
        </div>
      </div>
    </div>

    <div class="card" v-if="reportDir">
      <div class="card-head">
        <h3>覆盖率报告（原生生成）</h3>
        <div class="btn-group">
          <button class="btn btn-outline btn-sm" @click="doExport">导出 zip</button>
          <button class="btn btn-outline btn-sm" @click="openReport">新窗口打开</button>
        </div>
      </div>
      <iframe v-if="reportUrl" :src="reportUrl" class="report-iframe"></iframe>
      <p v-else class="muted">报告目录：{{ reportDir }}（当前环境下以原生文件打开）</p>
    </div>

    <div class="card" v-if="msg">
      <pre class="msg-box">{{ msg }}</pre>
    </div>
  </div>
</template>

<style scoped>
.coverage-page { display: flex; flex-direction: column; gap: 16px; }

/* 复用现有工具样式 token（App.vue 为 scoped，子组件自带） */
.card { background: #fff; border-radius: 12px; padding: 18px; border: 1px solid #eef1f5; }
.card h3 { font-size: 14px; color: #1f2937; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; }
.card .sub { font-size: 12px; color: #64748b; margin-bottom: 14px; }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.control-row { display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap; }
.input-group { display: flex; flex-direction: column; gap: 6px; }
.input-group span { font-size: 12px; color: #64748b; }
.input-group input, .input-group select { border: 1px solid #cbd5e1; border-radius: 8px; padding: 8px 10px; font-size: 13px; color: #1f2937; background: #fff; min-width: 160px; transition: border-color .15s; }
.input-group input:focus, .input-group select:focus { border-color: #2563eb; }
.btn { border: 1px solid transparent; border-radius: 8px; padding: 7px 14px; font-size: 13px; transition: all .15s; }
.btn-primary { background: #2563eb; color: #fff; } .btn-primary:hover { background: #1d4ed8; }
.btn-primary:disabled, .btn-outline:disabled { opacity: .5; cursor: not-allowed; }
.btn-outline { background: #fff; color: #334155; border-color: #cbd5e1; } .btn-outline:hover { border-color: #2563eb; color: #2563eb; }
.btn-sm { padding: 4px 10px; font-size: 12px; border-radius: 6px; }
.btn-group { display: flex; gap: 8px; flex-wrap: wrap; }
.code-block { background: #17202b; color: #e5edf6; border-radius: 10px; padding: 12px 14px; font-family: ui-monospace, Menlo, monospace; font-size: 12px; line-height: 1.7; overflow-x: auto; white-space: pre; }
.tag { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; padding: 2px 8px; border-radius: 6px; }
.tag-blue { background: #eff6ff; color: #1d4ed8; }
.tag-green { background: #f0fdf4; color: #15803d; }
.tag-amber { background: #fffbeb; color: #b45309; }
.muted { color: #94a3b8; font-size: 12px; }
.panel { background: #fff; border-radius: 12px; border: 1px solid #eef1f5; overflow: hidden; }
.panel-header { padding: 12px 16px; border-bottom: 1px solid #eef1f5; display: flex; align-items: center; justify-content: space-between; }
table.data { width: 100%; border-collapse: collapse; font-size: 12.5px; }
table.data th { text-align: left; padding: 9px 14px; background: #f8fafc; color: #475569; font-weight: 600; font-size: 12px; border-bottom: 1px solid #eef1f5; }
table.data td { padding: 9px 14px; border-bottom: 1px solid #f1f5f9; color: #334155; }
table.data tr:last-child td { border-bottom: none; }

.report-iframe { width: 100%; height: 520px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; }
.msg-box { white-space: pre-wrap; font-size: 12px; color: #334155; font-family: ui-monospace, Menlo, monospace; margin: 0; }
.plugin-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.plugin-card { background: #fff; border: 1px solid #eef1f5; border-radius: 12px; padding: 16px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.plugin-card:hover { border-color: #cbd5e1; }
.plugin-card.sel { border-color: #2563eb; box-shadow: 0 0 0 3px rgba(37,99,235,.1); }
.plugin-card h4 { font-size: 14px; color: #1f2937; display: flex; align-items: center; justify-content: space-between; }
.plugin-card p { font-size: 12px; color: #64748b; margin: 6px 0 0; }
.plugin-card p.desc { margin-top: 6px; color: #94a3b8; line-height: 1.5; }
.flow-strip { display: grid; grid-template-columns: repeat(3, 1fr); background: #fff; border: 1px solid #eef1f5; border-radius: 12px; overflow: hidden; }
.flow-step { padding: 14px 18px; display: flex; gap: 12px; align-items: flex-start; border-right: 1px solid #f1f5f9; }
.flow-step:last-child { border-right: none; }
.flow-step .no { width: 24px; height: 24px; border-radius: 50%; background: #f1f5f9; color: #475569; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; flex-shrink: 0; }
.flow-step.cur .no { background: #2563eb; color: #fff; }
.flow-step.done .no { background: #16a34a; color: #fff; }
.flow-step b { font-size: 13px; color: #1f2937; display: block; }
.flow-step small { font-size: 11.5px; color: #64748b; display: block; margin-top: 2px; }

/* 参数表单 */
.param-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px 18px; margin-bottom: 16px; }
.param { display: flex; flex-direction: column; gap: 6px; }
.param .pl { font-size: 12px; color: #475569; font-weight: 600; }
.param .req { color: #dc2626; font-style: normal; margin-left: 3px; }
.param input[type=text], .param input[type=number], .param select { border: 1px solid #cbd5e1; border-radius: 8px; padding: 7px 10px; font-size: 13px; color: #1f2937; background: #fff; width: 100%; box-sizing: border-box; }
.param input:focus, .param select:focus { border-color: #2563eb; }
.param .path { display: flex; gap: 8px; align-items: center; }
.param .path input { flex: 1; }
.param .bool { display: flex; align-items: center; gap: 8px; }
.param .bool em { font-size: 12px; color: #64748b; font-style: normal; }
.param .ph { font-size: 11px; color: #94a3b8; }
.switch { position: relative; width: 40px; height: 22px; border-radius: 11px; background: #cbd5e1; border: none; transition: background .2s; flex-shrink: 0; cursor: pointer; }
.switch::after { content: ''; position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: #fff; transition: left .2s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.switch.on { background: #16a34a; } .switch.on::after { left: 21px; }
.cmd-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 4px; }
.cmd-tab { border: 1px solid #d7dde5; background: #fff; color: #33404d; border-radius: 999px; padding: 5px 14px; font-size: 12.5px; cursor: pointer; }
.cmd-tab:hover { border-color: #2563eb; color: #2563eb; }
.cmd-tab.on { background: #2563eb; border-color: #2563eb; color: #fff; }
.cmd-preview { border-top: 1px solid #eef1f5; padding-top: 12px; }
.cp-head { font-size: 12px; color: #475569; font-weight: 600; margin-bottom: 8px; }
.sub { font-size: 12px; color: #64748b; margin: 4px 0 14px; }
.fold-btn { font-size: 12px; color: #6b7280; }
.conn-bar { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; margin: 10px 0 4px; padding: 12px; border: 1px solid var(--line, #e5e7eb); border-radius: 8px; background: #f9fafb; }
.mount-tip { flex: 1 1 100%; font-size: 12px; }
.mount-tip summary { cursor: pointer; color: #6b7280; }
.mount-tip[open] summary { margin-bottom: 2px; }
/* 路径校验徽标：绿=可用 / 黄=有隐患 / 红=不可用 */
.pb { display: inline-block; margin-left: 8px; padding: 1px 8px; border-radius: 999px; font-size: 11px; font-weight: 500; font-style: normal; }
.pb-green { background: #ecfdf5; color: #047857; border: 1px solid #a7f3d0; }
.pb-amber { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
.pb-red { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
/* 自动推导候选：zip 与构建产物目录一键切换 */
.cand-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 4px; }
.cand-chip { border: 1px solid #d7dde5; background: #fff; color: #33404d; border-radius: 999px; padding: 3px 10px; font-size: 11.5px; cursor: pointer; }
.cand-chip:hover { border-color: #2563eb; color: #2563eb; }
.cand-chip.on { background: #eff6ff; border-color: #2563eb; color: #1d4ed8; }
</style>
