<template>
  <section class="git-impact-page">
    <header class="page-header plain-header">
      <div>
        <div class="eyebrow">Git Change Impact</div>
        <h1>Git 变更影响分析</h1>
        <p class="subtext">比较两个 Commit，定位结构化变更、调用传播路径，以及受影响的验收标准和回归用例。</p>
      </div>
      <div class="header-actions">
        <AppRefreshButton :loading="loading" @click="loadOverview" />
      </div>
    </header>

    <div v-if="error" class="notice danger">{{ error }}</div>

    <section class="panel-section form-stack">
      <div class="section-head"><h2>分析范围</h2><span>确定性传播优先，LLM 仅辅助确认</span></div>
      <label>
        <span>分析基线</span>
        <select v-model="form.baselineId">
          <option value="">请选择已完成的 AI 验证基线</option>
          <option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">{{ baseline.name }} · {{ baseline.status }}</option>
        </select>
      </label>
      <div class="inline-grid">
        <label>
          <span>源码工程</span>
          <select v-model="form.appId">
            <option value="">请选择源码工程</option>
            <option v-for="app in apps" :key="app.id" :value="app.id">{{ app.name }}</option>
          </select>
        </label>
        <label><span>Base Commit</span><input v-model.trim="form.baseCommit" placeholder="比较起点 Commit" /></label>
        <label><span>Head Commit</span><input v-model.trim="form.headCommit" placeholder="比较终点 Commit" /></label>
      </div>
      <div class="analyze-action-row">
        <button
          type="button"
          class="analyze-button"
          :disabled="loading || !canAnalyze"
          :aria-label="loading ? '正在分析 Git 影响范围' : '分析 Git 影响范围'"
          @click="analyze"
        >
          <svg v-if="loading" class="analyze-icon spinning" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20 11a8 8 0 0 0-14.9-4L3 9m0-5v5h5M4 13a8 8 0 0 0 14.9 4L21 15m0 5v-5h-5" />
          </svg>
          <span>{{ loading ? '正在分析影响范围...' : '分析 Git 影响范围' }}</span>
          <span v-if="!canAnalyze && !loading" class="analyze-hint">请完善分析条件</span>
        </button>
      </div>
    </section>

    <section v-if="result" class="panel-section">
      <div class="section-head"><h2>影响结果</h2><span>{{ result.report.directChanges.length }} 项直接变更 · {{ result.report.candidates.length }} 项候选影响</span></div>
      <div v-if="!result.report.directChanges.length" class="empty-state compact">两个 Commit 间未发现可分析的结构化源码变更。</div>
      <article v-for="change in result.report.directChanges" :key="change.symbolKey" class="impact-record">
        <strong>{{ change.symbolKey }}</strong><span>{{ change.changeType }} · {{ change.facets.join(' / ') }}</span>
      </article>
      <h3>受影响验收标准</h3>
      <div v-if="!result.traceability.affectedCriteria.length" class="empty-inline">暂无已确认追溯关系可映射到验收标准。</div>
      <article v-for="criterion in result.traceability.affectedCriteria" :key="criterion.id" class="impact-record">
        <strong>{{ criterion.requirementKey }} / {{ criterion.acKey }}</strong><span>{{ criterion.title || criterion.content }}</span>
      </article>
      <h3>建议回归用例</h3>
      <div v-if="!result.traceability.affectedTestcases.length" class="empty-inline">暂无已确认追溯关系可映射到测试用例。</div>
      <article v-for="testcase in result.traceability.affectedTestcases" :key="testcase.id" class="impact-record">
        <strong>{{ testcase.externalKey }}</strong><span>{{ testcase.title }}</span>
      </article>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import { analyzeGitChangeImpact, fetchVerificationOverview, type GitChangeImpactResponse, type VerificationOverview } from '@/api/verification'
import AppRefreshButton from '@/components/AppRefreshButton.vue'
import { useToast } from '@/composables/useToast'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const toast = useToast()
const projectStore = useProjectStore()
const projectId = computed(() => String(route.params.projectId || ''))
const apps = computed(() => projectStore.contextByProjectId[projectId.value]?.apps || [])
const overview = ref<VerificationOverview>({ requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] })
const result = ref<GitChangeImpactResponse | null>(null)
const loading = ref(false)
const error = ref('')
const form = reactive({ baselineId: '', appId: '', baseCommit: '', headCommit: '' })
const canAnalyze = computed(() => !!form.baselineId && !!form.appId && !!form.baseCommit && !!form.headCommit)

async function loadOverview() {
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadProjectContext(projectId.value)
    overview.value = await fetchVerificationOverview(projectId.value)
  } catch (err) {
    error.value = messageOf(err)
  } finally {
    loading.value = false
  }
}

async function analyze() {
  if (!canAnalyze.value) return
  loading.value = true
  error.value = ''
  try {
    result.value = await analyzeGitChangeImpact(projectId.value, form.baselineId, {
      appId: form.appId, baseCommit: form.baseCommit, headCommit: form.headCommit,
    })
    toast.success('Git 变更影响分析完成')
  } catch (err) {
    error.value = messageOf(err)
    toast.error(error.value)
  } finally {
    loading.value = false
  }
}

function messageOf(err: unknown) {
  return err instanceof Error ? err.message : '请求失败，请稍后重试'
}

onMounted(loadOverview)
</script>

<style scoped>
.git-impact-page { display: grid; gap: 18px; }
.page-header,
.header-actions {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions {
  justify-content: flex-end;
}
@keyframes refresh-spin {
  to { transform: rotate(360deg); }
}
.form-stack { display: grid; gap: 14px; }
.analyze-action-row {
  display: flex;
  justify-content: flex-end;
}
.analyze-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-height: 48px;
  width: fit-content;
  min-width: 220px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-primary-hover));
  color: #fff;
  box-shadow: 0 10px 22px rgba(var(--oat-primary-rgb), .22);
  font-weight: 800;
  line-height: 1;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, opacity .16s ease;
}
.analyze-button:hover:not(:disabled) {
  box-shadow: 0 14px 28px rgba(var(--oat-primary-rgb), .30);
  transform: translateY(-2px);
}
.analyze-button:active:not(:disabled) {
  box-shadow: 0 5px 12px rgba(var(--oat-primary-rgb), .18);
  transform: translateY(0) scale(.98);
}
.analyze-button:focus-visible {
  outline: none;
  box-shadow: var(--oat-focus-ring), 0 10px 22px rgba(var(--oat-primary-rgb), .22);
}
.analyze-button:disabled {
  border-color: var(--oat-border);
  background: var(--oat-surface-soft);
  color: var(--oat-text-muted);
  box-shadow: none;
  opacity: 1;
}
.analyze-icon {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}
.analyze-hint {
  margin-left: 3px;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
}
.inline-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.form-stack label { display: grid; gap: 7px; color: #475569; font-size: 13px; font-weight: 700; }
.form-stack input, .form-stack select { min-height: 42px; border: 1px solid rgba(15, 23, 42, .13); border-radius: 10px; padding: 9px 11px; background: #fff; color: #1e293b; font: inherit; }
.impact-record { display: grid; gap: 5px; padding: 12px 0; border-bottom: 1px solid rgba(15, 23, 42, .08); }
.impact-record strong { overflow-wrap: anywhere; color: #0f766e; }
.impact-record span { color: #64748b; font-size: 13px; }
h3 { margin: 22px 0 8px; font-size: 15px; }
.empty-inline { padding: 10px 0; color: #64748b; font-size: 13px; }
@media (max-width: 760px) {
  .inline-grid { grid-template-columns: 1fr; }
  .analyze-action-row { justify-content: stretch; }
  .analyze-button { width: 100%; }
}
</style>
