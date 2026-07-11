<template>
  <RelationBoard
    eyebrow="Code Map"
    title="源码链路图"
    subtext="按 traceId 展示调用链中的代码节点，节点之间按真实调用关系连线。点击节点可高亮上下游关系。"
    :loading="loading"
    :error="error"
    :nodes="nodes"
    :edges="edges"
    :highlight-related="true"
    :show-edge-labels="false"
    :back-route="backRoute"
    :back-label="backLabel"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import RelationBoard from '@/components/map/RelationBoard.vue'
import { fetchMapCode } from '@/api/bootstrap'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || ''))
const traceId = computed(() => String(route.query.traceId || ''))
const backRoute = computed(() => sanitizeBackRoute(String(route.query.backRoute || ''), `/p/${projectId.value}/search`))
const backLabel = computed(() => String(route.query.backLabel || '返回搜索中心'))
const loading = ref(false)
const error = ref('')
const elements = ref<Awaited<ReturnType<typeof fetchMapCode>>>([])

const nodes = computed(() =>
  elements.value
    .filter((item) => item.group === 'nodes')
    .map((item) => ({
      id: item.data.id,
      label: item.data.name || item.data.id,
      type: item.classes?.join(','),
      description: item.data.describe,
      meta: [
        item.data.packageAndClassName ? `类 ${item.data.packageAndClassName}` : '',
        item.data.methodName ? `方法 ${item.data.methodName}` : '',
      ].filter(Boolean),
    })),
)

const edges = computed(() => {
  const labelMap = new Map(nodes.value.map((node) => [node.id, node.label]))
  return elements.value
    .filter((item) => item.group === 'edges' && item.data.source && item.data.target)
    .map((item) => ({
      id: item.data.id,
      source: item.data.source as string,
      target: item.data.target as string,
      label: relationLabel(item.data.name || item.data.methodName),
      action: item.data.name || item.data.methodName,
      sourceLabel: labelMap.get(item.data.source as string),
      targetLabel: labelMap.get(item.data.target as string),
    }))
})

function relationLabel(value?: string) {
  const normalized = String(value || '').toLowerCase()
  if (normalized === 'entry' || normalized === 'start') return '入口'
  if (normalized === 'static invoke') return '静态调用'
  if (normalized === 'runtime sequence') return '执行关联'
  if (normalized === 'invoke') return '调用'
  return value || '调用'
}

function sanitizeBackRoute(value: string, fallback: string) {
  if (!value || !value.startsWith('/p/')) return fallback
  if (value.includes('://') || value.startsWith('//')) return fallback
  return value
}

async function load() {
  if (!traceId.value) {
    error.value = '缺少 traceId'
    return
  }
  loading.value = true
  error.value = ''
  try {
    elements.value = await fetchMapCode(projectId.value, traceId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载源码地图失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
