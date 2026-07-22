<template>
  <section class="panel">
    <section class="card picker"><label>分析基线<select v-model="baselineId" :disabled="busy" @change="loadState"><option value="">请选择</option><option v-for="b in baselines" :key="b.id" :value="b.id">{{ b.name }} · {{ statusText(b.status) }} · {{ commit(b.sourceCommit) }}</option></select></label><span v-if="baseline" class="meta">{{ statusText(baseline.status) }} · Commit {{ commit(baseline.sourceCommit) }} · {{ time(baseline.updateTime) }}</span></section>
    <template v-if="baseline">
      <section class="summary"><article><span>AI 分析</span><strong>{{ jobText(job?.status) }}</strong><small>{{ job?.message || '尚未运行' }}</small></article><article><span>静态图</span><strong :class="graph?.staticReady ? 'ok' : ''">{{ graph?.staticReady ? '已就绪' : '未构建' }}</strong><small>代码、控制流、依赖</small></article><article><span>动态证据</span><strong :class="graph?.runtimeReady ? 'ok' : ''">{{ graph?.runtimeReady ? '已就绪' : '未构建' }}</strong><small>覆盖率、分支、执行</small></article><article><span>追溯图</span><strong :class="graph?.traceabilityReady ? 'ok' : ''">{{ graph?.traceabilityReady ? '已就绪' : '未构建' }}</strong><small>AC、用例与实现</small></article></section>
      <section class="grid">
        <article class="card"><h2>01 全量构建当前基线</h2><p>依次构建静态图、控制流、依赖、覆盖率、分支、测试执行、追溯图和读模型，最后提交 AI 分析。</p><ol><li v-for="s in steps" :key="s.key" :class="stepClass(s.key)">{{ s.label }} <small>{{ stepHint(s.key) }}</small></li></ol><div class="actions"><button class="primary" :disabled="busy" @click="fullBuild">{{ busy ? `执行中：${runningLabel}` : '一键全量构建' }}</button><RouterLink :to="`/p/${projectId}/verification/baselines/${baselineId}/graph`" class="secondary">事实图谱</RouterLink></div></article>
        <article class="card"><h2>02 按变更增量分析</h2><p>失效关联快照，并仅重算受影响的符号、AC、用例与聚合。</p><label>变更类型<select v-model="changeType" :disabled="busy"><option v-for="t in types" :key="t.key" :value="t.key">{{ t.label }}</option></select></label><label>受影响符号（代码变更必填，每行一个）<textarea v-model="symbols" :disabled="busy" rows="5" placeholder="com.example.OrderService#submit" /></label><p v-if="result" class="result">{{ result }}</p><div class="actions"><button class="primary" :disabled="busy" @click="incremental">执行增量分析</button><RouterLink :to="`/p/${projectId}/git-impact`" class="secondary">Git 影响</RouterLink></div></article>
      </section>
      <section class="card task"><div><h2>03 后台任务状态</h2><p>AI 分析会自动轮询最新状态；图谱构建步骤在当前页面顺序执行。</p></div><div><strong :class="job?.status?.toLowerCase()">{{ jobText(job?.status) }}</strong><span>{{ job?.message || '没有执行中的 AI 分析任务。' }}</span><small v-if="job">更新于 {{ time(job.updateTime) }}</small></div></section>
    </template>
    <div v-else-if="!loading" class="empty">请选择或创建分析基线后开始编排。</div>
  </section>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,ref,watch } from 'vue'
import { useToast } from '@/composables/useToast'
import { analyzeBaseline,fetchLatestAnalysisJob,fetchVerificationOverview,type AnalysisJob,type VerificationBaseline } from '@/api/verification'
import { applyDiffInvalidation,fetchGraphView,incrementalRecompute,projectBranchCoverageGraph,projectControlFlowGraph,projectRuntimeCoverageGraph,projectStaticDependencyGraph,projectStaticGraph,projectTestExecutionGraph,projectTraceabilityGraph,rebuildReadModels,type DiffChangeType,type GraphSummary } from '@/api/graph'
const props=defineProps<{projectId:string;initialBaselineId?:string}>();const toast=useToast()
const loading=ref(false),busy=ref(false),baselines=ref<VerificationBaseline[]>([]),baselineId=ref(props.initialBaselineId||''),job=ref<AnalysisJob|null>(null),graph=ref<GraphSummary|null>(null),symbols=ref(''),result=ref(''),changeType=ref<DiffChangeType>('CODE'),running=ref(''),done=ref<string[]>([])
let timer:ReturnType<typeof setTimeout>|null=null
const baseline=computed(()=>baselines.value.find(x=>x.id===baselineId.value));const runningLabel=computed(()=>steps.find(x=>x.key===running.value)?.label||'准备中')
const steps=[{key:'static',label:'静态图'},{key:'cfg',label:'控制流图'},{key:'dependency',label:'依赖图'},{key:'coverage',label:'覆盖率'},{key:'branch',label:'分支覆盖'},{key:'execution',label:'测试执行'},{key:'trace',label:'追溯图'},{key:'read',label:'读模型'},{key:'ai',label:'AI 分析'}] as const
const types:Array<{key:DiffChangeType;label:string}>=[{key:'CODE',label:'代码变更'},{key:'REQUIREMENT',label:'需求变更'},{key:'TESTCASE',label:'用例变更'},{key:'CONFIG_SQL_API',label:'配置 / SQL / API'},{key:'COVERAGE',label:'覆盖率变更'},{key:'EXECUTION_TRACE',label:'执行轨迹'},{key:'PROMPT_MODEL_RULE',label:'Prompt / 模型 / 规则'},{key:'ANALYZER_UPGRADE',label:'分析器升级'}]
watch(()=>props.initialBaselineId,v=>{if(v&&v!==baselineId.value){baselineId.value=v;loadState()}});onMounted(load);onBeforeUnmount(clearTimer)
async function load(){loading.value=true;try{const o=await fetchVerificationOverview(props.projectId);baselines.value=o.baselines;if(!baselineId.value&&o.baselines[0])baselineId.value=o.baselines[0].id;await loadState()}finally{loading.value=false}}
async function loadState(){clearTimer();job.value=null;graph.value=null;if(!baselineId.value)return;const[j,g]=await Promise.allSettled([fetchLatestAnalysisJob(props.projectId,baselineId.value),fetchGraphView(props.projectId,{baselineId:baselineId.value,maxNodes:1,maxEdges:1})]);if(j.status==='fulfilled'){job.value=j.value;if(['QUEUED','RUNNING'].includes(j.value.status))poll()}if(g.status==='fulfilled')graph.value=g.value.summary}
async function fullBuild(){
  if(busy.value||!baselineId.value)return
  busy.value=true
  done.value=[]
  const p=props.projectId
  const b=baselineId.value
  type BuildTask=()=>Promise<unknown>
  const run:Record<string,BuildTask>={
    static:()=>projectStaticGraph(p,b),
    cfg:()=>projectControlFlowGraph(p,b),
    dependency:()=>projectStaticDependencyGraph(p,b),
    coverage:()=>projectRuntimeCoverageGraph(p,b),
    branch:()=>projectBranchCoverageGraph(p,b),
    execution:()=>projectTestExecutionGraph(p,b),
    trace:()=>projectTraceabilityGraph(p,b),
    read:()=>rebuildReadModels(p,b),
    ai:()=>analyzeBaseline(p,b),
  }
  try{
    for(const s of steps){
      running.value=s.key
      const v=await run[s.key]()
      done.value.push(s.key)
      if(s.key==='ai')job.value=v as AnalysisJob
    }
    toast.success('全量构建已提交')
    await loadState()
  }catch(e){
    toast.error(`全量构建在「${runningLabel.value}」失败：${msg(e)}`)
  }finally{
    running.value=''
    busy.value=false
  }
}
async function incremental(){if(busy.value||!baselineId.value)return;const list=symbols.value.split('\n').map(x=>x.trim()).filter(Boolean);if(changeType.value==='CODE'&&!list.length){toast.warning('代码变更请填写受影响符号');return}busy.value=true;result.value='';try{const d=await applyDiffInvalidation(props.projectId,baselineId.value,changeType.value);const r=list.length?await incrementalRecompute(props.projectId,baselineId.value,list):null;result.value=`${typeText(d.changeType)}：失效快照 ${d.invalidatedSnapshotKinds.join('、')||'无'}；${r?`受影响节点 ${r.affectedNodeCount}，AC ${r.affectedAcIds.length}，用例 ${r.affectedTestcaseIds.length}。`:'已完成失效传播。'}`;toast.success('增量分析完成');await loadState()}catch(e){toast.error(msg(e))}finally{busy.value=false}}
function poll(){clearTimer();timer=setTimeout(async()=>{try{if(!baselineId.value)return;job.value=await fetchLatestAnalysisJob(props.projectId,baselineId.value);if(['QUEUED','RUNNING'].includes(job.value.status))poll()}catch{}},2500)}function clearTimer(){if(timer){clearTimeout(timer);timer=null}}function stepClass(k:string){return running.value===k?'running':done.value.includes(k)?'done':''}function stepHint(k:string){return running.value===k?'执行中':done.value.includes(k)?'完成':'待执行'}function statusText(v?:string){return({CREATED:'待分析',ANALYZING:'分析中',COMPLETED:'已完成',FAILED:'失败',STALE:'已过期'}as Record<string,string>)[v||'']||v||'-'}function jobText(v?:string){return({QUEUED:'排队中',RUNNING:'执行中',SUCCEEDED:'已完成',FAILED:'失败'}as Record<string,string>)[v||'']||'未运行'}function typeText(v:string){return types.find(x=>x.key===v)?.label||v}function commit(v?:string){return v?v.slice(0,10):'未绑定'}function time(v?:string){return v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'-'}function msg(e:unknown){return e instanceof Error?e.message:'操作失败'}
</script>
<style scoped>
.panel{display:grid;gap:14px}.card,.empty{border:1px solid var(--oat-border);border-radius:12px;background:#fff;padding:15px}.picker,.actions,.task{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.picker label,.card label{display:grid;gap:5px;font-size:12px;font-weight:800;color:var(--oat-text-secondary)}select,textarea{border:1px solid var(--oat-border);border-radius:8px;padding:8px;background:#fff;color:var(--oat-text)}.picker label{min-width:min(100%,430px)}.meta,small{font-size:12px;color:var(--oat-text-muted)}.summary,.grid{display:grid;gap:12px;grid-template-columns:repeat(4,minmax(0,1fr))}.summary article{display:grid;gap:4px;border:1px solid var(--oat-border);border-radius:12px;padding:13px;background:#fff}.summary span{font-size:12px;color:var(--oat-text-muted)}.summary strong{font-size:18px}.ok,.succeeded{color:#15803d}.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.grid .card{display:grid;gap:12px}.card h2{margin:0;font-size:16px}.card p{margin:0;color:var(--oat-text-secondary);font-size:13px;line-height:1.6}ol{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;list-style:none;padding:0;margin:0}li{display:grid;gap:2px;padding:7px;border:1px solid var(--oat-border);border-radius:7px;font-size:12px;font-weight:800;color:var(--oat-text-muted)}li.running{border-color:var(--oat-primary);color:var(--oat-primary)}li.done{border-color:#86efac;color:#15803d;background:#f0fdf4}.primary,.secondary{min-height:34px;border:1px solid var(--oat-border);border-radius:8px;padding:6px 12px;font-weight:800;font-size:13px;text-decoration:none;background:var(--oat-surface-soft);color:var(--oat-text);cursor:pointer}.primary{background:var(--oat-primary);border-color:var(--oat-primary);color:#fff}.primary:disabled{opacity:.45}.result{padding:9px;border-radius:8px;background:var(--oat-surface-soft);font-size:12px}.task>div{display:grid;gap:4px}.task>div:last-child{text-align:right}.task strong.running,.task strong.queued{color:var(--oat-primary)}.task strong.failed{color:var(--oat-danger)}.empty{text-align:center;padding:48px;color:var(--oat-text-muted)}@media(max-width:900px){.summary,.grid{grid-template-columns:1fr}.task>div:last-child{text-align:left}ol{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
