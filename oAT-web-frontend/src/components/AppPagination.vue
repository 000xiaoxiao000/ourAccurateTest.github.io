<template>
  <nav v-if="total > 0" class="app-pagination" aria-label="分页">
    <p class="page-summary" aria-live="polite">
      <span>显示</span>
      <strong>{{ firstItem }}–{{ lastItem }}</strong>
      <span>/ 共</span>
      <strong>{{ total }}</strong>
      <span>{{ normalizedItemName }}</span>
    </p>

    <div class="page-controls">
      <label v-if="pageSizes.length" class="page-size-control">
        <span>每页</span>
        <select :value="pageSize" aria-label="每页显示数量" @change="changePageSize">
          <option v-for="size in pageSizes" :key="size" :value="size">{{ size }}</option>
        </select>
      </label>

      <div class="page-stepper" role="group" aria-label="分页导航">
        <button type="button" :disabled="safePage <= 1" aria-label="第一页" @click="setPage(1)">首页</button>
        <button type="button" :disabled="safePage <= 1" aria-label="上一页" @click="setPage(safePage - 1)">上一页</button>
        <button
          v-for="pageNumber in pageNumbers"
          :key="pageNumber"
          type="button"
          :class="{ active: pageNumber === safePage }"
          :aria-current="pageNumber === safePage ? 'page' : undefined"
          :aria-label="`第 ${pageNumber} 页`"
          @click="setPage(pageNumber)"
        >
          {{ pageNumber }}
        </button>
        <button type="button" :disabled="safePage >= totalPages" aria-label="下一页" @click="setPage(safePage + 1)">下一页</button>
        <button type="button" :disabled="safePage >= totalPages" aria-label="最后一页" @click="setPage(totalPages)">末页</button>
      </div>

      <span class="page-index">第 {{ safePage }} / {{ totalPages }} 页</span>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'

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
  maxButtons: 5,
})

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize))))
const safePage = computed(() => Math.min(Math.max(1, props.page), totalPages.value))
const firstItem = computed(() => (props.total === 0 ? 0 : (safePage.value - 1) * props.pageSize + 1))
const lastItem = computed(() => Math.min(props.total, safePage.value * props.pageSize))
const normalizedItemName = computed(() => props.itemName.replace(/^条/, '').replace(/^个/, '') || props.itemName)

const pageNumbers = computed(() => {
  const maxButtons = Math.max(3, props.maxButtons)
  const half = Math.floor(maxButtons / 2)
  let from = Math.max(1, safePage.value - half)
  let to = Math.min(totalPages.value, from + maxButtons - 1)
  from = Math.max(1, to - maxButtons + 1)
  const pages: number[] = []
  for (let page = from; page <= to; page += 1) {
    pages.push(page)
  }
  return pages
})

watch([() => props.page, totalPages], () => {
  if (props.page !== safePage.value) {
    emit('update:page', safePage.value)
  }
})

function setPage(page: number) {
  emit('update:page', Math.min(Math.max(1, page), totalPages.value))
}

function changePageSize(event: Event) {
  const value = Number((event.target as HTMLSelectElement).value)
  emit('update:pageSize', value > 0 ? value : props.pageSize)
}
</script>

<style scoped>
.app-pagination {
  position: sticky;
  bottom: 12px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 18px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 12px 30px rgba(15, 23, 42, .08);
  backdrop-filter: saturate(180%) blur(14px);
}

.page-summary {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.page-summary strong {
  color: #0f172a;
  font-size: 15px;
}

.page-controls,
.page-stepper,
.page-size-control {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.page-size-control,
.page-index {
  color: #64748b;
  font-size: 13px;
  font-weight: 700;
}

.page-stepper button,
.page-size-control select {
  min-height: 40px;
  border: 1px solid rgba(15, 118, 110, .18);
  border-radius: 999px;
  background: rgba(15, 118, 110, .06);
  color: #0f766e;
  font-weight: 800;
}

.page-stepper button {
  padding: 8px 13px;
  transition: transform .16s ease, background .16s ease, color .16s ease, box-shadow .16s ease;
}

.page-stepper button:hover:not(:disabled),
.page-stepper button.active {
  transform: translateY(-1px);
  background: #0f766e;
  color: #fff;
  box-shadow: 0 8px 18px rgba(15, 118, 110, .16);
}

.page-stepper button:disabled {
  opacity: .45;
}

.page-size-control select {
  padding: 0 30px 0 12px;
  background-color: #fff;
}

@media (max-width: 720px) {
  .app-pagination,
  .page-controls,
  .page-stepper {
    align-items: stretch;
  }

  .app-pagination,
  .page-controls {
    display: grid;
    grid-template-columns: 1fr;
  }

  .page-stepper {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .page-stepper button[aria-current='page'] {
    grid-column: 1 / -1;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .app-pagination {
    background: #fff;
    backdrop-filter: none;
  }
}
</style>
