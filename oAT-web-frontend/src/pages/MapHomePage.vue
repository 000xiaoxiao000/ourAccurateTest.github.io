<template>
  <section>
    <div class="home-map-toolbar">
      <div>
        <strong>{{ selectedNode ? selectedNode.label || selectedNode.id : '项目图谱操作' }}</strong>
        <span>{{ selectedNode ? selectedTypeText : '选择应用节点后可进入应用图谱' }}</span>
      </div>
      <div class="toolbar-actions">
        <button type="button" :class="{ active: showHotLabels }" @click="showHotLabels = !showHotLabels">关联热度</button>
        <button type="button" :class="{ active: highlightRelated }" @click="highlightRelated = !highlightRelated">高亮关联</button>
        <button v-if="canUseAppActions" type="button" @click="openAppMap">查看应用图谱</button>
        <button v-if="extensionElements.length" type="button" class="danger" :disabled="loadingLayer" @click="clearExtensionLayers">清除扩展图层</button>
      </div>
      <div v-if="loadingLayer || activeExtensionLabel || layerError" :class="['layer-status', layerError && 'error']">
        {{ layerError || (loadingLayer ? `正在加载${activeExtensionLabel || '图层'}...` : `已加载：${activeExtensionLabel}`) }}
      </div>
    </div>
    <RelationBoard
      eyebrow="System Map"
      title="项目链路地图"
      subtext="展示项目下应用和外部依赖的关系视图。"
      :loading="loading"
      :error="error"
      :nodes="nodes"
      :edges="edges"
      :context-actions="contextActions"
      :show-edge-labels="showHotLabels"
      :highlight-related="highlightRelated"
      @node-select="handleNodeSelect"
      @context-action="handleContextAction"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import RelationBoard from '@/components/map/RelationBoard.vue'
import { fetchMapHome } from '@/api/bootstrap'
import type { MapElement, MapElementData } from '@/api/types'

interface RelationNodeSelection {
  id: string
  label?: string
  type?: string
  raw?: Record<string, unknown>
  classes?: string[]
}

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId || ''))
const loading = ref(false)
const loadingLayer = ref(false)
const error = ref('')
const layerError = ref('')
const activeExtensionLabel = ref('')
const elements = ref<MapElement[]>([])
const extensionElements = ref<MapElement[]>([])
const selectedNode = ref<RelationNodeSelection | null>(null)
const showHotLabels = ref(false)
const highlightRelated = ref(true)

const allElements = computed(() => mergeElements(elements.value, extensionElements.value))
const selectedClasses = computed(() => selectedNode.value?.classes || selectedNode.value?.type?.split(/\s+/).filter(Boolean) || [])
const selectedTypeText = computed(() => selectedClasses.value.length ? `类型：${selectedClasses.value.join(' / ')}` : '节点')
const canUseAppActions = computed(() => selectedClasses.value.some((item) => item === 'app' || item.includes('app')))
const contextActions = computed(() => [
  { id: 'refresh-map', label: '刷新图谱', target: 'canvas' as const, disabled: loading.value || loadingLayer.value },
  { id: 'open-app-map', label: '详情视图', target: 'app' as const },
  ...(extensionElements.value.length
    ? [{ id: 'clear-extension-layers', label: '清空扩展图层', target: 'canvas' as const, danger: true, disabled: loadingLayer.value }]
    : []),
])

const nodes = computed(() =>
  allElements.value
    .filter((item) => item.group === 'nodes')
    .map((item) => ({
      id: item.data.id,
      label: item.data.name || item.data.id,
      type: item.classes?.join(' '),
      description: item.data.describe,
      raw: item.data as unknown as Record<string, unknown>,
      classes: item.classes,
      meta: [
        item.classes?.length ? `图层 ${item.classes.join(' / ')}` : '',
        item.data.cyclo ? `复杂度 ${item.data.cyclo}` : '',
        item.data.appId ? `应用 ${item.data.appId}` : '',
      ].filter(Boolean),
    })),
)

const edges = computed(() => {
  const labelMap = new Map(nodes.value.map((node) => [node.id, node.label]))
  return allElements.value
    .filter((item) => item.group === 'edges' && item.data.source && item.data.target)
    .map((item) => ({
      id: item.data.id,
      source: item.data.source as string,
      target: item.data.target as string,
      label: item.data.hotName || item.data.methodName || item.data.name || edgeAction(item.data),
      action: edgeAction(item.data),
      sourceLabel: labelMap.get(item.data.source as string),
      targetLabel: labelMap.get(item.data.target as string),
    }))
})

function edgeAction(data: MapElementData) {
  return String(data.action || '')
}

function handleNodeSelect(node: RelationNodeSelection | null) {
  selectedNode.value = node
  layerError.value = ''
}

function handleContextAction(actionId: string, node: RelationNodeSelection | null) {
  selectedNode.value = node
  layerError.value = ''
  if (actionId === 'open-app-map') {
    openAppMap()
    return
  }
  if (actionId === 'refresh-map') {
    load()
    return
  }
  if (actionId === 'clear-extension-layers') {
    clearExtensionLayers()
  }
}

function openAppMap() {
  if (!selectedNode.value?.id) return
  router.push(`/p/${projectId.value}/map/app/${selectedNode.value.id}`)
}

function clearExtensionLayers() {
  extensionElements.value = []
  activeExtensionLabel.value = ''
  layerError.value = ''
}

function mergeElements(base: MapElement[], incoming: MapElement[]) {
  const map = new Map<string, MapElement>()
  ;[...base, ...incoming].forEach((item) => {
    const key = `${item.group || ''}:${item.data.id}:${item.data.source || ''}:${item.data.target || ''}`
    map.set(key, item)
  })
  return Array.from(map.values())
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    elements.value = await fetchMapHome(projectId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载地图失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.home-map-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, .45fr) 1fr;
  gap: 12px;
  align-items: center;
  margin-bottom: 14px;
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.home-map-toolbar > div:first-child {
  display: grid;
  gap: 4px;
}

.home-map-toolbar span,
.layer-status {
  color: #64748b;
}

.toolbar-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.toolbar-actions button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  background: rgba(15, 23, 42, 0.08);
  cursor: pointer;
  font-weight: 800;
  transition: transform .12s ease, background .12s ease, color .12s ease, box-shadow .12s ease;
}

.toolbar-actions button.active,
.toolbar-actions button:hover:not(:disabled) {
  background: #0f172a;
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(15, 23, 42, .16);
}

.toolbar-actions button:active:not(:disabled) {
  transform: translateY(0);
}

.toolbar-actions button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.toolbar-actions .danger {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.layer-status {
  grid-column: 1 / -1;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(15, 118, 110, 0.08);
  font-weight: 700;
}

.layer-status.error {
  color: #b91c1c;
  background: rgba(220, 38, 38, 0.08);
}

@media (max-width: 860px) {
  .home-map-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
