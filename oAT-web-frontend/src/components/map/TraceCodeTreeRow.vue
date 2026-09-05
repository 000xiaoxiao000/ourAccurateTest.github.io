<template>
  <div v-if="node.kind === 'dir'" class="trace-code-tree-node">
    <div class="trace-code-tree-row trace-code-tree-row--dir" :style="{ paddingLeft: `${8 + depth * 14}px` }">
      <button type="button" class="trace-code-tree-toggle" :aria-label="openDirs.has(node.key) ? '折叠包' : '展开包'" @click.stop="$emit('toggle-dir', node.key)">
        {{ openDirs.has(node.key) ? '▾' : '▸' }}
      </button>
      <span class="trace-code-tree-icon">📁</span>
      <span class="trace-code-tree-label" :title="node.displayName">{{ node.displayName }}</span>
      <em class="trace-code-tree-count">{{ node.descendantCount }} 项</em>
    </div>
    <template v-if="openDirs.has(node.key)">
      <TraceCodeTreeRow
        v-for="child in node.children"
        :key="child.key"
        :node="child"
        :depth="depth + 1"
        :open-dirs="openDirs"
        :open-classes="openClasses"
        :selected-ids="selectedIds"
        :linked-ids="linkedIds"
        :loading-ids="loadingIds"
        :get-link-count="getLinkCount"
        @toggle-dir="$emit('toggle-dir', $event)"
        @toggle-class="$emit('toggle-class', $event)"
        @toggle-select="$emit('toggle-select', $event)"
      />
    </template>
  </div>

  <div v-else class="trace-code-tree-node">
    <div class="trace-code-tree-row trace-code-tree-row--item" :style="{ paddingLeft: `${8 + depth * 14}px` }" @click="$emit('toggle-select', node.nodeId)">
      <button
        v-if="node.kind === 'class' && node.expandable"
        type="button"
        class="trace-code-tree-toggle"
        :aria-label="openClasses.has(node.key) ? '折叠类' : '展开类'"
        @click.stop="$emit('toggle-class', node.key)"
      >
        {{ openClasses.has(node.key) ? '▾' : '▸' }}
      </button>
      <span v-else class="trace-code-tree-toggle-spacer"></span>

      <input
        class="trace-code-tree-check"
        type="checkbox"
        :checked="selectedIds.has(node.nodeId)"
        :aria-label="`选择${node.displayName}`"
        @click.stop
        @change="$emit('toggle-select', node.nodeId)"
      />
      <span class="trace-code-tree-icon" :class="node.kind">{{ node.kind === 'class' ? '◎' : '◉' }}</span>
      <span class="trace-code-tree-label" :title="node.title">{{ node.displayName }}</span>
      <span v-if="node.kind === 'method' && node.line" class="trace-code-tree-line">L{{ node.line }}</span>
      <em v-if="node.kind === 'class' && node.methodCount !== undefined" class="trace-code-tree-count trace-code-tree-count--class">{{ node.methodCount }} 方法</em>
      <em v-if="node.linkCount > 0" class="trace-code-tree-count trace-code-tree-count--linked">{{ node.linkCount }}</em>
    </div>

    <template v-if="node.kind === 'class' && openClasses.has(node.key)">
      <div v-if="loadingIds.has(node.key)" class="trace-code-tree-loading" :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }">加载方法…</div>
      <TraceCodeTreeRow
        v-for="child in node.children"
        :key="child.key"
        :node="child"
        :depth="depth + 1"
        :open-dirs="openDirs"
        :open-classes="openClasses"
        :selected-ids="selectedIds"
        :linked-ids="linkedIds"
        :loading-ids="loadingIds"
        :get-link-count="getLinkCount"
        @toggle-dir="$emit('toggle-dir', $event)"
        @toggle-class="$emit('toggle-class', $event)"
        @toggle-select="$emit('toggle-select', $event)"
      />
      <div v-if="!loadingIds.has(node.key) && node.loaded && node.children.length === 0" class="trace-code-tree-empty" :style="{ paddingLeft: `${8 + (depth + 1) * 14}px` }">无方法级数据</div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

defineOptions({ name: 'TraceCodeTreeRow' })

interface TraceCodeTreeNode {
  key: string
  kind: 'dir' | 'class' | 'method'
  displayName: string
  title: string
  children: TraceCodeTreeNode[]
  descendantCount: number
  nodeId: string
  methodCount?: number
  loaded?: boolean
  expandable?: boolean
  line?: number
  linkCount: number
}

const props = defineProps<{
  node: TraceCodeTreeNode
  depth: number
  openDirs: Set<string>
  openClasses: Set<string>
  selectedIds: Set<string>
  linkedIds: Set<string>
  loadingIds: Set<string>
  getLinkCount: (nodeId: string) => number
}>()

defineEmits<{
  (e: 'toggle-dir', key: string): void
  (e: 'toggle-class', key: string): void
  (e: 'toggle-select', nodeId: string): void
}>()

const isLeaf = computed(() => props.node.kind !== 'dir')
</script>

<style scoped>
.trace-code-tree-node { display: contents; }

.trace-code-tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  padding-top: 4px;
  padding-bottom: 4px;
  padding-right: 8px;
}

.trace-code-tree-row:hover { background: #f8fafc; }
.trace-code-tree-row--dir { color: #374151; font-size: 12.5px; font-weight: 700; }
.trace-code-tree-row--item { color: #4b5563; font-size: 12px; font-weight: 600; }
.trace-code-tree-row--item:hover { background: #f5f3ff; }

.trace-code-tree-toggle,
.trace-code-tree-toggle-spacer {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
}

.trace-code-tree-toggle {
  display: inline-grid;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  font-size: 11px;
  cursor: pointer;
}
.trace-code-tree-toggle:hover {
  background: #e2e8f0;
  color: #0f766e;
}

.trace-code-tree-check {
  flex: 0 0 auto;
  accent-color: #0f766e;
}

.trace-code-tree-icon {
  flex: 0 0 auto;
  font-size: 13px;
}
.trace-code-tree-icon.class { color: #0f766e; }
.trace-code-tree-icon.method { color: #7c3aed; }

.trace-code-tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-code-tree-line {
  flex: 0 0 auto;
  color: #9ca3af;
  font-size: 10.5px;
}

.trace-code-tree-count {
  flex: 0 0 auto;
  border-radius: 999px;
  padding: 1px 6px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
}
.trace-code-tree-count--class { background: #ecfeff; color: #0f766e; }
.trace-code-tree-count--linked { background: #ede9fe; color: #6d28d9; }

.trace-code-tree-loading,
.trace-code-tree-empty {
  padding-top: 6px;
  padding-bottom: 6px;
  color: #94a3b8;
  font-size: 11px;
}
.trace-code-tree-loading::before { content: "◌ "; }
</style>
