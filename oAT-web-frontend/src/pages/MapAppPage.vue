<template>
  <section>
    <div class="toolbar map-toolbar">
      <div class="chip-group">
        <button
          v-for="item in layerOptions"
          :key="item.value"
          type="button"
          :class="['chip-button', selectedLayers.includes(item.value) && 'active']"
          @click="toggleLayer(item.value)"
        >
          {{ item.label }}
        </button>
        <button type="button" :class="['chip-button', showHotLabels && 'active']" @click="showHotLabels = !showHotLabels">关联热度</button>
        <button type="button" :class="['chip-button', highlightRelated && 'active']" @click="highlightRelated = !highlightRelated">高亮关联</button>
      </div>

      <div class="layer-actions">
        <div>
          <strong>{{ selectedNode ? selectedNode.label || selectedNode.id : '图层操作' }}</strong>
          <span>{{ selectedNode ? selectedTypeText : '先在图谱中选择应用、表或远程服务节点' }}</span>
        </div>
        <div class="action-buttons">
          <button v-if="extensionElements.length" type="button" class="danger" :disabled="loadingLayer" @click="clearExtensionLayers">清除扩展图层</button>
        </div>
      </div>
      <div v-if="loadingLayer || activeExtensionLabel || layerError" :class="['layer-status', layerError && 'error']">
        {{ layerError || (loadingLayer ? `正在加载${activeExtensionLabel || '图层'}...` : `已加载：${activeExtensionLabel}`) }}
      </div>
    </div>
    <RelationBoard
      eyebrow="Application Map"
      :title="`应用链路图 · ${appId}`"
      subtext="支持代码层、表结构层等应用关系图谱。"
      :loading="loading"
      :error="error"
      :nodes="nodes"
      :edges="edges"
      :back-route="`/p/${projectId}/apps`"
      back-label="返回应用列表"
      :context-actions="contextActions"
      :show-edge-labels="showHotLabels"
      :highlight-related="highlightRelated"
      @node-select="handleNodeSelect"
      @context-action="handleContextAction"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import RelationBoard from '@/components/map/RelationBoard.vue'
import { fetchMapApp } from '@/api/bootstrap'
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
const appId = computed(() => String(route.params.appId || ''))
const loading = ref(false)
const loadingLayer = ref(false)
const error = ref('')
const layerError = ref('')
const activeExtensionLabel = ref('')
const elements = ref<MapElement[]>([])
const extensionElements = ref<MapElement[]>([])
const selectedLayers = ref<string[]>(['code'])
const selectedNode = ref<RelationNodeSelection | null>(null)
const showHotLabels = ref(false)
const highlightRelated = ref(true)

const layerOptions = [
  { label: '代码层', value: 'code' },
  { label: '表结构层', value: 'table' },
]

const allElements = computed(() => mergeElements(elements.value, extensionElements.value))

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
      meta: buildNodeMeta(item.data, item.classes),
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

const selectedRaw = computed(() => selectedNode.value?.raw as Partial<MapElementData> | undefined)
const selectedClasses = computed(() => selectedNode.value?.classes || selectedNode.value?.type?.split(/\s+/).filter(Boolean) || [])
const selectedTypeText = computed(() => selectedClasses.value.length ? `类型：${selectedClasses.value.join(' / ')}` : '节点')
const contextActions = computed(() => [
  { id: 'refresh-map', label: '刷新图谱', target: 'canvas' as const, disabled: loading.value || loadingLayer.value },
  ...(extensionElements.value.length
    ? [
        { id: 'clear-extension-layers', label: '清除扩展图层', target: 'canvas' as const, danger: true, disabled: loadingLayer.value },
      ]
    : []),
])

function buildNodeMeta(data: MapElementData, classes?: string[]) {
  return [
    classes?.length ? `图层 ${classes.join(' / ')}` : '',
    data.cyclo ? `复杂度 ${data.cyclo}` : '',
    data.appId ? `应用 ${data.appId}` : '',
    data.database ? `库 ${data.database}` : '',
    data.methodName ? `方法 ${data.methodName}` : '',
  ].filter(Boolean)
}

function edgeAction(data: MapElementData) {
  return String((data as MapElementData & { action?: string }).action || '')
}

function toggleLayer(layer: string) {
  selectedLayers.value = selectedLayers.value.includes(layer)
    ? selectedLayers.value.filter((item) => item !== layer)
    : [...selectedLayers.value, layer]
}

function handleNodeSelect(node: RelationNodeSelection | null) {
  selectedNode.value = node
  layerError.value = ''
}

function handleContextAction(actionId: string, node: RelationNodeSelection | null) {
  selectedNode.value = node
  layerError.value = ''
  if (actionId === 'refresh-map') {
    load()
    return
  }
  if (actionId === 'clear-extension-layers') {
    clearExtensionLayers()
  }
}

async function loadExtensionLayer(label: string, loader: () => Promise<MapElement[]>, replaceSecondary: boolean) {
  loadingLayer.value = true
  layerError.value = ''
  activeExtensionLabel.value = label
  try {
    const next = await loader()
    extensionElements.value = replaceSecondary ? next : mergeElements(extensionElements.value, next)
    if (!next.length) {
      layerError.value = `${label}暂无数据`
    }
  } catch (err) {
    layerError.value = err instanceof Error ? err.message : `${label}加载失败`
  } finally {
    loadingLayer.value = false
  }
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
  layerError.value = ''
  activeExtensionLabel.value = ''
  extensionElements.value = []
  try {
    elements.value = await fetchMapApp(projectId.value, appId.value, selectedLayers.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载应用地图失败'
  } finally {
    loading.value = false
  }
}

watch(selectedLayers, load, { deep: true })
onMounted(load)
</script>

<style scoped>
.toolbar {
  margin-bottom: 14px;
}

.map-toolbar {
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.chip-group,
.action-buttons {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.chip-button,
.action-buttons button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  background: rgba(15, 23, 42, 0.08);
  cursor: pointer;
  font-weight: 800;
  transition: transform .12s ease, background .12s ease, color .12s ease, box-shadow .12s ease;
}

.chip-button.active,
.action-buttons button:hover:not(:disabled) {
  background: #0f172a;
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(15, 23, 42, .16);
}

.action-buttons button:active:not(:disabled),
.chip-button:active:not(:disabled) {
  transform: translateY(0);
}

.action-buttons button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.action-buttons .danger {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.layer-actions {
  display: grid;
  grid-template-columns: minmax(220px, .4fr) 1fr;
  gap: 12px;
  align-items: center;
}

.layer-actions div:first-child {
  display: grid;
  gap: 4px;
}

.layer-actions span,
.layer-status {
  color: #64748b;
}

.layer-status {
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
  .layer-actions {
    grid-template-columns: 1fr;
  }
}
</style>
