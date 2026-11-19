<script setup lang="ts">
/**
 * 变更影响分析面板（源码结构版）
 *
 * 一句话原理：**改了哪些符号 → 沿调用/注入/类型引用图反向回溯 → Controller 接口 → 抓包用例**。
 * 与「影响分析」（impact 页签）互补：
 *   - 本页签不需要 exec / 覆盖率，只要新旧两份源码，冷启动即可用，结果是静态候选（需人工复核）
 *   - impact 页签靠覆盖率证据，更精确，但必须先按用例抓包 + dump exec
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import GitSourcePanel from './GitSourcePanel.vue'
import type { SourceImpactResult } from '../types/impact'
import type { GitCapability } from '../types/electron'

const props = defineProps<{ cap: GitCapability | null }>()
const emit = defineEmits<{ (e: 'recheck-git'): void }>()

const newSources = ref('')
const oldSources = ref('')
const maxDepth = ref(4)
const windowHours = ref(0)
const gitOpenKey = ref('')

const busy = ref(false)
const err = ref('')
const result = ref<SourceImpactResult | null>(null)
const stageText = ref('')
const stagePercent = ref(0)
const resultTab = ref<'apis' | 'cases' | 'changes'>('apis')

let offLog: (() => void) | undefined
onMounted(() => {
  offLog = window.electronAPI?.onCoverageSourceImpactLog?.((p) => {
    stageText.value = p.text
    stagePercent.value = p.percent ?? 0
  })
})
onUnmounted(() => offLog?.())

const since = computed(() => (windowHours.value > 0 ? Date.now() - windowHours.value * 3600_000 : undefined))

async function pickDir(target: 'newSources' | 'oldSources') {
  const r = await window.electronAPI?.coveragePickPath({ pick: 'dir', title: '选择源码根目录' })
  if (r?.success && r.path) {
    if (target === 'newSources') newSources.value = r.path
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
  if (!newSources.value.trim() || !oldSources.value.trim()) {
    err.value = '请选择新版源码根目录和旧版源码根目录'
    return
  }
  busy.value = true
  stageText.value = '准备中…'
  stagePercent.value = 0
  result.value = null
  try {
    const r = await window.electronAPI?.coverageSourceImpactAnalyze?.({
      newSourceRoot: newSources.value.trim(),
      oldSourceRoot: oldSources.value.trim(),
      maxDepth: maxDepth.value,
      since: since.value
    })
    if (!r) throw new Error('主进程未返回分析结果')
    result.value = r
    if (!r.success) err.value = r.error || '分析失败'
  } catch (e) {
    err.value = '分析异常：' + ((e as Error)?.message || String(e))
  } finally {
    busy.value = false
  }
}

function pct(v: number) { return (v * 100).toFixed(0) + '%' }
function edgeLabel(t: string) {
  return ({ CALLS: '调用', INJECTS: '注入', READS: '类型引用', EXTENDS: '继承' } as Record<string, string>)[t] || t
}
</script>

<template>
  <div class="sia">
    <div class="card">
      <div class="card-head">
        <h3>变更影响分析 <span class="tag tag-blue">Source Impact</span></h3>
        <span class="muted sia-note">改了哪些符号 → 反向回溯 → Controller 接口 → 抓包用例</span>
      </div>
      <div class="sub">
        只需要 <b>新旧两份源码</b>（可用 Git 切换版本），无需 exec / 覆盖率；
        系统解析调用与注入关系，找出受影响的 Spring 接口，再用抓包记录反查访问过该接口的用例。
        结果是<b>静态候选</b>，建议人工复核。
      </div>

      <div class="sia-form">
        <label class="sia-field">
          <span class="sia-lb">新版源码根<i class="req">*</i></span>
          <span class="sia-ctl">
            <input v-model="newSources" type="text" placeholder="检出后的新版源码目录" />
            <button type="button" class="btn btn-ghost" @click="pickDir('newSources')">选目录</button>
            <button type="button" class="btn btn-ghost" @click="toggleGit('newSources')">Git</button>
          </span>
          <GitSourcePanel
            v-if="gitOpenKey === 'newSources'"
            param-key="newSources"
            target="new"
            :cap="props.cap"
            :current-value="newSources"
            @apply="onGitApply('newSources', $event)"
            @recheck="emit('recheck-git')"
          />
        </label>

        <label class="sia-field">
          <span class="sia-lb">旧版源码根<i class="req">*</i></span>
          <span class="sia-ctl">
            <input v-model="oldSources" type="text" placeholder="基线版本源码目录" />
            <button type="button" class="btn btn-ghost" @click="pickDir('oldSources')">选目录</button>
            <button type="button" class="btn btn-ghost" @click="toggleGit('oldSources')">Git</button>
          </span>
          <GitSourcePanel
            v-if="gitOpenKey === 'oldSources'"
            param-key="oldSources"
            target="old"
            :cap="props.cap"
            :current-value="oldSources"
            @apply="onGitApply('oldSources', $event)"
            @recheck="emit('recheck-git')"
          />
        </label>

        <label class="sia-field">
          <span class="sia-lb">回溯深度</span>
          <span class="sia-ctl"><input v-model.number="maxDepth" type="number" min="1" max="8" /></span>
        </label>
        <label class="sia-field">
          <span class="sia-lb">流量时间窗</span>
          <span class="sia-ctl">
            <select v-model.number="windowHours">
              <option :value="0">全部</option>
              <option :value="24">最近 24 小时</option>
              <option :value="72">最近 3 天</option>
              <option :value="168">最近 7 天</option>
            </select>
          </span>
        </label>
      </div>

      <div class="sia-actions">
        <button type="button" class="btn btn-primary" :disabled="busy" @click="analyze">
          {{ busy ? '分析中…' : '开始分析' }}
        </button>
        <span v-if="busy" class="muted">{{ stageText }} {{ stagePercent }}%</span>
      </div>
      <div v-if="err" class="err-box">{{ err }}</div>
    </div>

    <div v-if="result && result.success" class="card">
      <div class="card-head"><h3>分析结果</h3></div>
      <div class="sia-metrics">
        <div class="sia-m"><span class="sia-mv">{{ result.summary.changedSymbols }}</span><span class="sia-ml">变更符号</span></div>
        <div class="sia-m"><span class="sia-mv">{{ result.summary.affectedEndpoints }}</span><span class="sia-ml">受影响接口</span></div>
        <div class="sia-m"><span class="sia-mv">{{ result.summary.affectedCases }}</span><span class="sia-ml">关联用例</span></div>
        <div class="sia-m"><span class="sia-mv">{{ result.summary.unmatchedEndpoints }}</span><span class="sia-ml">无流量命中</span></div>
      </div>
      <div v-for="w in result.warnings" :key="w" class="warn-box">⚠ {{ w }}</div>

      <div class="sia-tabs">
        <button class="sia-tab" :class="{ on: resultTab === 'apis' }" @click="resultTab = 'apis'">受影响接口 · {{ result.affected.length }}</button>
        <button class="sia-tab" :class="{ on: resultTab === 'cases' }" @click="resultTab = 'cases'">关联用例 · {{ result.cases.length }}</button>
        <button class="sia-tab" :class="{ on: resultTab === 'changes' }" @click="resultTab = 'changes'">变更符号 · {{ result.changes.length }}</button>
      </div>

      <div v-if="resultTab === 'apis'" class="sia-table-wrap">
        <table class="sia-table">
          <thead><tr><th>接口</th><th style="width:80px">置信度</th><th>关联用例</th><th>影响路径</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in result.affected" :key="i">
              <td><code>{{ a.endpoint.httpMethod }} {{ a.endpoint.path }}</code><div class="muted tiny">{{ a.endpoint.locator }}</div></td>
              <td>{{ pct(a.confidence) }}</td>
              <td>
                <span v-for="c in a.cases" :key="c" class="pill">{{ c }}</span>
                <span v-if="!a.cases.length" class="muted">无抓包命中</span>
              </td>
              <td><code class="tiny">{{ a.edgeTypes.map(edgeLabel).join(' → ') }}</code></td>
            </tr>
            <tr v-if="!result.affected.length"><td colspan="4" class="muted">未发现受影响的 Controller 接口</td></tr>
          </tbody>
        </table>
      </div>

      <div v-else-if="resultTab === 'cases'" class="sia-table-wrap">
        <table class="sia-table">
          <thead><tr><th style="width:50px">序</th><th>用例</th><th>命中接口</th></tr></thead>
          <tbody>
            <tr v-for="c in result.cases" :key="c.caseName">
              <td>{{ c.order }}</td>
              <td>{{ c.caseName }}</td>
              <td><code class="tiny">{{ c.endpoints.join(', ') }}</code></td>
            </tr>
            <tr v-if="!result.cases.length"><td colspan="3" class="muted">没有用例命中受影响的接口 —— 这些接口可能缺少历史抓包</td></tr>
          </tbody>
        </table>
      </div>

      <div v-else class="sia-table-wrap">
        <table class="sia-table">
          <thead><tr><th>符号</th><th style="width:90px">类型</th><th style="width:100px">变更</th><th>原因</th></tr></thead>
          <tbody>
            <tr v-for="(c, i) in result.changes" :key="i">
              <td><code class="tiny">{{ c.qualifiedName }}</code></td>
              <td>{{ c.kind }}</td>
              <td>{{ c.changeType }}</td>
              <td>{{ c.reason }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sia { display: flex; flex-direction: column; gap: 12px; }
.sia-note { font-size: 12px; }
.sub { font-size: 12px; color: #64748b; line-height: 1.7; margin: 6px 0 12px; }
.sia-form { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px 18px; }
.sia-field { display: flex; flex-direction: column; gap: 4px; }
.sia-lb { font-size: 12px; font-weight: 500; color: #334155; }
.req { color: #e11d48; font-style: normal; margin-left: 2px; }
.sia-ctl { display: flex; gap: 6px; align-items: center; }
.sia-ctl input, .sia-ctl select { flex: 1; min-width: 0; border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 8px; font-size: 12px; }
.sia-actions { display: flex; gap: 12px; align-items: center; margin-top: 14px; }
.sia-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 10px 0 14px; }
.sia-m { display: flex; flex-direction: column; align-items: center; background: #f8fafc; border: 1px solid #eef1f5; border-radius: 10px; padding: 10px; }
.sia-mv { font-size: 20px; font-weight: 500; color: #2563eb; }
.sia-ml { font-size: 11px; color: #64748b; }
.sia-tabs { display: flex; gap: 6px; margin: 8px 0; }
.sia-tab { border: 1px solid #d1d5db; background: #fff; border-radius: 7px; padding: 4px 12px; font-size: 12px; cursor: pointer; }
.sia-tab.on { background: #2563eb; border-color: #2563eb; color: #fff; }
.sia-table-wrap { overflow: auto; max-height: 460px; }
.sia-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.sia-table th, .sia-table td { border-bottom: 1px solid #eef1f5; padding: 8px 10px; text-align: left; vertical-align: top; }
.sia-table th { color: #475569; font-weight: 500; background: #f8fafc; position: sticky; top: 0; }
.tiny { font-size: 11px; }
.pill { display: inline-block; background: #e0f2fe; color: #075985; border-radius: 10px; padding: 1px 8px; margin: 0 4px 4px 0; font-size: 11px; }
.err-box, .warn-box { font-size: 12px; border-radius: 8px; padding: 8px 12px; margin-top: 8px; }
.err-box { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
.warn-box { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
</style>
