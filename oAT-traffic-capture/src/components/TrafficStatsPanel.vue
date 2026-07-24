<script setup lang="ts">
defineProps<{
  stats: {
    protocols: Array<{ label: string; value: number }>
    statuses: Array<{ label: string; value: number }>
    hosts: Array<{ label: string; value: number }>
    timeline: Array<{ label: string; count: number; avgDuration: number }>
  }
}>()
</script>

<template>
  <section class="stats-panel">
    <div class="chart-card">
      <h2>协议分布</h2>
      <div v-for="item in stats.protocols" :key="item.label" class="bar-row">
        <span>{{ item.label }}</span>
        <div class="bar"><i :style="{ width: `${Math.min(100, item.value * 12)}%` }"></i></div>
        <strong>{{ item.value }}</strong>
      </div>
    </div>
    <div class="chart-card">
      <h2>状态分布</h2>
      <div v-for="item in stats.statuses" :key="item.label" class="bar-row">
        <span>{{ item.label }}</span>
        <div class="bar status"><i :style="{ width: `${Math.min(100, item.value * 12)}%` }"></i></div>
        <strong>{{ item.value }}</strong>
      </div>
    </div>
    <div class="chart-card wide">
      <h2>Top Host</h2>
      <div v-for="item in stats.hosts" :key="item.label" class="bar-row host">
        <span :title="item.label">{{ item.label }}</span>
        <div class="bar host-bar"><i :style="{ width: `${Math.min(100, item.value * 10)}%` }"></i></div>
        <strong>{{ item.value }}</strong>
      </div>
    </div>
    <div class="chart-card wide">
      <h2>分钟趋势</h2>
      <div class="timeline">
        <div v-for="item in stats.timeline" :key="item.label" class="tick">
          <i :style="{ height: `${Math.max(6, Math.min(96, item.count * 12))}px` }"></i>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stats-panel {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 30px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
}

.chart-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px;
  min-height: 150px;
}

.chart-card.wide {
  grid-column: span 2;
}

h2 {
  margin: 0 0 10px;
  font-size: 14px;
  color: #262626;
}

.bar-row {
  display: grid;
  grid-template-columns: 64px 1fr 32px;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
  font-size: 12px;
  color: #595959;
}

.bar-row.host {
  grid-template-columns: minmax(100px, 180px) 1fr 32px;
}

.bar-row span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar {
  height: 8px;
  background: #f0f0f0;
  border-radius: 999px;
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  background: #667eea;
}

.bar.status i {
  background: #52c41a;
}

.host-bar i {
  background: #fa8c16;
}

.timeline {
  height: 120px;
  display: flex;
  align-items: flex-end;
  gap: 6px;
  overflow-x: auto;
}

.tick {
  width: 28px;
  flex: 0 0 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #8c8c8c;
}

.tick i {
  width: 12px;
  background: #13c2c2;
  border-radius: 2px 2px 0 0;
}

@media (max-width: 1100px) {
  .stats-panel {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
