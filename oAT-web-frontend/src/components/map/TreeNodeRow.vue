<template>
  <!-- directory node -->
  <div v-if="node.isDir" class="tree-node">
    <button
      class="tree-row tree-row--dir"
      :style="{ paddingLeft: `${8 + depth * 14}px` }"
      @click="$emit('toggle-dir', node.key)"
    >
      <span class="tree-icon-toggle">{{ openDirs.has(node.key) ? '▾' : '▸' }}</span>
      <span class="tree-icon-file">📁</span>
      <span class="tree-label" :title="node.key">{{ node.displayName }}</span>
      <em class="tree-count">{{ countFiles(node) }}</em>
    </button>
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
        :get-link-count="getLinkCount"
        @toggle-dir="$emit('toggle-dir', $event)"
        @toggle-class="$emit('toggle-class', $event)"
        @select="$emit('select', $event)"
      />
    </template>
  </div>

  <!-- file / class node -->
  <div v-else-if="node.file" class="tree-node">
    <button
      :class="['tree-row', 'tree-row--file', { active: selectedId === node.file.nodeId, linked: linkedIds.has(node.file.nodeId) }]"
      :style="{ paddingLeft: `${8 + depth * 14}px` }"
      @click="$emit('toggle-class', node.file.id); $emit('select', node.file.nodeId)"
    >
      <span class="tree-icon-toggle">{{ openClasses.has(node.file.id) ? '▾' : '▸' }}</span>
      <span class="tree-icon-file">{{ node.file.name.endsWith('.java') || node.name.endsWith('java') ? '☕' : '📄' }}</span>
      <span class="tree-label" :title="node.file.name || node.name">{{ node.name }}</span>
      <em v-if="linkedIds.has(node.file.nodeId)" class="tree-count tree-count--linked">{{ linkCount(node.file.nodeId) }}</em>
    </button>
    <div v-if="openClasses.has(node.file.id)" class="tree-methods-block">
      <button
        v-for="method in node.file.methods"
        :key="method.nodeId"
        :class="['tree-row', 'tree-row--method', { active: selectedId === method.nodeId, linked: linkedIds.has(method.nodeId) }]"
        :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }"
        @click="$emit('select', method.nodeId)"
      >
        <span class="tree-icon-method">{{ linkedIds.has(method.nodeId) ? '◉' : '○' }}</span>
        <span class="tree-label tree-label--method" :title="methodTitle(method)">{{ method.name }}</span>
        <span v-if="method.line" class="tree-lineno">L{{ method.line }}</span>
        <em v-if="linkedIds.has(method.nodeId)" class="tree-count tree-count--linked">{{ linkCount(method.nodeId) }}</em>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'TreeNodeRow' })

interface CodeTreeMethod { nodeId: string; name: string; line?: number }
interface TreeNode {
  key:         string
  name:        string
  displayName: string
  isDir:       boolean
  children:    TreeNode[]
  file?: { id: string; nodeId: string; name: string; methods: CodeTreeMethod[] }
}

const props = defineProps<{
  node:        TreeNode
  depth:       number
  openDirs:    Set<string>
  openClasses: Set<string>
  selectedId:  string
  linkedIds:   Set<string>
  getLinkCount: (nodeId: string) => number
}>()

const emit = defineEmits<{
  (e: 'toggle-dir',   key: string): void
  (e: 'toggle-class', id: string):  void
  (e: 'select',       id: string):  void
}>()

function countFiles(node: TreeNode): number {
  if (!node.isDir) return node.file ? 1 : 0
  return node.children.reduce((sum, c) => sum + countFiles(c), 0)
}

function linkCount(nodeId: string): number {
  return props.getLinkCount(nodeId)
}

function methodTitle(method: CodeTreeMethod): string {
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

.tree-icon-toggle { flex-shrink: 0; width: 12px; color: #9ca3af; font-size: 10px; }
.tree-icon-file   { flex-shrink: 0; font-size: 13px; }
.tree-icon-method { flex-shrink: 0; width: 14px; text-align: center; color: #9ca3af; font-size: 11px; }
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
</style>
