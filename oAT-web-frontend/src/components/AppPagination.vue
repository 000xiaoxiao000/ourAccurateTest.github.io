<template>
  <nav v-if="total > 0" class="app-pagination" aria-label="分页导航">
    <div class="page-summary" aria-live="polite">
      <strong>{{ firstItem }}–{{ lastItem }}</strong>
      <span>/ 共 {{ total }} {{ itemName }}</span>
    </div>

    <div class="page-controls">
      <label v-if="pageSizes.length" class="page-size-control">
        <span class="sr-only">每页显示</span>
        <select :value="pageSize" aria-label="每页显示数量" @change="changePageSize">
          <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条/页</option>
        </select>
      </label>

      <div class="page-stepper" role="group" aria-label="页码">
        <button type="button" class="page-action" :disabled="safePage <= 1" aria-label="上一页" @click="setPage(safePage - 1)">
          <span aria-hidden="true">‹</span><span class="action-label">上一页</span>
        </button>
        <template v-for="item in pageItems" :key="item.key">
          <span v-if="item.type === 'ellipsis'" class="page-ellipsis" aria-hidden="true">•••</span>
          <button
            v-else
            type="button"
            :class="['page-number', { active: item.value === safePage }]"
            :aria-current="item.value === safePage ? 'page' : undefined"
            :aria-label="`第 ${item.value} 页`"
            @click="setPage(item.value)"
          >
            {{ item.value }}
          </button>
        </template>
        <button type="button" class="page-action" :disabled="safePage >= totalPages" aria-label="下一页" @click="setPage(safePage + 1)">
          <span class="action-label">下一页</span><span aria-hidden="true">›</span>
        </button>
      </div>

      <label class="page-jump">
        <span>前往</span>
        <input :value="safePage" type="number" min="1" :max="totalPages" inputmode="numeric" aria-label="跳转页码" @change="jumpToPage" />
        <span>页</span>
      </label>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'

type PageItem =
  | { key: string; type: 'page'; value: number }
  | { key: string; type: 'ellipsis' }

const props = withDefaults(defineProps<{
  page: number
  pageSize: number
  total: number
  itemName?: string
  pageSizes?: number[]
  maxButtons?: number
}>(), {
  itemName: '条',
  pageSizes: () => [10, 20, 50, 100],
  maxButtons: 7,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))
const safePage = computed(() => Math.min(Math.max(1, props.page), totalPages.value))
const firstItem = computed(() => (props.total ? (safePage.value - 1) * props.pageSize + 1 : 0))
const lastItem = computed(() => Math.min(props.total, safePage.value * props.pageSize))

const pageItems = computed<PageItem[]>(() => {
  const pages = totalPages.value
  const maxButtons = Math.max(5, props.maxButtons)
  if (pages <= maxButtons) return Array.from({ length: pages }, (_, index) => ({ key: String(index + 1), type: 'page', value: index + 1 }))

  const siblingCount = Math.max(1, Math.floor((maxButtons - 3) / 2))
  const from = Math.max(2, safePage.value - siblingCount)
  const to = Math.min(pages - 1, safePage.value + siblingCount)
  const items: PageItem[] = [{ key: '1', type: 'page', value: 1 }]
  if (from > 2) items.push({ key: 'start-ellipsis', type: 'ellipsis' })
  for (let value = from; value <= to; value += 1) items.push({ key: String(value), type: 'page', value })
  if (to < pages - 1) items.push({ key: 'end-ellipsis', type: 'ellipsis' })
  items.push({ key: String(pages), type: 'page', value: pages })
  return items
})

watch([() => props.page, totalPages], () => {
  if (props.page !== safePage.value) emit('update:page', safePage.value)
})

function setPage(page: number) {
  emit('update:page', Math.min(Math.max(1, page), totalPages.value))
}

function changePageSize(event: Event) {
  const value = Number((event.target as HTMLSelectElement).value)
  if (value > 0) emit('update:pageSize', value)
}

function jumpToPage(event: Event) {
  setPage(Number((event.target as HTMLInputElement).value))
}
</script>

<style scoped>
.app-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(15, 23, 42, .08);
  color: #64748b;
  font-size: 13px;
}

.page-summary,
.page-controls,
.page-stepper,
.page-size-control,
.page-jump {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-summary { white-space: nowrap; }
.page-summary strong { color: #334155; font-variant-numeric: tabular-nums; }
.page-controls { justify-content: flex-end; flex-wrap: wrap; }

.page-size-control select,
.page-jump input {
  height: 34px;
  border: 1px solid rgba(15, 23, 42, .13);
  border-radius: 8px;
  background: #fff;
  color: #334155;
  font: inherit;
  font-weight: 700;
}

.page-size-control select { padding: 0 26px 0 9px; }
.page-jump input { width: 46px; padding: 0 6px; text-align: center; }
.page-jump { white-space: nowrap; }

.page-stepper { gap: 4px; }
.page-stepper button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  min-width: 34px;
  border: 1px solid rgba(15, 23, 42, .11);
  border-radius: 8px;
  padding: 0 9px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  transition: border-color .16s ease, background .16s ease, color .16s ease;
}

.page-stepper button:hover:not(:disabled) { border-color: rgba(15, 118, 110, .42); background: rgba(15, 118, 110, .07); color: #0f766e; }
.page-stepper .page-number.active { border-color: #0f766e; background: #0f766e; color: #fff; }
.page-stepper button:disabled { cursor: not-allowed; opacity: .4; }
.page-action { gap: 4px; }
.page-ellipsis { width: 26px; color: #94a3b8; text-align: center; letter-spacing: 1px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }

@media (max-width: 760px) {
  .app-pagination { align-items: flex-start; flex-direction: column; }
  .page-controls { width: 100%; justify-content: flex-start; }
  .page-jump { margin-left: auto; }
}

@media (max-width: 500px) {
  .page-controls { gap: 6px; }
  .page-size-control { order: 2; }
  .page-jump { order: 2; }
  .page-action .action-label { display: none; }
}
</style>
