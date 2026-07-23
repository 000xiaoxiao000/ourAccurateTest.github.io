<template>
  <section class="page-content">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">AI 验证 · 追溯依据</div>
        <h1>双向追溯矩阵</h1>
        <p class="subtext">以验收标准为最小单位，展示需求 → 测试用例 → 代码符号 → 执行依据的正向追溯关系与依据等级。</p>
      </div>
      <div class="header-actions">
        <button type="button" class="secondary-button" :disabled="loading" @click="load">刷新</button>
        <RouterLink :to="`/p/${projectId}/verification`" class="secondary-button-link">← 返回验证工作区</RouterLink>
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>
    <div v-if="loading" class="loading-state">加载追溯矩阵中...</div>

    <template v-else-if="matrix.length">
      <div class="filter-bar">
        <select v-model="filterVerdict">
          <option value="">全部结论</option>
          <option value="NOT_SATISFIED">不满足</option>
          <option value="PARTIAL">部分满足</option>
          <option value="AMBIGUOUS">需求模糊</option>
          <option value="STATICALLY_CONSISTENT">静态一致</option>
          <option value="SATISFIED">已满足</option>
          <option value="NOT_VERIFIABLE">不可验证</option>
        </select>
        <select v-model="filterEvidence">
          <option value="">全部依据等级</option>
          <option value="E0">E0 无依据</option>
          <option value="E1">E1 用例依据</option>
          <option value="E2">E2 代码依据</option>
          <option value="E3">E3 执行依据</option>
          <option value="E4">E4 覆盖率依据</option>
        </select>
        <span class="filter-count">显示 {{ filtered.length }} / {{ matrix.length }} 条 AC</span>
      </div>

      <div class="matrix-table">
        <div class="table-head">
          <span>验收标准</span>
          <span>测试用例覆盖</span>
          <span>代码 / 执行依据</span>
          <span>结论 · 依据级</span>
        </div>

        <article v-for="row in filtered" :key="row.criterion.id" class="matrix-row" :class="row.verdict.toLowerCase()">
          <div class="ac-cell">
            <div class="ac-keys">
              <strong>{{ row.criterion.requirementKey }}</strong>
              <span>/</span>
              <code>{{ row.criterion.acKey }}</code>
              <span v-if="row.criterion.ambiguity" class="badge warning">歧义</span>
              <span v-if="!row.criterion.testable" class="badge muted">不可测</span>
            </div>
            <p class="ac-content">{{ row.criterion.content }}</p>
            <small v-if="row.criterion.sourceLocator" class="locator">📍 {{ row.criterion.sourceLocator }}</small>
          </div>

          <div class="tc-cell">
            <div v-if="row.testcases.length" class="chip-list">
              <span v-for="tc in row.testcases" :key="tc.id" class="tc-chip" :title="tc.title">
                {{ tc.externalKey }}
              </span>
            </div>
            <em v-else class="no-coverage">无测试用例</em>
          </div>

          <div class="evidence-cell">
            <div v-if="row.codeLinks.length" class="link-list">
              <button
                v-for="link in row.codeLinks"
                :key="link.id"
                type="button"
                class="evidence-chip"
                :class="link.reviewStatus.toLowerCase()"
                :title="evidenceTooltip(link)"
                @click="confirmLink(link.id, link.reviewStatus)"
              >
                {{ targetTypeText(link.targetType) }} · {{ link.evidenceLevel }} · {{ reviewText(link.reviewStatus) }}
              </button>
            </div>
            <em v-else class="no-coverage">无代码 / 执行依据</em>
          </div>

          <div class="verdict-cell">
            <span class="verdict-badge" :class="row.verdict.toLowerCase()">{{ verdictText(row.verdict) }}</span>
            <small class="evidence-level" :title="levelHelp(row.evidenceLevel)">{{ row.evidenceLevel }}</small>
          </div>
        </article>
      </div>

      <div v-if="!filtered.length" class="empty-state">
        <strong>当前筛选下没有验收标准</strong>
        <span>尝试清除筛选条件</span>
      </div>
    </template>

    <div v-else-if="!loading" class="empty-state">
      <strong>该基线还没有追溯矩阵数据</strong>
      <span>请先在 AI 验证工作区运行分析，分析完成后追溯矩阵将自动更新。</span>
      <RouterLink :to="`/p/${projectId}/verification`" class="primary-button">去运行分析</RouterLink>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchTraceMatrix, reviewTraceLink,
  type MatrixRow, type ReviewStatus, type TraceLink, type Verdict,
} from '@/api/verification'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const baselineId = computed(() => String(route.params.baselineId || ''))

const matrix = ref<MatrixRow[]>([])
const loading = ref(false)
const error = ref('')
const filterVerdict = ref('')
const filterEvidence = ref('')
const confirmingLinkIds = ref<Set<string>>(new Set())

const filtered = computed(() => matrix.value.filter(row => {
  if (filterVerdict.value && row.verdict !== filterVerdict.value) return false
  if (filterEvidence.value && row.evidenceLevel !== filterEvidence.value) return false
  return true
}))

onMounted(load)

async function load() {
  if (!baselineId.value) return
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    matrix.value = await fetchTraceMatrix(projectId.value, baselineId.value)
  } catch (e) {
    error.value = msgOf(e)
  } finally {
    loading.value = false
  }
}

async function confirmLink(id: string, currentStatus: ReviewStatus) {
  if (confirmingLinkIds.value.has(id)) return
  confirmingLinkIds.value = new Set(confirmingLinkIds.value).add(id)
  const next: ReviewStatus = currentStatus === 'CONFIRMED' ? 'PENDING' : 'CONFIRMED'
  try {
    await reviewTraceLink(projectId.value, id, next)
    toast.success(next === 'CONFIRMED' ? '追溯关系已确认' : '追溯关系已取消确认')
    await load()
  } catch (e) {
    toast.error(msgOf(e))
  } finally {
    const nextIds = new Set(confirmingLinkIds.value)
    nextIds.delete(id)
    confirmingLinkIds.value = nextIds
  }
}

function evidenceTooltip(link: TraceLink) {
  const ev = link.evidence as Record<string, string> | undefined
  return ev ? `${ev.reason || ''}\n${ev.locator || ''}`.trim() : link.targetId
}

const verdictMap: Record<string, string> = {
  SATISFIED: '已满足', STATICALLY_CONSISTENT: '静态一致', PARTIAL: '部分满足',
  NOT_SATISFIED: '不满足', AMBIGUOUS: '需求模糊', NOT_VERIFIABLE: '不可验证',
  EXEMPTED: '已豁免', STALE: '已过期',
}
function verdictText(v: Verdict) { return verdictMap[v] || v }

const targetTypeMap: Record<string, string> = {
  TESTCASE: '用例', SOURCE_SYMBOL: '源码', EXECUTION: '执行', COVERAGE: '覆盖率', DEFECT: '缺陷',
}
function targetTypeText(t: string) { return targetTypeMap[t] || t }

const reviewMap: Record<string, string> = {
  PENDING: '待确认', CONFIRMED: '已确认', REJECTED: '已驳回', STALE: '已过期', EXEMPTED: '已豁免',
}
function reviewText(s: string) { return reviewMap[s] || s }

const levelHelpMap: Record<string, string> = {
  E0: '没有依据，需要补充', E1: '有用例关联但无代码/执行依据',
  E2: '有代码实现依据（静态）', E3: '有测试执行依据', E4: '有覆盖率依据（最强）',
}
function levelHelp(l: string) { return levelHelpMap[l] || '' }

function msgOf(e: unknown) { return e instanceof Error ? e.message : '操作失败' }
</script>

<style scoped>
.page-content { display: flex; flex-direction: column; gap: 14px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.header-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.secondary-button, .secondary-button-link, .primary-button {
  display: inline-flex; align-items: center; min-height: 36px; border-radius: 8px;
  padding: 7px 14px; font-weight: 800; font-size: 14px; text-decoration: none;
  border: 1px solid var(--oat-border); background: var(--oat-surface-soft); color: var(--oat-text);
}
.primary-button { border-color: var(--oat-primary); background: var(--oat-primary); color: #fff; }

.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-bar select {
  border: 1px solid var(--oat-border); border-radius: 8px; padding: 7px 10px;
  background: #fff; color: var(--oat-text); font-size: 13px;
}
.filter-count { color: var(--oat-text-muted); font-size: 12px; }

.matrix-table { display: grid; border: 1px solid var(--oat-border); border-radius: 12px; overflow: hidden; }
.table-head {
  display: grid; grid-template-columns: 2fr 1fr 1.2fr .6fr; gap: 12px; padding: 10px 14px;
  background: var(--oat-surface-soft); font-size: 12px; font-weight: 800; color: var(--oat-text-secondary);
}
.matrix-row {
  display: grid; grid-template-columns: 2fr 1fr 1.2fr .6fr; gap: 12px; padding: 12px 14px;
  border-top: 1px solid var(--oat-border); background: #fff; align-items: start;
}
.matrix-row.not_satisfied { border-left: 3px solid var(--oat-danger); }
.matrix-row.ambiguous { border-left: 3px solid #f59e0b; }
.matrix-row.satisfied, .matrix-row.statically_consistent { border-left: 3px solid var(--oat-success); }

.ac-keys { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 13px; }
.ac-content { margin: 4px 0; color: var(--oat-text-secondary); font-size: 12px; line-height: 1.5; }
.locator { color: var(--oat-text-muted); font-size: 11px; }

.badge { border-radius: 999px; padding: 2px 7px; font-size: 11px; font-weight: 800; }
.badge.warning { background: rgba(245, 158, 11, .15); color: #92400e; }
.badge.muted { background: rgba(100, 116, 139, .12); color: #64748b; }

.chip-list, .link-list { display: flex; flex-wrap: wrap; gap: 5px; }
.tc-chip {
  border-radius: 999px; padding: 3px 8px;
  background: rgba(var(--oat-primary-rgb), .08); color: var(--oat-primary-dark);
  font-size: 11px; font-weight: 800;
}
.evidence-chip {
  border: 1px solid var(--oat-border); border-radius: 999px; padding: 3px 8px;
  background: #fff; font-size: 11px; font-weight: 800; cursor: pointer;
}
.evidence-chip.confirmed { border-color: var(--oat-success); background: rgba(22, 163, 74, .07); color: #15803d; }
.evidence-chip:hover { border-color: var(--oat-primary); }
.no-coverage { color: var(--oat-text-muted); font-size: 12px; font-style: normal; }

.verdict-cell { display: grid; gap: 4px; }
.verdict-badge { border-radius: 999px; padding: 3px 8px; font-size: 11px; font-weight: 800; background: rgba(100,116,139,.1); color: #475569; }
.verdict-badge.not_satisfied { background: rgba(220,38,38,.1); color: var(--oat-danger); }
.verdict-badge.ambiguous { background: rgba(245,158,11,.12); color: #92400e; }
.verdict-badge.satisfied, .verdict-badge.statically_consistent { background: rgba(22,163,74,.1); color: #15803d; }
.evidence-level { color: var(--oat-text-muted); font-size: 11px; cursor: help; text-decoration: underline dotted; }

.notice.danger { border: 1px solid rgba(220,38,38,.25); border-radius: 10px; padding: 10px 14px; color: var(--oat-danger); background: rgba(220,38,38,.06); }
.loading-state { padding: 24px; text-align: center; color: var(--oat-text-muted); }
.empty-state { display: grid; gap: 8px; min-height: 180px; place-content: center; text-align: center; border: 1px dashed var(--oat-border); border-radius: 12px; padding: 24px; }
.empty-state strong { font-size: 16px; }
.empty-state span { color: var(--oat-text-muted); font-size: 13px; }

@media (max-width: 900px) {
  .table-head, .matrix-row { grid-template-columns: 1fr; }
  .table-head { display: none; }
}
</style>
