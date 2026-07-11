<template>
  <div v-if="visible" class="modal-mask" @click.self="$emit('close')">
    <section class="modal-card">
      <div class="panel-head">
        <h2>选择 {{ target === 'old' ? '旧' : '新' }} Commit</h2>
        <button class="text-danger" type="button" @click="$emit('close')">关闭</button>
      </div>
      <div class="input-action modal-tools">
        <input
          :value="keyword"
          class="text-input"
          type="search"
          placeholder="搜索 Commit、作者、说明"
          aria-label="搜索 Commit"
          @input="$emit('update:keyword', ($event.target as HTMLInputElement).value.trim())"
        />
        <label class="check-inline">
          <input :checked="onlySelectable" type="checkbox" @change="$emit('update:onlySelectable', ($event.target as HTMLInputElement).checked)" />
          只看可选
        </label>
      </div>
      <div class="commit-list">
        <button v-for="item in commits" :key="item.commitId" class="commit-item" type="button" @click="$emit('select', item.commitId)">
          <strong>{{ item.shortCommitId || item.commitId.slice(0, 10) }}</strong>
          <span>{{ item.message || '-' }}</span>
          <small>{{ item.author || '-' }}</small>
        </button>
        <div v-if="!filteredCount" class="empty-card">未找到匹配 Commit</div>
      </div>
      <AppPagination
        v-if="filteredCount > pageSize"
        :page="page"
        :page-size="pageSize"
        :total="filteredCount"
        item-name="个 Commit"
        @update:page="$emit('update:page', $event)"
        @update:page-size="$emit('update:pageSize', $event)"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import AppPagination from '@/components/AppPagination.vue'
import type { GitCommitOption } from '@/api/types'

defineProps<{
  visible: boolean
  target: 'old' | 'new'
  keyword: string
  onlySelectable: boolean
  commits: GitCommitOption[]
  filteredCount: number
  page: number
  pageSize: number
}>()

defineEmits<{
  (event: 'close'): void
  (event: 'select', commitId: string): void
  (event: 'update:keyword', keyword: string): void
  (event: 'update:onlySelectable', onlySelectable: boolean): void
  (event: 'update:page', page: number): void
  (event: 'update:pageSize', pageSize: number): void
}>()
</script>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .44);
}

.modal-card,
.empty-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.modal-card {
  width: min(720px, calc(100vw - 32px));
  max-height: min(760px, calc(100vh - 48px));
  overflow: auto;
}

.panel-head,
.input-action,
.modal-tools,
.check-inline {
  display: flex;
  gap: 12px;
}

.panel-head {
  justify-content: space-between;
  align-items: center;
}

.input-action,
.modal-tools,
.check-inline {
  align-items: center;
  flex-wrap: wrap;
}

.text-input {
  width: 100%;
  min-width: 180px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  padding: 10px 12px;
}

.input-action .text-input {
  flex: 1;
}

.commit-list {
  display: grid;
  gap: 12px;
}

.commit-item {
  display: grid;
  grid-template-columns: 120px 1fr auto;
  gap: 12px;
  align-items: center;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 14px;
  padding: 12px;
  background: #f8fbfb;
  text-align: left;
  cursor: pointer;
}

.commit-item small,
.check-inline {
  color: #64748b;
}

.text-danger {
  border: none;
  background: transparent;
  color: #b91c1c;
  cursor: pointer;
  padding: 0;
  font-weight: 700;
}

@media (max-width: 840px) {
  .commit-item {
    grid-template-columns: 1fr;
  }
}
</style>
