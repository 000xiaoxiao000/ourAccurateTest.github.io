<template>
  <!-- directory node -->
  <div v-if="node.isDir" class="tree-node">
    <div
      class="tree-row tree-row--dir"
      :style="{ paddingLeft: `${8 + depth * 14}px` }"
    >
      <button type="button" class="tree-toggle-button" :aria-label="openDirs.has(node.key) ? '折叠目录' : '展开目录'" @click.stop="$emit('toggle-dir', node.key)">
        {{ openDirs.has(node.key) ? '▾' : '▸' }}
      </button>
      <span class="tree-icon-file">📁</span>
      <span class="tree-label" :title="node.key">{{ node.displayName }}</span>
      <em class="tree-count">{{ countFiles(node) }}</em>
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

  <!-- file / class node -->
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
      <button type="button" class="tree-toggle-button" :aria-label="openClasses.has(node.file.id) ? '折叠方法' : '展开方法'" @click.stop="$emit('toggle-class', node.file.id)">
        {{ openClasses.has(node.file.id) ? '▾' : '▸' }}
      </button>
      <span class="tree-icon-file">{{ node.file.name.endsWith('.java') || node.name.endsWith('java') ? '☕' : '📄' }}</span>
      <span class="tree-label" :title="node.file.name || node.name">{{ node.name }}</span>
      <em v-if="node.file.methodCount !== undefined" class="tree-count tree-count--method">{{ node.file.methodCount }} 方法</em>
      <em v-if="linkedIds.has(node.file.nodeId)" class="tree-count tree-count--linked">{{ linkCount(node.file.nodeId) }}</em>
    </div>
    <div v-if="openClasses.has(node.file.id)" class="tree-methods-block">
      <div v-if="loadingIds.has(node.file.id)" class="tree-method-loading">加载方法…</div>
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
      <div v-if="!loadingIds.has(node.file.id) && node.file.methods.length === 0 && node.file.loaded" class="tree-method-empty">无方法级数据</div>
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
  file?: { id: string; nodeId: string; name: string; methods: CodeTreeMethod[]; methodCount?: number; loaded?: boolean; loading?: boolean }
}

const props = defineProps<{
  node:        TreeNode
  depth:       number
  openDirs:    Set<string>
  openClasses: Set<string>
  selectedId:  string
  linkedIds:   Set<string>
  loadingIds:  Set<string>
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

.tree-toggle-button {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
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
.tree-count--method { background: #f1f5f9; color: #64748b; }

.tree-method-loading,
.tree-method-empty {
  padding: 6px 8px 6px 34px;
  font-size: 11px;
  color: #94a3b8;
}
.tree-method-loading::before {
  content: "◌ ";
}
</style>
