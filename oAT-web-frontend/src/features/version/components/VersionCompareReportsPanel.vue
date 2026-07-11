<template>
  <section class="panel">
    <div class="panel-head">
      <h2>比对报告</h2>
      <div class="panel-head-actions">
        <span class="report-count">{{ totalCount }} 份</span>
        <button v-if="totalCount > pageSize" class="ghost-button small" type="button" @click="$emit('update:collapsed', !collapsed)">
          {{ collapsed ? '展开' : '收起' }}
        </button>
      </div>
    </div>
    <div v-show="!collapsed" class="report-table-list">
      <article v-for="item in reports" :key="item.id" class="report-row compare-row">
        <div class="report-main">
          <RouterLink class="report-title-link" :to="`/p/${projectId}/version/reports/${item.id}?appId=${appId}`">{{ item.name || item.id }}</RouterLink>
          <div class="record-meta">
            <span class="meta-chip">{{ item.gitBranch ? 'Git 比对' : '制品比对' }}</span>
            <span v-if="item.gitBranch" class="meta-chip">分支：{{ item.gitBranch }}</span>
            <span class="meta-chip old" :title="commitTooltip(item.gitOldCommit || item.sourceVersion)">旧：{{ shortText(item.gitOldCommit || item.sourceVersion) || '-' }}</span>
            <span class="meta-chip new" :title="commitTooltip(item.gitNewCommit || item.targetVersion)">新：{{ shortText(item.gitNewCommit || item.targetVersion) || '-' }}</span>
          </div>
        </div>
        <div class="report-metrics">
          <span><b>{{ item.addClassCount + item.updateClassCount + item.deleteClassCount }}</b>类</span>
          <span><b>{{ item.addMethodCount + item.updateMethodCount + item.deleteMethodCount }}</b>方法</span>
          <span><b>{{ item.impactCaseCount }}</b>用例</span>
        </div>
        <div class="report-time">
          <strong>{{ item.createTimeRelativeText || '-' }}</strong>
          <small>{{ item.createTimeText || '-' }}</small>
        </div>
        <div class="row-actions">
          <RouterLink class="primary-button small" :to="`/p/${projectId}/version/reports/${item.id}?appId=${appId}`">查看</RouterLink>
          <button class="danger-button small" type="button" :disabled="busy" @click="$emit('delete', item.id)">删除</button>
        </div>
      </article>
      <div v-if="!totalCount" class="empty-card">暂无比对报告</div>
    </div>
    <AppPagination
      v-if="!collapsed && totalCount > pageSize"
      :page="page"
      :page-size="pageSize"
      :total="totalCount"
      item-name="份比对报告"
      @update:page="$emit('update:page', $event)"
      @update:page-size="$emit('update:pageSize', $event)"
    />
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppPagination from '@/components/AppPagination.vue'
import type { CompareReportSummary } from '@/api/types'

defineProps<{
  projectId: string
  appId: string
  reports: CompareReportSummary[]
  totalCount: number
  page: number
  pageSize: number
  collapsed: boolean
  busy: boolean
  shortText: (value?: string) => string
  commitTooltip: (value?: string) => string
}>()

defineEmits<{
  (event: 'update:page', page: number): void
  (event: 'update:pageSize', pageSize: number): void
  (event: 'update:collapsed', collapsed: boolean): void
  (event: 'delete', reportId: string): void
}>()
</script>

<style scoped>
.panel,
.empty-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.panel {
  margin-top: 18px;
}

.panel-head,
.panel-head-actions,
.record-meta,
.row-actions {
  display: flex;
  gap: 12px;
}

.panel-head {
  justify-content: space-between;
  align-items: center;
}

.panel-head-actions {
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.report-count {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(var(--oat-accent-rgb), .1);
  color: var(--oat-accent-hover);
  font-size: 12px;
  font-weight: 800;
  line-height: 1;
}

.report-table-list {
  display: grid;
  gap: 12px;
}

.report-row {
  display: grid;
  grid-template-columns: minmax(260px, 1.5fr) minmax(260px, 1fr) minmax(128px, auto) auto;
  gap: 14px;
  align-items: center;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.76);
}

.report-main {
  min-width: 0;
}

.report-title-link {
  display: block;
  overflow: hidden;
  color: #0f172a;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-meta {
  flex-wrap: wrap;
  margin-top: 8px;
}

.meta-chip {
  border-radius: 999px;
  padding: 4px 9px;
  background: rgba(15, 118, 110, 0.08);
  color: #0f766e;
  font-size: 12px;
  font-weight: 700;
}

.meta-chip.old {
  background: rgba(234, 88, 12, .10);
  color: #c2410c;
}

.meta-chip.new {
  background: rgba(37, 99, 235, .10);
  color: #1d4ed8;
}

.report-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.report-metrics span {
  display: grid;
  gap: 2px;
  border-radius: 12px;
  padding: 9px 10px;
  background: rgba(255, 255, 255, .86);
  color: #64748b;
  text-align: center;
}

.report-metrics b {
  color: #0f172a;
  font-size: 18px;
}

.report-time {
  display: grid;
  gap: 4px;
  color: #64748b;
  text-align: right;
  white-space: nowrap;
}

.report-time strong {
  color: #334155;
}

.row-actions {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.primary-button,
.ghost-button,
.danger-button {
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

.danger-button {
  background: rgba(185, 28, 28, .12);
  color: #b91c1c;
}

.small {
  padding: 7px 10px;
  font-size: 12px;
}

button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

@media (max-width: 840px) {
  .report-row {
    grid-template-columns: 1fr;
  }

  .report-time,
  .row-actions {
    justify-content: flex-start;
    text-align: left;
  }
}
</style>
