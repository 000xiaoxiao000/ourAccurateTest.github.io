<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 覆盖与执行</div>
        <h1>覆盖度 &amp; 执行证据</h1>
        <p class="subtext">多维度覆盖率指标：用例覆盖、实现覆盖、执行有效覆盖、运行时覆盖和闭环率。</p>
      </div>
      <div class="header-actions">
        <button type="button" class="secondary-button" :disabled="loading" @click="load">刷新</button>
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>
    <div v-if="loading" class="loading-state">加载覆盖数据中...</div>

    <template v-else-if="metrics">
      <div class="metrics-grid">
        <article class="metric-card">
          <span class="metric-label" title="有多少验收标准找到了对应测试用例">用例覆盖率</span>
          <strong class="metric-value" :class="rateClass(metrics.testcaseCoverageRate)">{{ pct(metrics.testcaseCoverageRate) }}</strong>
          <div class="metric-bar"><div class="metric-fill" :style="{ width: pct(metrics.testcaseCoverageRate), '--color': rateColor(metrics.testcaseCoverageRate) }"></div></div>
          <small>{{ metrics.coveredCriteria }} / {{ metrics.totalCriteria }} 条 AC 有用例</small>
        </article>

        <article class="metric-card">
          <span class="metric-label" title="有多少验收标准在源码中找到了实现证据">实现覆盖率</span>
          <strong class="metric-value" :class="rateClass(metrics.implementationCoverageRate)">{{ pct(metrics.implementationCoverageRate) }}</strong>
          <div class="metric-bar"><div class="metric-fill" :style="{ width: pct(metrics.implementationCoverageRate), '--color': rateColor(metrics.implementationCoverageRate) }"></div></div>
          <small>{{ metrics.implementedCriteria }} / {{ metrics.totalCriteria }} 条 AC 有代码证据</small>
        </article>

        <article class="metric-card">
          <span class="metric-label" title="有多少验收标准有测试执行记录">执行证据覆盖</span>
          <strong class="metric-value" :class="rateClass(metrics.executionEvidenceRate)">{{ pct(metrics.executionEvidenceRate) }}</strong>
          <div class="metric-bar"><div class="metric-fill" :style="{ width: pct(metrics.executionEvidenceRate), '--color': rateColor(metrics.executionEvidenceRate) }"></div></div>
          <small>{{ metrics.executedCriteria }} / {{ metrics.totalCriteria }} 条 AC 有执行记录</small>
        </article>

        <article class="metric-card">
          <span class="metric-label" title="有多少验收标准有覆盖率证据（JaCoCo/Istanbul等）">运行时覆盖</span>
          <strong class="metric-value" :class="rateClass(metrics.runtimeCoverageRate)">{{ pct(metrics.runtimeCoverageRate) }}</strong>
          <div class="metric-bar"><div class="metric-fill" :style="{ width: pct(metrics.runtimeCoverageRate), '--color': rateColor(metrics.runtimeCoverageRate) }"></div></div>
          <small>{{ metrics.coveredByRuntimeCriteria }} / {{ metrics.totalCriteria }} 条 AC 有运行证据</small>
        </article>

        <article class="metric-card highlight">
          <span class="metric-label" title="同时有用例和代码实现证据的验收标准比例">闭环率</span>
          <strong class="metric-value" :class="rateClass(metrics.closedLoopRate)">{{ pct(metrics.closedLoopRate) }}</strong>
          <div class="metric-bar"><div class="metric-fill" :style="{ width: pct(metrics.closedLoopRate), '--color': rateColor(metrics.closedLoopRate) }"></div></div>
          <small>{{ metrics.closedLoopCriteria }} / {{ metrics.totalCriteria }} 条 AC 用例+实现双覆盖</small>
        </article>

        <article class="metric-card">
          <span class="metric-label">开放问题</span>
          <strong class="metric-value" :class="metrics.openFindings > 0 ? 'bad' : 'good'">{{ metrics.openFindings }}</strong>
          <small>待审核或已确认的 AI 发现</small>
        </article>
      </div>

      <section class="coverage-notice">
        <h3>覆盖口径说明</h3>
        <ul>
          <li><strong>静态一致（E2）</strong>：有需求、用例、代码三类证据，但无执行或覆盖率数据 → <code>STATICALLY_CONSISTENT</code></li>
          <li><strong>执行证据（E3）</strong>：有测试运行记录 → 可部分证明实际行为</li>
          <li><strong>已满足（E4）</strong>：有覆盖率证据 + 人工确认 → <code>SATISFIED</code></li>
          <li>只有语义相似不等于覆盖，AI 发现需人工确认才计入正式指标</li>
        </ul>
      </section>

      <section v-if="changeImpact" class="impact-section">
        <div class="section-head">
          <h2>变更影响分析</h2>
          <span>最近一次分析 · {{ formatTime(changeImpact.createTime) }}</span>
        </div>
        <div class="impact-grid">
          <div class="impact-stat">
            <strong>{{ changeImpact.impactedCriteria.length }}</strong>
            <span>受影响 AC</span>
          </div>
          <div class="impact-stat">
            <strong>{{ changeImpact.impactedTestcases.length }}</strong>
            <span>受影响测试用例</span>
          </div>
          <div class="impact-stat warn">
            <strong>{{ changeImpact.orphans.length }}</strong>
            <span>孤儿工件</span>
          </div>
        </div>

        <div v-if="changeImpact.orphans.length" class="orphan-list">
          <div class="orphan-head">孤儿工件 — 无追溯关系的资产，需人工处理</div>
          <article v-for="item in changeImpact.orphans" :key="item.itemId" class="orphan-item">
            <strong>{{ item.itemType }}</strong>
            <span>{{ item.title }}</span>
            <small>{{ item.description }}</small>
          </article>
        </div>
      </section>

      <div class="impact-actions">
        <button type="button" class="primary-button" :disabled="impactLoading" @click="runImpact">
          {{ impactLoading ? '分析中...' : '运行变更影响分析' }}
        </button>
      </div>
    </template>

    <div v-else-if="!loading" class="empty-state">
      <strong>该基线还没有覆盖数据</strong>
      <span>请先在 AI 验证工作区运行 AI 分析。</span>
      <RouterLink :to="`/p/${projectId}/verification`" class="primary-button">去运行分析</RouterLink>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchBaselineDetail, analyzeChangeImpact,
  type ChangeImpactReport, type VerificationMetrics,
} from '@/api/verification'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))

const metrics = ref<VerificationMetrics | null>(null)
const changeImpact = ref<ChangeImpactReport | null>(null)
const loading = ref(false)
const error = ref('')
const impactLoading = ref(false)

onMounted(load)

async function load() {
  if (!baselineId.value) return
  loading.value = true; error.value = ''
  try {
    const d = await fetchBaselineDetail(projectId.value, baselineId.value)
    metrics.value = d.metrics
  } catch (e) { error.value = msg(e) }
  finally { loading.value = false }
}

async function runImpact() {
  impactLoading.value = true
  try {
    changeImpact.value = await analyzeChangeImpact(projectId.value, baselineId.value)
    toast.success('变更影响分析完成')
  } catch (e) { toast.error(msg(e)) }
  finally { impactLoading.value = false }
}

function pct(v: number) { return `${Math.round((v || 0) * 1000) / 10}%` }
function rateClass(v: number) { return v >= 0.8 ? 'good' : v >= 0.5 ? 'warn' : 'bad' }
function rateColor(v: number) { return v >= 0.8 ? '#16a34a' : v >= 0.5 ? '#f59e0b' : '#dc2626' }
function formatTime(s?: string) { return s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '-' }
function msg(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
</script>

<style scoped>
.page-content { display: flex; flex-direction: column; gap: 18px; }
.page-header, .header-actions, .section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.secondary-button, .secondary-button-link, .primary-button { display:inline-flex;align-items:center;min-height:36px;border-radius:8px;padding:7px 14px;font-weight:800;font-size:14px;text-decoration:none;cursor:pointer;border:1px solid var(--oat-border);background:var(--oat-surface-soft);color:var(--oat-text); }
.primary-button { border-color:var(--oat-primary);background:var(--oat-primary);color:#fff; }
.metrics-grid { display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px; }
.metric-card { display:grid;gap:6px;padding:16px;border:1px solid var(--oat-border);border-radius:12px;background:#fff; }
.metric-card.highlight { border-color:rgba(var(--oat-primary-rgb),.3);background:rgba(var(--oat-primary-rgb),.04); }
.metric-label { font-size:12px;font-weight:800;color:var(--oat-text-muted);cursor:help;text-decoration:underline dotted; }
.metric-value { font-size:28px; }
.metric-value.good { color:#16a34a; }
.metric-value.warn { color:#f59e0b; }
.metric-value.bad { color:var(--oat-danger); }
.metric-card small { font-size:11px;color:var(--oat-text-muted); }
.metric-bar { height:6px;border-radius:999px;background:rgba(15,23,42,.08);overflow:hidden; }
.metric-fill { --color: var(--oat-primary);height:100%;border-radius:999px;background:var(--color);transition:width .3s ease; }
.coverage-notice { padding:14px 16px;border:1px solid var(--oat-border);border-radius:12px;background:var(--oat-surface-soft); }
.coverage-notice h3 { margin:0 0 8px;font-size:14px; }
.coverage-notice ul { margin:0;padding-left:18px;display:grid;gap:5px; }
.coverage-notice li { font-size:13px;color:var(--oat-text-secondary);line-height:1.6; }
.coverage-notice code { border-radius:4px;padding:1px 5px;background:rgba(var(--oat-primary-rgb),.1);font-size:11px; }
.section-head h2 { margin:0;font-size:16px; }
.section-head span { color:var(--oat-text-muted);font-size:12px; }
.impact-section { display:grid;gap:12px;padding:14px;border:1px solid var(--oat-border);border-radius:12px;background:#fff; }
.impact-grid { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px; }
.impact-stat { display:grid;gap:3px;padding:10px;border:1px solid var(--oat-border);border-radius:10px;background:var(--oat-surface-soft);text-align:center; }
.impact-stat strong { font-size:24px; }
.impact-stat span { font-size:12px;color:var(--oat-text-muted); }
.impact-stat.warn strong { color:#f59e0b; }
.orphan-list { display:grid;gap:6px; }
.orphan-head { font-size:12px;font-weight:800;color:var(--oat-text-secondary); }
.orphan-item { display:grid;grid-template-columns:60px 1fr;gap:4px 8px;padding:8px;border:1px solid var(--oat-border);border-radius:8px;background:rgba(245,158,11,.04);font-size:12px; }
.orphan-item strong { grid-row:1;color:var(--oat-text-muted); }
.orphan-item span { grid-row:1;font-weight:700; }
.orphan-item small { grid-column:1/-1;grid-row:2;color:var(--oat-text-muted); }
.impact-actions { display:flex;justify-content:flex-end; }
.notice.danger { border:1px solid rgba(220,38,38,.25);border-radius:10px;padding:10px 14px;color:var(--oat-danger);background:rgba(220,38,38,.06); }
.loading-state { padding:24px;text-align:center;color:var(--oat-text-muted); }
.empty-state { display:grid;gap:8px;min-height:160px;place-content:center;text-align:center;border:1px dashed var(--oat-border);border-radius:12px;padding:24px; }
.empty-state strong { font-size:16px; } .empty-state span { color:var(--oat-text-muted);font-size:13px; }
</style>
