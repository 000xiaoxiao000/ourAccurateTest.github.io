<script setup lang="ts">
/**
 * 影响分析面板（P0）
 *
 * 一句话原理：**「改了哪行」∩「谁跑过这行」= 受影响的接口与用例。**
 *   改了哪行 → incremental 差异明细；谁跑过 → report --perkey 每个 key 一份行覆盖；
 *   key 是谁 → 抓包时注入的 X-Coverage-Key（流量库里反查到接口与用例）。
 *
 * 它不是一个 CLI 指令，而是 incremental + perkey + 流量反查的组合，
 * 所以结果形态是表格而不是命令行输出 —— 这也是它独立成页签的原因。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import GitSourcePanel from './GitSourcePanel.vue'
import type { ImpactResult } from '../types/impact'
import type { GitCapability } from '../types/electron'

const props = defineProps<{
  /** 新版 classfiles（解码 exec 必需，来自共享覆盖率配置） */
  classfilesPath: string
  /** 默认 exec 目录（已抓取的 exec 所在目录） */
  defaultExecDir: string
  cap: GitCapability | null
}>()
const emit = defineEmits<{ (e: 'recheck-git'): void }>()

const execDir = ref(props.defaultExecDir || '')
const classfiles = ref(props.classfilesPath || '')
const oldClassfiles = ref('')
const newSources = ref('')
const oldSources = ref('')
/** 时间窗：0 = 全部 */
const windowHours = ref(0)

const busy = ref(false)
const err = ref('')
const result = ref<ImpactResult | null>(null)
const stageText = ref('')
const stagePercent = ref(0)
const gitOpenKey = ref('')
/** 结果区当前展开的表：apis / cases / minimal */
const resultTab = ref<'apis' | 'cases' | 'minimal'>('apis')

let offLog: (() => void) | undefined
onMounted(() => {
  offLog = window.electronAPI?.onCoverageImpactLog?.((p) => {
    stageText.value = p.text
    stagePercent.value = p.percent ?? 0
  })
})
onUnmounted(() => offLog?.())

const baselineMode = computed(() => (oldClassfiles.value.trim() ? 'class' : newSources.value.trim() && oldSources.value.trim() ? 'source' : 'none'))

/** 必测接口空表的原因提示：按流量侧自检结果区分，不再笼统说「时间窗」 */
const apisEmptyHint = computed(() => {
  if (!result.value) return ''
  const s = result.value.summary
  if (!s.trafficTotal) return '时间窗内没有抓包记录 —— 放宽时间窗，或确认抓包记录没被清空'
  if (!s.trafficKeyed) return '抓包记录全部没有归属 key（见上方红色提示）—— 开启覆盖率采集后重新抓包 + dump'
  return '没有接口跑到变更行 —— 检查时间窗是否与 dump 对齐'
})

async function pickDir(target: 'execDir' | 'classfiles' | 'oldClassfiles' | 'newSources' | 'oldSources') {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择目录' })
  if (r?.success && r.path) {
    if (target === 'execDir') execDir.value = r.path
    else if (target === 'classfiles') classfiles.value = r.path
    else if (target === 'oldClassfiles') oldClassfiles.value = r.path
    else if (target === 'newSources') newSources.value = r.path
    else oldSources.value = r.path
  }
}

function toggleGit(key: string) {
  gitOpenKey.value = gitOpenKey.value === key ? '' : key
}

function onGitApply(key: string, payload: { newSourceRoot?: string; oldSourceRoot?: string }) {
  if (key === 'newSources') newSources.value = payload.newSourceRoot ?? newSources.value
  else oldSources.value = payload.oldSourceRoot ?? payload.newSourceRoot ?? oldSources.value
  gitOpenKey.value = ''
}

async function analyze() {
  err.value = ''
  if (!execDir.value.trim()) { err.value = '请先选择 exec 目录'; return }
  if (!classfiles.value.trim()) { err.value = '缺少新版 classfiles（解码 exec 必需），请到上方覆盖率配置里填写'; return }
  if (baselineMode.value === 'none') {
    err.value = '缺少比对基准：填「旧版 classfiles」，或同时填「新版源码 + 旧版源码」（二选一）'
    return
  }
  busy.value = true
  stageText.value = '准备中…'
  stagePercent.value = 0
  result.value = null
  try {
    const r = await window.electronAPI?.coverageImpactAnalyze({
      execDir: execDir.value.trim(),
      classfiles: classfiles.value.trim(),
      oldClassfiles: oldClassfiles.value.trim() || undefined,
      newSources: newSources.value.trim() || undefined,
      oldSources: oldSources.value.trim() || undefined,
      since: windowHours.value > 0 ? Date.now() - windowHours.value * 3600 * 1000 : 0
    })
    if (!r?.success) err.value = r?.error ?? '分析失败'
    else result.value = r
  } catch (e: any) {
    err.value = String(e?.message ?? e)
  } finally {
    busy.value = false
    stageText.value = ''
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

const copiedCmd = ref(false)
const copiedSet = ref(false)
async function copyCommands() {
  const ok = await copyText((result.value?.commands ?? []).join('\n'))
  copiedCmd.value = ok
  setTimeout(() => (copiedCmd.value = false), 1500)
}
async function copyMinimalSet() {
  const ok = await copyText((result.value?.minimalSet ?? []).join('\n'))
  copiedSet.value = ok
  setTimeout(() => (copiedSet.value = false), 1500)
}

function pct(n: number): string {
  return Math.round(n * 100) + '%'
}
/** URL 可能很长，取 pathname 之后的部分展示 */
function shortUrl(u: string): string {
  try {
    const url = new URL(u)
    return url.pathname + (url.search ? url.search.slice(0, 20) : '')
  } catch {
    return u.length > 60 ? '…' + u.slice(-57) : u
  }
}
</script>

<template>
  <div class="ia">
    <!-- ① 输入条：只让用户填必要的三样，其余从共享配置带出 -->
    <div class="card">
      <div class="card-head">
        <h3>影响分析 <span class="tag tag-blue">Impact Analysis</span></h3>
        <span class="muted ia-note">改了哪行 ∩ 谁跑过这行 = 受影响的接口与用例</span>
      </div>
      <div class="sub">
        只需要指定 <b>exec 目录</b> 和 <b>比对基准</b>（旧版 classfiles 或两版源码，二选一）；
        classfiles 从上方覆盖率配置带出，归属 Key 由抓包时注入的 <code>X-Coverage-Key</code> 自动反查。
      </div>

      <div class="ia-form">
        <label class="ia-field">
          <span class="ia-lb">exec 目录<i class="req">*</i></span>
          <span class="ia-ctl">
            <input v-model="execDir" placeholder="/…/oat-coverage/execs（存放 .exec 的目录）" />
            <button class="btn btn-outline btn-sm" @click="pickDir('execDir')">选择</button>
          </span>
        </label>
        <label class="ia-field">
          <span class="ia-lb">新版 classfiles<i class="req">*</i></span>
          <span class="ia-ctl">
            <input v-model="classfiles" placeholder="解码 exec 必需；多模块用 ; 分隔" />
            <button class="btn btn-outline btn-sm" @click="pickDir('classfiles')">选择</button>
          </span>
        </label>
        <label class="ia-field">
          <span class="ia-lb">时间窗</span>
          <span class="ia-ctl">
            <select v-model.number="windowHours">
              <option :value="0">全部流量</option>
              <option :value="24">最近 24 小时</option>
              <option :value="6">最近 6 小时</option>
              <option :value="1">最近 1 小时</option>
            </select>
            <small class="ia-hint">过滤抓包记录；若提示「key 查不到」，放宽这个范围</small>
          </span>
        </label>
      </div>

      <!-- 比对基准：两种给法二选一 -->
      <div class="ia-base">
        <div class="ia-base-h">比对基准（二选一）</div>
        <div class="ia-form">
          <label class="ia-field">
            <span class="ia-lb">① 旧版 classfiles</span>
            <span class="ia-ctl">
              <input v-model="oldClassfiles" placeholder="/data/builds/0001/classes 或 old.zip" @input="newSources = ''; oldSources = ''" />
              <button class="btn btn-outline btn-sm" @click="pickDir('oldClassfiles')">选择</button>
            </span>
          </label>
          <label class="ia-field">
            <span class="ia-lb">② 新版源码</span>
            <span class="ia-ctl">
              <input v-model="newSources" placeholder="精确到 src/main/java（多模块用 ; 分隔）" />
              <button class="btn btn-outline btn-sm" @click="pickDir('newSources')">选择</button>
              <button class="btn btn-outline btn-sm" @click="toggleGit('newSources')">{{ gitOpenKey === 'newSources' ? '收起 ▲' : 'Git 拉取' }}</button>
            </span>
          </label>
          <label class="ia-field">
            <span class="ia-lb">② 旧版源码</span>
            <span class="ia-ctl">
              <input v-model="oldSources" placeholder="与上方新版源码同时给才生效" />
              <button class="btn btn-outline btn-sm" @click="pickDir('oldSources')">选择</button>
              <button class="btn btn-outline btn-sm" @click="toggleGit('oldSources')">{{ gitOpenKey === 'oldSources' ? '收起 ▲' : 'Git 拉取' }}</button>
            </span>
          </label>
        </div>
        <div v-if="gitOpenKey === 'newSources'" class="ia-git">
          <GitSourcePanel param-key="newSources" target="new" :cap="cap" :current-value="newSources" @apply="onGitApply('newSources', $event)" @recheck="emit('recheck-git')" />
        </div>
        <div v-if="gitOpenKey === 'oldSources'" class="ia-git">
          <GitSourcePanel param-key="oldSources" target="old" :cap="cap" :current-value="oldSources" @apply="onGitApply('oldSources', $event)" @recheck="emit('recheck-git')" />
        </div>
        <p class="ia-tip">
          ① 用两版字节码比对（推荐，不需要源码）；② 用两版源码文本比对（同样行级，还能识别注释/格式改动）。两者都不给无法出结果。
        </p>
      </div>

      <div class="ia-actions">
        <button class="btn btn-primary btn-sm" :disabled="busy" @click="analyze">
          {{ busy ? '分析中…' : '开始分析' }}
        </button>
        <span v-if="busy && stageText" class="ia-prog">
          <span class="ia-bar"><i :style="{ width: stagePercent + '%' }"></i></span>
          {{ stageText }}
        </span>
      </div>
      <p v-if="err" class="ia-err">✗ {{ err }}</p>
    </div>

    <!-- ② 空状态引导 -->
    <div v-if="!result && !busy" class="card ia-empty">
      <h3>先确认这三点，结果才准</h3>
      <ol>
        <li><b>key 是用例级</b>：<code>X-Coverage-Key</code> 填用例 ID（或用 <code>cli setkey --key &lt;用例ID&gt;</code> 进程外切换）。若是每次请求随机的 uuid，结果会碎片化成「谁访问过」，答不了「哪个用例该回归」。</li>
        <li><b>时间窗对齐</b>：<code>dump --reset</code> 后的 exec 只含本轮流量，这里的时间窗要覆盖同一区间，否则会提示「key 查不到」。</li>
        <li><b>比对基准选对版本</b>：旧版 classfiles 应该是「改动前那次构建」的产物。</li>
      </ol>
      <p class="ia-tip">新增的类 / 方法必然落在「未覆盖风险」里（没有历史执行记录），这是正确行为 —— 它们需要补测。</p>
    </div>

    <!-- ③ 结果 -->
    <template v-if="result">
      <!-- 指标卡：先给结论 -->
      <div class="ia-metrics">
        <div class="ia-m"><span class="ia-mv">{{ result.summary.changedLines }}</span><span class="ia-ml">变更行</span><small>{{ result.summary.changedClasses }} 个类</small></div>
        <div class="ia-m"><span class="ia-mv">{{ result.summary.affectedApis }}</span><span class="ia-ml">受影响接口</span><small>{{ result.summary.keysHit }}/{{ result.summary.keysTotal }} 个 key 命中</small></div>
        <div class="ia-m"><span class="ia-mv">{{ result.summary.affectedCases }}</span><span class="ia-ml">受影响用例</span><small>最小回归集 {{ result.minimalSet.length }} 个</small></div>
        <div class="ia-m" :class="{ danger: result.summary.uncoveredChanged > 0 }">
          <span class="ia-mv">{{ result.summary.uncoveredChanged }}</span><span class="ia-ml">未覆盖（需补测）</span><small>没有任何用例跑到</small>
        </div>
      </div>

      <!-- 流量侧自检：抓包记录里没有归属 key 时，接口/用例必然为 0，必须醒目指出 -->
      <div v-if="result.success && !result.apis.length && !result.summary.trafficKeyed" class="card ia-zerotraffic">
        <b>抓包记录里没有任何带 X-Coverage-Key 的请求</b>
        <p>
          本次时间窗内共 {{ result.summary.trafficTotal }} 条抓包记录、全部没有归属 key ——
          抓包时「采集工作台 → 覆盖率采集」开关没开或 key 为空，代理不会注入请求头，所以反查不到接口与用例。
          请开启覆盖率采集、填写 key 后重新抓包，再 dump 一次 exec（旧 exec 是之前的 key，对不上）。
        </p>
      </div>

      <!-- 结果表 -->
      <div class="card">
        <div class="card-head">
          <h3>受影响清单</h3>
          <div class="ia-rtabs">
            <button class="ia-rtab" :class="{ on: resultTab === 'apis' }" @click="resultTab = 'apis'">必测接口 · {{ result.apis.length }}</button>
            <button class="ia-rtab" :class="{ on: resultTab === 'cases' }" @click="resultTab = 'cases'">必测用例 · {{ result.cases.length }}</button>
            <button class="ia-rtab" :class="{ on: resultTab === 'minimal' }" @click="resultTab = 'minimal'">最小回归集 · {{ result.minimalSet.length }}</button>
          </div>
        </div>

        <!-- 必测接口 -->
        <table v-if="resultTab === 'apis'" class="ia-table">
          <thead><tr><th style="width:78px">协议</th><th>接口</th><th style="width:90px">命中行</th><th>关联用例</th><th style="width:180px">证据（类:行）</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in result.apis" :key="i">
              <td><span class="ia-chip">{{ a.protocol }}</span></td>
              <td class="mono"><b>{{ a.method }}</b> {{ shortUrl(a.url) }}</td>
              <td>{{ a.hits }}</td>
              <td>{{ a.cases.length ? a.cases.join('、') : '（未命名会话）' }}</td>
              <td class="mono ia-ev">{{ a.evidence.join(' ') }}</td>
            </tr>
            <tr v-if="!result.apis.length"><td colspan="5" class="ia-none">{{ apisEmptyHint }}</td></tr>
          </tbody>
        </table>

        <!-- 必测用例 -->
        <table v-else-if="resultTab === 'cases'" class="ia-table">
          <thead><tr><th style="width:52px">顺序</th><th>用例</th><th style="width:110px">命中 / 占比</th><th>key</th></tr></thead>
          <tbody>
            <tr v-for="c in result.cases" :key="c.caseName">
              <td>{{ c.order }}</td>
              <td><b>{{ c.caseName }}</b></td>
              <td>{{ c.hits }} 行 · {{ pct(c.coveredRatio) }}</td>
              <td class="mono">{{ c.keys.join(', ') }}</td>
            </tr>
            <tr v-if="!result.cases.length"><td colspan="4" class="ia-none">没有带 key 的流量命中变更行</td></tr>
          </tbody>
        </table>

        <!-- 最小回归集 -->
        <div v-else class="ia-minimal">
          <p class="sub">
            贪心集合覆盖算出的最小执行序列：<b>{{ result.minimalStats.cases }}</b> 个用例即可覆盖
            <b>{{ result.minimalStats.lines }}/{{ result.minimalStats.totalLines }}</b> 行变更（用最少的用例盖住全部变更行，避免回归集膨胀）。
          </p>
          <ol class="ia-setlist">
            <li v-for="(c, i) in result.minimalSet" :key="i">{{ c }}</li>
          </ol>
          <button class="btn btn-outline btn-sm" :disabled="!result.minimalSet.length" @click="copyMinimalSet">
            {{ copiedSet ? '已复制 ✓' : '复制清单' }}
          </button>
        </div>
      </div>

      <!-- ④ 风险卡：置底且显眼 -->
      <div v-if="result.risks.length" class="card ia-risk">
        <div class="card-head">
          <h3>⚠ {{ result.summary.uncoveredChanged }} 处变更没有任何用例跑到 → 需补测</h3>
        </div>
        <div v-for="(r, i) in result.risks" :key="i" class="ia-riskitem">
          <div class="mono"><b>{{ r.cls }}</b><span v-if="r.source" class="muted"> （{{ r.source }}）</span></div>
          <div class="muted">行 {{ r.lines.join(', ') }}<template v-if="r.methods.length"> · 方法 {{ r.methods.join(', ') }}</template></div>
        </div>
      </div>

      <!-- ⑤ 提示 / 命令 -->
      <div class="card">
        <div class="card-head">
          <h3>真实命令（可复制到 CI）</h3>
          <button class="btn btn-outline btn-sm" @click="copyCommands">{{ copiedCmd ? '已复制 ✓' : '复制' }}</button>
        </div>
        <pre class="ia-cmd">{{ (result.commands ?? []).join('\n') }}</pre>
        <p v-if="result.keyHint" class="ia-warn">⚠ {{ result.keyHint }}</p>
        <p v-for="(w, i) in result.warnings" :key="i" class="ia-warn">⚠ {{ w }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ia { display: flex; flex-direction: column; gap: 12px; }
.ia-note { font-size: 12px; margin-left: 8px; }
.ia-form { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.ia-field { display: flex; align-items: flex-start; gap: 10px; }
.ia-lb { width: 128px; flex: none; font-size: 12px; color: var(--text-soft, #6b7280); padding-top: 6px; }
.ia-ctl { flex: 1; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ia-ctl input, .ia-ctl select { flex: 1; min-width: 220px; }
.ia-hint { flex: 1 0 100%; font-size: 12px; color: var(--text-soft, #6b7280); }
.req { color: #dc2626; font-style: normal; margin-left: 2px; }
.ia-base { margin-top: 12px; border: 1px dashed var(--border, #d1d5db); border-radius: 8px; padding: 10px 12px; }
.ia-base-h { font-size: 12px; font-weight: 600; margin-bottom: 4px; }
.ia-git { margin-top: 8px; }
.ia-tip { font-size: 12px; color: var(--text-soft, #6b7280); line-height: 1.7; margin: 6px 0 0; }
.ia-actions { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.ia-prog { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-soft, #6b7280); }
.ia-bar { display: inline-block; width: 140px; height: 5px; border-radius: 3px; background: #e5e7eb; overflow: hidden; }
.ia-bar i { display: block; height: 100%; background: #2563eb; transition: width .2s; }
.ia-err { font-size: 12px; color: #b91c1c; margin: 8px 0 0; }
.ia-warn { font-size: 12px; color: #b45309; line-height: 1.7; margin: 6px 0 0; }
.ia-zerotraffic { border: 1px solid #fca5a5; background: #fef2f2; border-radius: 8px; padding: 12px 16px; margin: 12px 0; font-size: 13px; color: #b91c1c; }
.ia-zerotraffic p { margin: 6px 0 0; line-height: 1.7; color: #7f1d1d; }
.ia-empty { font-size: 13px; line-height: 1.8; }
.ia-empty ol { margin: 6px 0 0; padding-left: 20px; }
.ia-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.ia-m { background: var(--bg-soft, #f8fafc); border: 1px solid var(--border, #e5e7eb); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; }
.ia-mv { font-size: 24px; font-weight: 600; line-height: 1.2; }
.ia-ml { font-size: 12px; color: var(--text-soft, #6b7280); }
.ia-m small { font-size: 11px; color: var(--text-soft, #9ca3af); margin-top: 2px; }
.ia-m.danger { border-color: #fca5a5; background: #fef2f2; }
.ia-m.danger .ia-mv { color: #b91c1c; }
.ia-rtabs { display: flex; gap: 6px; }
.ia-rtab { border: 1px solid var(--border, #d1d5db); background: #fff; border-radius: 6px; padding: 3px 10px; font-size: 12px; cursor: pointer; }
.ia-rtab.on { background: #2563eb; color: #fff; border-color: #2563eb; }
.ia-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ia-table th { text-align: left; font-weight: 500; color: var(--text-soft, #6b7280); border-bottom: 1px solid var(--border, #e5e7eb); padding: 6px 8px; }
.ia-table td { padding: 6px 8px; border-bottom: 1px solid var(--border, #f1f5f9); vertical-align: top; }
.ia-chip { display: inline-block; padding: 1px 6px; border-radius: 5px; background: #eef2ff; color: #4338ca; font-size: 11px; }
.ia-ev { color: var(--text-soft, #6b7280); font-size: 11px; word-break: break-all; }
.ia-none { text-align: center; color: var(--text-soft, #9ca3af); padding: 18px 0; }
.ia-setlist { margin: 6px 0 10px; padding-left: 22px; font-size: 13px; line-height: 1.9; }
.ia-risk { border-color: #fca5a5; }
.ia-risk h3 { color: #b91c1c; }
.ia-riskitem { padding: 6px 0; border-top: 1px solid #fee2e2; font-size: 12px; }
.ia-riskitem:first-of-type { border-top: none; }
.ia-cmd { background: #17202b; color: #e5edf6; border-radius: 10px; padding: 10px 12px; font-family: ui-monospace, Menlo, monospace; font-size: 11px; line-height: 1.7; overflow-x: auto; white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
