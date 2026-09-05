<template>
  <div v-if="node.isDir" class="tree-node">
    <div class="tree-row tree-row--dir" :style="{ paddingLeft: `${8 + depth * 14}px` }">
      <button type="button" class="tree-toggle-button" :aria-label="openDirs.has(node.key) ? '折叠目录' : '展开目录'" @click.stop="$emit('toggle-dir', node.key)">
        {{ openDirs.has(node.key) ? '▾' : '▸' }}
      </button>
      <span class="tree-icon-dir">📁</span>
      <span class="tree-label" :title="node.key">{{ node.displayName }}</span>
      <em class="tree-count">{{ countDescendants(node) }}</em>
    </div>
    <template v-if="openDirs.has(node.key)">
      <TreeNodeRow
        v-for="child in node.children"
        :key="child.key"
        :node="child"
        :depth="depth + 1"
        :open-dirs="openDirs"
        :open-classes="openClasses"
        :selected-id="selectedId"
        :linked-ids="linkedIds"
        :loading-ids="loadingIds"
        :get-link-count="getLinkCount"
        @toggle-dir="$emit('toggle-dir', $event)"
        @toggle-class="$emit('toggle-class', $event)"
        @select="$emit('select', $event)"
      />
    </template>
  </div>

  <div v-else-if="node.file" class="tree-node">
    <div
      :class="['tree-row', 'tree-row--file', { active: selectedId === node.file.nodeId, linked: linkedIds.has(node.file.nodeId) }]"
      :style="{ paddingLeft: `${8 + depth * 14}px` }"
      role="button"
      tabindex="0"
      @click="$emit('select', node.file.nodeId)"
      @keydown.enter.prevent="$emit('select', node.file.nodeId)"
      @keydown.space.prevent="$emit('select', node.file.nodeId)"
    >
      <button
        v-if="isExpandable"
        type="button"
        class="tree-toggle-button"
        :aria-label="isOpen ? '折叠' : '展开'"
        @click.stop="$emit('toggle-class', node.file.id)"
      >
        {{ isOpen ? '▾' : '▸' }}
      </button>
      <span v-else class="tree-toggle-spacer"></span>
      <span :class="['tree-icon-file', { class: isClassNode }]">{{ isClassNode ? '◎' : '📄' }}</span>
      <span class="tree-label" :title="node.displayName">{{ node.displayName }}</span>
      <em v-if="countLabel" class="tree-count" :class="{ 'tree-count--method': isClassNode, 'tree-count--class': !isClassNode }">{{ countLabel }}</em>
      <em v-if="linkedIds.has(node.file.nodeId)" class="tree-count tree-count--linked">{{ linkCount(node.file.nodeId) }}</em>
    </div>

    <template v-if="isOpen">
      <TreeNodeRow
        v-for="child in node.children"
        :key="child.key"
        :node="child"
        :depth="depth + 1"
        :open-dirs="openDirs"
        :open-classes="openClasses"
        :selected-id="selectedId"
        :linked-ids="linkedIds"
        :loading-ids="loadingIds"
        :get-link-count="getLinkCount"
        @toggle-dir="$emit('toggle-dir', $event)"
        @toggle-class="$emit('toggle-class', $event)"
        @select="$emit('select', $event)"
      />

      <div v-if="isClassNode && loadingIds.has(node.file.id)" class="tree-method-loading" :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }">
        加载方法…
      </div>
      <button
        v-for="method in node.file.methods"
        :key="method.nodeId"
        :class="['tree-row', 'tree-row--method', { active: selectedId === method.nodeId, linked: linkedIds.has(method.nodeId) }]"
        :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }"
        type="button"
        @click="$emit('select', method.nodeId)"
      >
        <span class="tree-icon-method">{{ linkedIds.has(method.nodeId) ? '◉' : '○' }}</span>
        <span class="tree-label tree-label--method" :title="methodTitle(method)">{{ method.name }}</span>
        <span v-if="method.line" class="tree-lineno">L{{ method.line }}</span>
        <em v-if="linkedIds.has(method.nodeId)" class="tree-count tree-count--linked">{{ linkCount(method.nodeId) }}</em>
      </button>
      <div v-if="isClassNode && !loadingIds.has(node.file.id) && node.file.methods.length === 0 && node.file.loaded" class="tree-method-empty" :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }">
        无方法级数据
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

defineOptions({ name: 'TreeNodeRow' })

interface CodeTreeMethod {
  nodeId: string
  name: string
  line?: number
}

interface TreeNode {
  key: string
  name: string
  displayName: string
  isDir: boolean
  children: TreeNode[]
  file?: {
    id: string
    nodeId: string
    name: string
    methods: CodeTreeMethod[]
    methodCount?: number
    loaded?: boolean
    loading?: boolean
  }
}

const props = defineProps<{
  node: TreeNode
  depth: number
  openDirs: Set<string>
  openClasses: Set<string>
  selectedId: string
  linkedIds: Set<string>
  loadingIds: Set<string>
  getLinkCount: (nodeId: string) => number
}>()

const emit = defineEmits<{
  (e: 'toggle-dir', key: string): void
  (e: 'toggle-class', id: string): void
  (e: 'select', id: string): void
}>()

const isClassNode = computed(() => props.node.file?.methodCount !== undefined)
const isOpen = computed(() => props.node.file ? props.openClasses.has(props.node.file.id) : false)
const isExpandable = computed(() => props.node.children.length > 0 || isClassNode.value)
const countLabel = computed(() => {
  if (!props.node.file) return ''
  if (isClassNode.value) return props.node.file.methodCount !== undefined ? `${props.node.file.methodCount} 方法` : ''
  return props.node.children.length ? `${props.node.children.length} 类` : ''
})

function countDescendants(node: TreeNode): string {
  let count = 0
  const stack = [...node.children]
  while (stack.length) {
    const current = stack.pop() as TreeNode
    if (current.file) {
      count += current.file.methodCount !== undefined ? current.file.methodCount : 1
    }
    stack.push(...current.children)
  }
  return `${count} 项`
}

function linkCount(nodeId: string) {
  return props.getLinkCount(nodeId)
}

function methodTitle(method: CodeTreeMethod) {
  return method.line ? `${method.name} · L${method.line}` : method.name
}
</script>

<style scoped>
.tree-node { display: contents; }

.tree-row {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  border: 0;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  padding-top: 4px;
  padding-bottom: 4px;
  padding-right: 8px;
  min-width: 0;
}

.tree-toggle-button,
.tree-toggle-spacer {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
}

.tree-toggle-button {
  display: inline-grid;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  font-size: 11px;
  line-height: 1;
  cursor: pointer;
}
.tree-toggle-button:hover {
  background: #e2e8f0;
  color: #0f766e;
}
.tree-toggle-button:focus-visible {
  outline: 2px solid #99f6e4;
  outline-offset: 1px;
}

.tree-row--dir  { color: #374151; font-size: 12.5px; font-weight: 700; }
.tree-row--dir:hover  { background: #f1f5f9; }

.tree-row--file { color: #4b5563; font-size: 12px; font-weight: 600; }
.tree-row--file:hover  { background: #f5f3ff; }
.tree-row--file.active { background: #ede9fe; color: #6d28d9; }
.tree-row--file.linked .tree-label { color: #6d28d9; }

.tree-row--method { color: #6b7280; font-size: 11.5px; }
.tree-row--method:hover  { background: #faf5ff; }
.tree-row--method.active { background: #ede9fe; color: #6d28d9; }
.tree-row--method.linked .tree-label--method { color: #7c3aed; font-weight: 600; }

.tree-icon-dir,
.tree-icon-file,
.tree-icon-method {
  flex-shrink: 0;
}

.tree-icon-file { font-size: 13px; }
.tree-icon-file.class { color: #0f766e; }
.tree-icon-method { width: 14px; text-align: center; color: #9ca3af; font-size: 11px; }
.tree-row--method.linked .tree-icon-method { color: #7c3aed; }

.tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-lineno { flex-shrink: 0; color: #9ca3af; font-size: 10.5px; }

.tree-count {
  flex-shrink: 0;
  border-radius: 999px;
  padding: 1px 6px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
}
.tree-count--linked { background: #ede9fe; color: #6d28d9; }
.tree-count--method { background: #f1f5f9; color: #64748b; }
.tree-count--class { background: #ecfeff; color: #0f766e; }

.tree-method-loading,
.tree-method-empty {
  padding: 6px 8px;
  font-size: 11px;
  color: #94a3b8;
}
.tree-method-loading::before {
  content: "◌ ";
}
</style>
