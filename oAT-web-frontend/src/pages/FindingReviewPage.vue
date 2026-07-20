<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 分析结果</div>
        <h1>AI 发现 · 审核</h1>
        <p class="subtext">按产品 / 测试 / 开发 / 交叉视角审核 AI 发现，确认、驳回或生成回写内容。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="load" />
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>
    <div v-if="loading" class="loading-state">加载分析结果中...</div>

    <template v-else>
      <div class="filter-bar">
        <select v-model="filterPerspective">
          <option value="">全部视角</option>
          <option value="PRODUCT">产品</option>
          <option value="TEST">测试</option>
          <option value="DEVELOPMENT">开发</option>
          <option value="CROSS">交叉</option>
        </select>
        <select v-model="filterSeverity">
          <option value="">全部严重度</option>
          <option value="CRITICAL">严重</option>
          <option value="HIGH">高</option>
          <option value="MEDIUM">中</option>
          <option value="LOW">低</option>
        </select>
        <select v-model="filterStatus">
          <option value="">全部状态</option>
          <option value="PENDING">待审核</option>
          <option value="CONFIRMED">已确认</option>
          <option value="REJECTED">已驳回</option>
          <option value="EXEMPTED">已豁免</option>
        </select>
        <span class="filter-count">{{ filtered.length }} / {{ findings.length }} 条</span>
      </div>

      <div class="findings-list">
        <article
          v-for="f in filtered"
          :key="f.id"
          class="finding-card"
          :class="[f.severity.toLowerCase(), f.perspective.toLowerCase()]"
        >
          <div class="finding-header">
            <div class="finding-meta">
              <span class="sev-badge" :class="f.severity.toLowerCase()">{{ severityText(f.severity) }}</span>
              <span class="persp-badge" :class="f.perspective.toLowerCase()">{{ perspectiveText(f.perspective) }}</span>
              <span class="verdict-badge" :class="f.verdict.toLowerCase()">{{ verdictText(f.verdict) }}</span>
              <span class="status-badge" :class="f.reviewStatus.toLowerCase()">{{ statusText(f.reviewStatus) }}</span>
            </div>
            <div class="finding-actions">
              <button type="button" class="act-btn confirm" :disabled="f.reviewStatus === 'CONFIRMED'" @click="review(f.id, 'CONFIRMED')">确认</button>
              <button type="button" class="act-btn reject" :disabled="f.reviewStatus === 'REJECTED'" @click="review(f.id, 'REJECTED')">驳回</button>
              <button type="button" class="act-btn exempt" :disabled="f.reviewStatus === 'EXEMPTED'" @click="review(f.id, 'EXEMPTED')">豁免</button>
              <button type="button" class="act-btn wb" @click="openWB(f)">生成回写</button>
            </div>
          </div>
          <h3 class="finding-title">{{ f.title }}</h3>
          <p class="finding-desc">{{ f.description }}</p>
          <p v-if="f.suggestion" class="finding-tip">💡 {{ f.suggestion }}</p>
          <a v-if="f.externalWorkItemUrl" :href="f.externalWorkItemUrl" target="_blank" rel="noreferrer" class="ext-link">外部事项 →</a>
          <form v-if="wbId === f.id" class="wb-form" @submit.prevent="submitWB(f.id)">
            <label><span>接收方</span>
              <select v-model="wbRole">
                <option value="CROSS">综合协同</option>
                <option value="PRODUCT">产品</option>
                <option value="TEST">测试</option>
                <option value="DEVELOPMENT">开发</option>
              </select>
            </label>
            <label><span>外部链接（可选）</span>
              <input v-model.trim="wbUrl" type="url" placeholder="https://..." />
            </label>
            <label><span>补充说明（可选）</span>
              <textarea v-model.trim="wbNote" rows="2" placeholder="说明处理背景..." />
            </label>
            <div class="wb-actions">
              <button type="button" class="secondary-button" @click="cancelWB">取消</button>
              <button type="submit" class="primary-button" :disabled="wbSubmitting">{{ wbSubmitting ? 'AI生成中...' : '生成并记录' }}</button>
            </div>
          </form>
        </article>
      </div>
      <div v-if="!filtered.length && !loading" class="empty-state">
        <strong>当前筛选下没有 AI 发现</strong>
        <span>调整筛选条件，或先在工作区运行 AI 分析。</span>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import {
  fetchBaselineDetail, reviewVerificationFinding, writeBackVerificationFinding,
  type Perspective, type ReviewStatus, type Severity, type VerificationFinding, type Verdict,
} from '@/api/verification'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))
const findings = ref<VerificationFinding[]>([])
const loading = ref(false)
const error = ref('')
const filterPerspective = ref('')
const filterSeverity = ref('')
const filterStatus = ref('PENDING')
const wbId = ref('')
const wbRole = ref<Perspective>('CROSS')
const wbUrl = ref('')
const wbNote = ref('')
const wbSubmitting = ref(false)
const reviewingIds = ref<Set<string>>(new Set())

const filtered = computed(() => findings.value.filter(f => {
  if (filterPerspective.value && f.perspective !== filterPerspective.value) return false
  if (filterSeverity.value && f.severity !== filterSeverity.value) return false
  if (filterStatus.value && f.reviewStatus !== filterStatus.value) return false
  return true
}))

onMounted(load)

async function load() {
  if (!baselineId.value) return
  if (loading.value) return
  loading.value = true; error.value = ''
  try { const d = await fetchBaselineDetail(projectId.value, baselineId.value); findings.value = d.findings }
  catch (e) { error.value = msg(e) }
  finally { loading.value = false }
}

async function review(id: string, status: ReviewStatus) {
  if (reviewingIds.value.has(id)) return
  reviewingIds.value = new Set(reviewingIds.value).add(id)
  try { await reviewVerificationFinding(projectId.value, id, { status, reason: '工作台审核' }); toast.success('审核已更新'); await load() }
  catch (e) { toast.error(msg(e)) }
  finally {
    const next = new Set(reviewingIds.value)
    next.delete(id)
    reviewingIds.value = next
  }
}

function openWB(f: VerificationFinding) { wbId.value = f.id; wbRole.value = f.perspective; wbUrl.value = f.externalWorkItemUrl || ''; wbNote.value = '' }
function cancelWB() { wbId.value = ''; wbUrl.value = ''; wbNote.value = '' }

async function submitWB(id: string) {
  if (wbSubmitting.value) return
  wbSubmitting.value = true
  try {
    await writeBackVerificationFinding(projectId.value, id, { connectorType: 'ai-writeback', externalUrl: wbUrl.value || undefined, message: wbNote.value || undefined, targetRole: wbRole.value })
    toast.success('AI 回写内容已生成'); cancelWB(); await load()
  } catch (e) { toast.error(msg(e)) }
  finally { wbSubmitting.value = false }
}

const SM: Record<Severity, string> = { CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低', INFO: '信息' }
const PM: Record<Perspective, string> = { PRODUCT: '产品', TEST: '测试', DEVELOPMENT: '开发', CROSS: '交叉' }
const VM: Record<Verdict, string> = { SATISFIED: '已满足', STATICALLY_CONSISTENT: '静态一致', PARTIAL: '部分满足', NOT_SATISFIED: '不满足', AMBIGUOUS: '模糊', NOT_VERIFIABLE: '不可验证', EXEMPTED: '豁免', STALE: '过期' }
const RSM: Record<ReviewStatus, string> = { PENDING: '待审核', CONFIRMED: '已确认', REJECTED: '已驳回', WRITTEN_BACK: '已回写', STALE: '已过期', EXEMPTED: '已豁免' }

function severityText(s: Severity) { return SM[s] || s }
function perspectiveText(p: Perspective) { return PM[p] || p }
function verdictText(v: Verdict) { return VM[v] || v }
function statusText(s: ReviewStatus) { return RSM[s] || s }
function msg(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
</script>

<style scoped>
.page-content { display: flex; flex-direction: column; gap: 14px; }
.page-header,.header-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.secondary-button,.secondary-button-link,.primary-button { display:inline-flex;align-items:center;min-height:36px;border-radius:8px;padding:7px 14px;font-weight:800;font-size:14px;text-decoration:none;cursor:pointer;border:1px solid var(--oat-border);background:var(--oat-surface-soft);color:var(--oat-text); }
.primary-button { border-color:var(--oat-primary);background:var(--oat-primary);color:#fff; }
.filter-bar { display:flex;align-items:center;gap:10px;flex-wrap:wrap; }
.filter-bar select { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;background:#fff;font-size:13px; }
.filter-count { color:var(--oat-text-muted);font-size:12px; }
.findings-list { display:grid;gap:12px; }
.finding-card { display:grid;gap:10px;padding:14px 16px;border:1px solid var(--oat-border);border-left:4px solid #94a3b8;border-radius:12px;background:#fff; }
.finding-card.critical,.finding-card.high { border-left-color:var(--oat-danger); }
.finding-card.medium { border-left-color:#f59e0b; }
.finding-header { display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap; }
.finding-meta { display:flex;gap:5px;flex-wrap:wrap; }
.sev-badge,.persp-badge,.verdict-badge,.status-badge { border-radius:999px;padding:2px 7px;font-size:11px;font-weight:800;background:rgba(100,116,139,.1);color:#475569; }
.sev-badge.critical,.sev-badge.high { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.sev-badge.medium { background:rgba(245,158,11,.12);color:#92400e; }
.persp-badge.product { background:rgba(139,92,246,.1);color:#6d28d9; }
.persp-badge.test { background:rgba(var(--oat-primary-rgb),.1);color:var(--oat-primary-dark); }
.persp-badge.development { background:rgba(22,163,74,.1);color:#15803d; }
.verdict-badge.not_satisfied { background:rgba(220,38,38,.1);color:var(--oat-danger); }
.verdict-badge.satisfied,.verdict-badge.statically_consistent { background:rgba(22,163,74,.1);color:#15803d; }
.status-badge.confirmed { background:rgba(22,163,74,.1);color:#15803d; }
.finding-actions { display:flex;gap:6px;flex-wrap:wrap; }
.act-btn { min-height:30px;border-radius:6px;padding:4px 10px;border:1px solid var(--oat-border);background:#fff;font-size:12px;font-weight:800;cursor:pointer; }
.act-btn:disabled { opacity:.45;cursor:not-allowed; }
.act-btn.wb { border-color:var(--oat-primary);color:var(--oat-primary-dark); }
.finding-title { margin:0;font-size:15px; }
.finding-desc { margin:0;color:var(--oat-text-secondary);font-size:13px;line-height:1.6; }
.finding-tip { margin:0;color:var(--oat-primary-dark);font-size:13px; }
.ext-link { color:var(--oat-primary-dark);font-size:12px;font-weight:800; }
.wb-form { display:grid;gap:10px;padding:12px;border:1px solid rgba(var(--oat-primary-rgb),.18);border-radius:12px;background:rgba(var(--oat-primary-rgb),.04); }
label { display:grid;gap:4px; } label span { font-size:12px;font-weight:700;color:var(--oat-text-secondary); }
input,select,textarea { border:1px solid var(--oat-border);border-radius:8px;padding:7px 10px;width:100%;font-size:13px; }
.wb-actions { display:flex;justify-content:flex-end;gap:8px; }
.notice.danger { border:1px solid rgba(220,38,38,.25);border-radius:10px;padding:10px 14px;color:var(--oat-danger);background:rgba(220,38,38,.06); }
.loading-state { padding:24px;text-align:center;color:var(--oat-text-muted); }
.empty-state { display:grid;gap:8px;min-height:160px;place-content:center;text-align:center;border:1px dashed var(--oat-border);border-radius:12px;padding:24px; }
.empty-state strong { font-size:16px; } .empty-state span { color:var(--oat-text-muted);font-size:13px; }
</style>
