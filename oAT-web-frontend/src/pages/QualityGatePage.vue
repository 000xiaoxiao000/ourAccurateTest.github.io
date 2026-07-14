<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 质量门禁</div>
        <h1>质量门禁</h1>
        <p class="subtext">配置并执行质量门禁策略，查看评估结果，对失败规则申请豁免。</p>
      </div>
      <div class="header-actions">
        <button type="button" class="secondary-button" :disabled="loading" @click="load">刷新</button>
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>
    <div v-if="loading" class="loading-state">加载质量门禁数据中...</div>

    <template v-else>
      <!-- Policy selector + evaluate -->
      <div class="toolbar">
        <div class="policy-select-wrap">
          <label class="toolbar-label">评估策略</label>
          <select v-model="selectedPolicyId" class="policy-select">
            <option value="">— 选择策略 —</option>
            <option v-for="p in policies" :key="p.id!" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <button type="button" class="primary-button" :disabled="!selectedPolicyId || evaluating" @click="runEvaluate">
          {{ evaluating ? '评估中...' : '立即评估' }}
        </button>
        <button type="button" class="secondary-button" @click="showPolicyForm = !showPolicyForm">
          {{ showPolicyForm ? '收起' : '+ 新建策略' }}
        </button>
      </div>

      <!-- New policy form -->
      <form v-if="showPolicyForm" class="policy-form" @submit.prevent="savePolicy">
        <h3 class="form-title">新建质量门禁策略</h3>
        <div class="form-grid">
          <label class="field">
            <span>策略名称 *</span>
            <input v-model.trim="draft.name" type="text" required placeholder="如：Release Gate v1" />
          </label>
          <label class="field">
            <span>最低用例覆盖率（0~1）</span>
            <input v-model.number="draft.minTestcaseCoverageRate" type="number" step="0.01" min="0" max="1" />
          </label>
          <label class="field">
            <span>最低实现覆盖率（0~1）</span>
            <input v-model.number="draft.minImplementationCoverageRate" type="number" step="0.01" min="0" max="1" />
          </label>
          <label class="field">
            <span>最大 CRITICAL 发现数</span>
            <input v-model.number="draft.maxCriticalFindings" type="number" min="0" />
          </label>
          <label class="field">
            <span>最大 HIGH 发现数</span>
            <input v-model.number="draft.maxHighFindings" type="number" min="0" />
          </label>
        </div>
        <div class="form-checks">
          <label class="check-field">
            <input v-model="draft.requireAllAmbiguitiesResolved" type="checkbox" />
            <span>要求所有模糊项已解决</span>
          </label>
          <label class="check-field">
            <input v-model="draft.requireChangeImpactVerified" type="checkbox" />
            <span>要求变更影响已确认</span>
          </label>
          <label class="check-field">
            <input v-model="draft.blockOnStaleBaseline" type="checkbox" />
            <span>基线过期时阻断</span>
          </label>
        </div>
        <div class="form-actions">
          <button type="button" class="secondary-button" @click="showPolicyForm = false">取消</button>
          <button type="submit" class="primary-button" :disabled="saving">{{ saving ? '保存中...' : '保存策略' }}</button>
        </div>
      </form>

      <!-- Latest result -->
      <template v-if="latestResult">
        <div class="result-header">
          <div class="result-verdict-wrap">
            <span class="result-verdict" :class="latestResult.verdict.toLowerCase()">{{ verdictText(latestResult.verdict) }}</span>
            <span class="result-time">评估于 {{ formatTime(latestResult.evaluatedAt) }}</span>
          </div>
          <span v-if="latestResult.metrics" class="result-hint">
            用例覆盖 {{ pct(latestResult.metrics.testcaseCoverageRate) }} · 实现覆盖 {{ pct(latestResult.metrics.implementationCoverageRate) }} · 开放问题 {{ latestResult.metrics.openFindings }}
          </span>
        </div>

        <!-- Failures -->
        <section v-if="latestResult.failures.length" class="failures-section">
          <h3 class="section-title">未通过规则 <span class="badge-count bad">{{ latestResult.failures.length }}</span></h3>
          <div class="failures-list">
            <article v-for="f in latestResult.failures" :key="f.ruleId" class="failure-card">
              <div class="failure-top">
                <strong class="failure-desc">{{ f.description }}</strong>
                <div class="failure-values">
                  <span class="val-label">实际</span><span class="val bad">{{ f.actualValue }}</span>
                  <span class="val-label">阈值</span><span class="val">{{ f.threshold }}</span>
                </div>
              </div>
              <div class="exemption-row">
                <span v-if="exemptedRules.has(f.ruleId)" class="exempted-tag">已豁免</span>
                <button v-else type="button" class="act-btn" @click="openExemption(f.ruleId)">申请豁免</button>
              </div>
              <form v-if="exemptForm.ruleId === f.ruleId" class="exemption-form" @submit.prevent="submitExemption">
                <label>
                  <span>豁免原因 *</span>
                  <textarea v-model.trim="exemptForm.reason" rows="2" required placeholder="说明豁免背景..." />
                </label>
                <label>
                  <span>到期时间（可选）</span>
                  <input v-model="exemptForm.expiresAt" type="datetime-local" />
                </label>
                <div class="form-actions">
                  <button type="button" class="secondary-button" @click="exemptForm.ruleId = ''">取消</button>
                  <button type="submit" class="primary-button" :disabled="exemptSubmitting">{{ exemptSubmitting ? '提交中...' : '提交豁免' }}</button>
                </div>
              </form>
            </article>
          </div>
        </section>

        <!-- Active exemptions -->
        <section v-if="latestResult.activeExemptions.length" class="exemptions-section">
          <h3 class="section-title">有效豁免 <span class="badge-count warn">{{ latestResult.activeExemptions.length }}</span></h3>
          <div class="exemptions-list">
            <article v-for="e in latestResult.activeExemptions" :key="e.id" class="exemption-card">
              <strong>{{ e.ruleId }}</strong>
              <span>{{ e.reason }}</span>
              <small v-if="e.expiresAt">到期：{{ formatTime(e.expiresAt) }}</small>
              <small v-else>永久有效</small>
            </article>
          </div>
        </section>

        <div v-if="!latestResult.failures.length" class="pass-banner">
          所有规则均已通过，本次基线达到发布质量标准。
        </div>
      </template>

      <!-- Historical results -->
      <section v-if="results.length > 1" class="history-section">
        <h3 class="section-title">历史评估记录</h3>
        <table class="history-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>结论</th>
              <th>未通过规则</th>
              <th>豁免数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in results" :key="r.id">
              <td>{{ formatTime(r.evaluatedAt) }}</td>
              <td><span class="verdict-chip" :class="r.verdict.toLowerCase()">{{ verdictText(r.verdict) }}</span></td>
              <td>{{ r.failures.length }}</td>
              <td>{{ r.activeExemptions.length }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <div v-if="!latestResult && !loading" class="empty-state">
        <strong>尚未执行质量门禁评估</strong>
        <span>请先选择策略，然后点击「立即评估」。</span>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  createGateExemption, createQualityGatePolicy, evaluateQualityGate,
  fetchQualityGatePolicies, fetchQualityGateResults,
  type GateVerdict, type QualityGatePolicy, type QualityGateResult,
} from '@/api/verification'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))

const policies = ref<QualityGatePolicy[]>([])
const results = ref<QualityGateResult[]>([])
const selectedPolicyId = ref('')
const loading = ref(false)
const error = ref('')
const evaluating = ref(false)
const saving = ref(false)
const showPolicyForm = ref(false)
const exemptSubmitting = ref(false)

const draft = reactive<QualityGatePolicy>({
  name: '',
  minTestcaseCoverageRate: 0.8,
  minImplementationCoverageRate: 0.7,
  maxCriticalFindings: 0,
  maxHighFindings: 3,
  requireAllAmbiguitiesResolved: false,
  requireChangeImpactVerified: false,
  blockOnStaleBaseline: true,
})

const exemptForm = reactive({ ruleId: '', reason: '', expiresAt: '' })

const latestResult = computed(() => results.value[0] ?? null)

const exemptedRules = computed(() => {
  if (!latestResult.value) return new Set<string>()
  return new Set(latestResult.value.activeExemptions.map(e => e.ruleId))
})

onMounted(load)

async function load() {
  loading.value = true; error.value = ''
  try {
    const [p, r] = await Promise.all([
      fetchQualityGatePolicies(projectId.value),
      fetchQualityGateResults(projectId.value, baselineId.value),
    ])
    policies.value = p
    results.value = r
    if (!selectedPolicyId.value && p.length) selectedPolicyId.value = p[0].id ?? ''
  } catch (e) { error.value = msg(e) }
  finally { loading.value = false }
}

async function runEvaluate() {
  if (!selectedPolicyId.value) return
  evaluating.value = true
  try {
    const r = await evaluateQualityGate(projectId.value, baselineId.value, selectedPolicyId.value)
    results.value = [r, ...results.value]
    toast.success(`评估完成：${verdictText(r.verdict)}`)
  } catch (e) { toast.error(msg(e)) }
  finally { evaluating.value = false }
}

async function savePolicy() {
  saving.value = true
  try {
    const p = await createQualityGatePolicy(projectId.value, { ...draft })
    policies.value = [...policies.value, p]
    selectedPolicyId.value = p.id ?? ''
    showPolicyForm.value = false
    toast.success('策略已保存')
    Object.assign(draft, {
      name: '', minTestcaseCoverageRate: 0.8, minImplementationCoverageRate: 0.7,
      maxCriticalFindings: 0, maxHighFindings: 3,
      requireAllAmbiguitiesResolved: false, requireChangeImpactVerified: false, blockOnStaleBaseline: true,
    })
  } catch (e) { toast.error(msg(e)) }
  finally { saving.value = false }
}

function openExemption(ruleId: string) {
  Object.assign(exemptForm, { ruleId, reason: '', expiresAt: '' })
}

async function submitExemption() {
  if (!exemptForm.reason) return
  exemptSubmitting.value = true
  try {
    await createGateExemption(projectId.value, baselineId.value, {
      ruleId: exemptForm.ruleId,
      reason: exemptForm.reason,
      expiresAt: exemptForm.expiresAt || undefined,
    })
    toast.success('豁免申请已提交')
    exemptForm.ruleId = ''
    await load()
  } catch (e) { toast.error(msg(e)) }
  finally { exemptSubmitting.value = false }
}

const VM: Record<GateVerdict, string> = { PASSED: '通过', FAILED: '未通过', WARNING: '警告', EXEMPTED: '已豁免' }
function verdictText(v: GateVerdict) { return VM[v] || v }
function pct(v: number) { return `${Math.round((v || 0) * 1000) / 10}%` }
function formatTime(s?: string) { return s ? new Date(s).toLocaleString('zh-CN', { hour12: false }) : '-' }
function msg(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
</script>

<style scoped>
.page-content { display: flex; flex-direction: column; gap: 18px; }
.page-header, .header-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.secondary-button, .secondary-button-link, .primary-button { display:inline-flex;align-items:center;min-height:36px;border-radius:8px;padding:7px 14px;font-weight:800;font-size:14px;text-decoration:none;cursor:pointer;border:1px solid var(--oat-border);background:var(--oat-surface-soft);color:var(--oat-text); }
.primary-button { border-color:var(--oat-primary);background:var(--oat-primary);color:#fff; }
.primary-button:disabled, .secondary-button:disabled { opacity:.45;cursor:not-allowed; }
.toolbar { display:flex;align-items:center;gap:10px;flex-wrap:wrap; }
.toolbar-label { font-size:12px;font-weight:700;color:var(--oat-text-muted); }
.policy-select-wrap { display:flex;align-items:center;gap:8px; }
.policy-select { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px;min-width:200px; }
.policy-form { display:grid;gap:14px;padding:16px;border:1px solid var(--oat-border);border-radius:12px;background:var(--oat-surface-soft); }
.form-title { margin:0;font-size:15px; }
.form-grid { display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px; }
.field { display:grid;gap:4px; }
.field span { font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.field input { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px; }
.form-checks { display:flex;gap:16px;flex-wrap:wrap; }
.check-field { display:flex;align-items:center;gap:6px;font-size:13px;cursor:pointer; }
.form-actions { display:flex;justify-content:flex-end;gap:8px; }
.result-header { display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:14px 16px;border:1px solid var(--oat-border);border-radius:12px;background:#fff; }
.result-verdict-wrap { display:flex;align-items:center;gap:10px; }
.result-verdict { border-radius:8px;padding:5px 14px;font-size:15px;font-weight:900; }
.result-verdict.passed { background:rgba(22,163,74,.12);color:#15803d; }
.result-verdict.failed { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.result-verdict.warning { background:rgba(245,158,11,.12);color:#92400e; }
.result-verdict.exempted { background:rgba(100,116,139,.1);color:#475569; }
.result-time { font-size:12px;color:var(--oat-text-muted); }
.result-hint { font-size:12px;color:var(--oat-text-muted); }
.section-title { display:flex;align-items:center;gap:8px;margin:0 0 10px;font-size:15px; }
.badge-count { border-radius:999px;padding:1px 8px;font-size:11px;font-weight:900; }
.badge-count.bad { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.badge-count.warn { background:rgba(245,158,11,.12);color:#92400e; }
.failures-section, .exemptions-section, .history-section { display:grid;gap:10px; }
.failures-list, .exemptions-list { display:grid;gap:8px; }
.failure-card { display:grid;gap:8px;padding:12px 14px;border:1px solid rgba(220,38,38,.2);border-left:4px solid var(--oat-danger);border-radius:10px;background:#fff; }
.failure-top { display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap; }
.failure-desc { font-size:14px; }
.failure-values { display:flex;align-items:center;gap:6px;font-size:12px;flex-shrink:0; }
.val-label { color:var(--oat-text-muted); }
.val { font-weight:800; }
.val.bad { color:var(--oat-danger); }
.exemption-row { display:flex;align-items:center;gap:8px; }
.exempted-tag { border-radius:999px;padding:2px 8px;font-size:11px;font-weight:800;background:rgba(22,163,74,.1);color:#15803d; }
.act-btn { min-height:28px;border-radius:6px;padding:3px 10px;border:1px solid var(--oat-border);background:#fff;font-size:12px;font-weight:800;cursor:pointer; }
.exemption-form { display:grid;gap:10px;padding:10px 12px;border:1px solid var(--oat-border);border-radius:10px;background:var(--oat-surface-soft); }
.exemption-form label { display:grid;gap:4px; }
.exemption-form label span { font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
.exemption-form textarea, .exemption-form input { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;font-size:13px;width:100%; }
.exemption-card { display:grid;grid-template-columns:auto 1fr auto;gap:4px 10px;align-items:baseline;padding:10px 12px;border:1px solid var(--oat-border);border-radius:10px;background:var(--oat-surface-soft);font-size:13px; }
.exemption-card strong { color:var(--oat-text-muted);font-size:11px; }
.exemption-card small { font-size:11px;color:var(--oat-text-muted);text-align:right; }
.pass-banner { padding:16px;border:1px solid rgba(22,163,74,.25);border-radius:10px;background:rgba(22,163,74,.06);color:#15803d;font-weight:700;text-align:center; }
.history-table { width:100%;border-collapse:collapse;font-size:13px; }
.history-table th { padding:8px 10px;border-bottom:2px solid var(--oat-border);text-align:left;font-size:12px;color:var(--oat-text-muted); }
.history-table td { padding:8px 10px;border-bottom:1px solid var(--oat-border); }
.verdict-chip { border-radius:999px;padding:2px 8px;font-size:11px;font-weight:800; }
.verdict-chip.passed { background:rgba(22,163,74,.1);color:#15803d; }
.verdict-chip.failed { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.verdict-chip.warning { background:rgba(245,158,11,.12);color:#92400e; }
.verdict-chip.exempted { background:rgba(100,116,139,.1);color:#475569; }
.notice.danger { border:1px solid rgba(220,38,38,.25);border-radius:10px;padding:10px 14px;color:var(--oat-danger);background:rgba(220,38,38,.06); }
.loading-state { padding:24px;text-align:center;color:var(--oat-text-muted); }
.empty-state { display:grid;gap:8px;min-height:160px;place-content:center;text-align:center;border:1px dashed var(--oat-border);border-radius:12px;padding:24px; }
.empty-state strong { font-size:16px; }
.empty-state span { color:var(--oat-text-muted);font-size:13px; }
</style>
