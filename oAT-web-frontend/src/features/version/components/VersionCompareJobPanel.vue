<template>
  <section v-if="job" class="panel job-panel" aria-live="polite">
    <div class="panel-head job-head">
      <div>
        <h2>当前任务</h2>
        <p class="subtext">{{ job.name || fallbackName }}</p>
      </div>
      <div class="job-state-stack">
        <span :class="['job-state', job.error ? 'error' : job.finish ? 'done' : 'running']">{{ stateText }}</span>
        <strong>{{ progress }}%</strong>
      </div>
    </div>

    <div class="progress-track" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100">
      <span :style="{ width: `${progress}%` }"></span>
    </div>

    <div class="job-summary-grid">
      <div class="job-summary-item">
        <span>当前阶段</span>
        <strong>{{ job.progressName || job.errorMessage || '-' }}</strong>
      </div>
      <div class="job-summary-item">
        <span>差异类</span>
        <strong>{{ classDiffCount }}</strong>
      </div>
      <div class="job-summary-item">
        <span>差异方法</span>
        <strong>{{ methodDiffCount }}</strong>
      </div>
      <div class="job-summary-item">
        <span>任务编号</span>
        <strong class="mono-text">{{ activeJobId || job.id }}</strong>
      </div>
    </div>

    <div v-if="job.error && job.errorMessage" class="job-error">{{ job.errorMessage }}</div>

    <div class="job-toolbar">
      <label class="check-inline"><input :checked="autoScroll" type="checkbox" @change="$emit('update:autoScroll', ($event.target as HTMLInputElement).checked)" /> 日志自动滚动</label>
      <button class="ghost-button small" type="button" :disabled="!activeJobId || polling" @click="$emit('refresh')">刷新任务</button>
      <button class="ghost-button small" type="button" :disabled="!job.log" @click="$emit('copy-log')">复制日志</button>
      <RouterLink v-if="job.finish && !job.error" class="primary-button small" :to="reportRoute">查看报告</RouterLink>
    </div>

    <div ref="jobLogRef" class="job-log" :class="{ empty: !logLines.length }">
      <div v-if="!logLines.length" class="log-placeholder">任务日志尚未输出，正在等待后端执行...</div>
      <article v-for="group in logGroups" :key="group.name" class="job-log-group">
        <div class="job-log-group-head">
          <strong>{{ group.name }}</strong>
          <span>{{ group.lines.length }}</span>
        </div>
        <div class="job-log-group-body">
          <div v-for="(line, index) in group.lines" :key="`${group.name}-${index}-${line.raw}`" class="job-log-line">
            <span :class="['job-log-marker', line.type]"></span>
            <span class="job-log-time">{{ line.time }}</span>
            <span :class="['job-log-tag', line.type]">{{ logTypeText(line.type) }}</span>
            <span class="job-log-text">{{ line.content }}</span>
          </div>
        </div>
      </article>
    </div>
    <p v-if="pollError" class="error-text">{{ pollError }}</p>
  </section>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { CompareJobSummary } from '@/api/types'

type JobLogLine = { raw: string; time: string; content: string; type: string }
type JobLogGroup = { name: string; lines: JobLogLine[] }

const props = defineProps<{
  job: CompareJobSummary | null
  fallbackName: string
  stateText: string
  progress: number
  classDiffCount: number
  methodDiffCount: number
  activeJobId: string
  polling: boolean
  pollError: string
  autoScroll: boolean
  logLines: string[]
  logGroups: JobLogGroup[]
  reportRoute: string
  logTypeText: (type: string) => string
}>()

defineEmits<{
  (event: 'update:autoScroll', value: boolean): void
  (event: 'refresh'): void
  (event: 'copy-log'): void
}>()

const jobLogRef = ref<HTMLElement | null>(null)

watch(() => props.logLines, async () => {
  if (!props.autoScroll) return
  await nextTick()
  if (jobLogRef.value) {
    jobLogRef.value.scrollTop = jobLogRef.value.scrollHeight
  }
})
</script>

<style scoped>
.panel {
  margin-top: 12px;
  padding: 16px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.panel-head,
.job-toolbar,
.check-inline {
  display: flex;
  gap: 12px;
}

.panel-head {
  justify-content: space-between;
  align-items: center;
}

.job-panel {
  display: grid;
  gap: 14px;
}

.job-head {
  align-items: flex-start;
}

.job-head h2 {
  margin: 0;
}

.subtext,
.error-text {
  color: #64748b;
}

.job-head .subtext {
  margin: 4px 0 0;
}

.job-state-stack {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.job-state-stack strong {
  min-width: 54px;
  color: #0f172a;
  font-size: 24px;
  text-align: right;
}

.job-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.job-state::before {
  content: '';
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
}

.job-state.running {
  background: rgba(37, 99, 235, .10);
  color: #1d4ed8;
}

.job-state.running::before {
  animation: pulse-dot 1s ease-in-out infinite;
}

.job-state.done {
  background: rgba(22, 163, 74, .12);
  color: #15803d;
}

.job-state.error {
  background: rgba(185, 28, 28, .12);
  color: #b91c1c;
}

.progress-track {
  overflow: hidden;
  height: 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, .08);
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #0f766e, #14b8a6, #38bdf8);
  box-shadow: 0 8px 18px rgba(20, 184, 166, .24);
  transition: width .35s ease;
}

.job-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.job-summary-item {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 14px;
  background: rgba(248, 250, 252, .86);
}

.job-summary-item span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.job-summary-item strong {
  overflow: hidden;
  color: #172033;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono-text {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.job-error,
.error-text {
  color: #b91c1c;
}

.job-error {
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(185, 28, 28, .08);
  font-weight: 800;
}

.job-toolbar {
  align-items: center;
  flex-wrap: wrap;
  padding: 10px;
  border-radius: 14px;
  background: rgba(248, 250, 252, .72);
}

.check-inline {
  align-items: center;
  flex-wrap: wrap;
  color: #475569;
  font-weight: 700;
}

.primary-button,
.ghost-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
  text-decoration: none;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease;
}

.ghost-button {
  background: rgba(15, 23, 42, 0.08);
}

.primary-button {
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
  box-shadow: 0 12px 22px rgba(20, 184, 166, .22);
}

.primary-button.small,
.ghost-button.small {
  padding: 7px 11px;
  font-size: 13px;
}

.primary-button:disabled,
.ghost-button:disabled {
  cursor: not-allowed;
  opacity: .58;
}

.job-log {
  display: grid;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  background: rgba(15, 23, 42, .025);
}

.job-log.empty {
  place-items: center;
  min-height: 120px;
}

.log-placeholder {
  color: #64748b;
  font-weight: 700;
}

.job-log-group {
  overflow: hidden;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 14px;
  background: rgba(255, 255, 255, .86);
}

.job-log-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 12px;
  background: rgba(248, 250, 252, .92);
  color: #334155;
}

.job-log-group-head span {
  border-radius: 999px;
  padding: 2px 8px;
  background: rgba(15, 118, 110, .08);
  color: #0f766e;
  font-size: 12px;
  font-weight: 800;
}

.job-log-group-body {
  display: grid;
}

.job-log-line {
  display: grid;
  grid-template-columns: 8px 72px 50px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  padding: 8px 10px;
  border-top: 1px solid rgba(15, 23, 42, .045);
  color: #172033;
  font-size: 13px;
  line-height: 1.5;
}

.job-log-marker {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 999px;
  background: #94a3b8;
}

.job-log-marker.add,
.job-log-tag.add {
  color: #15803d;
}

.job-log-marker.add {
  background: #22c55e;
}

.job-log-marker.update,
.job-log-tag.update,
.job-log-marker.search,
.job-log-tag.search {
  color: #1d4ed8;
}

.job-log-marker.update,
.job-log-marker.search {
  background: #3b82f6;
}

.job-log-marker.delete,
.job-log-tag.delete,
.job-log-marker.error,
.job-log-tag.error {
  color: #b91c1c;
}

.job-log-marker.delete,
.job-log-marker.error {
  background: #ef4444;
}

.job-log-marker.done,
.job-log-tag.done {
  color: #0f766e;
}

.job-log-marker.done {
  background: #14b8a6;
}

.job-log-time {
  color: #64748b;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.job-log-tag {
  font-weight: 900;
}

.job-log-text {
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .job-summary-grid,
  .job-log-line {
    grid-template-columns: 1fr;
  }

  .job-state-stack {
    align-items: flex-start;
    flex-direction: column;
  }
}

@keyframes pulse-dot {
  0%, 100% {
    opacity: .45;
    transform: scale(.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.18);
  }
}
</style>
