<template>
  <section class="trace-workspace">
    <header class="workspace-toolbar">
      <div>
        <span class="eyebrow">Traceability Workspace</span>
        <strong>需求 · 测试 · 代码追溯</strong>
        <span>{{ baselineStatusText }}</span>
      </div>
      <div class="toolbar-actions">
        <label v-if="overview.baselines.length" class="baseline-select">
          <span>分析基线</span>
          <select v-model="activeBaselineId" :disabled="loading">
            <option v-for="baseline in overview.baselines" :key="baseline.id" :value="baseline.id">
              {{ baseline.name || baseline.id }} · {{ baseline.status }}
            </option>
          </select>
        </label>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div v-if="!overview.baselines.length && !loading && !error" class="workspace-notice">
      暂无分析基线。请先在 AI 验证页面导入需求、用例和源码并执行分析。
    </div>
    <div v-if="error" class="workspace-notice error">{{ error }}</div>

    <div class="trace-columns">
      <!-- 左侧：业务资产 -->
      <aside class="asset-pane">
        <div class="pane-head">
          <strong>业务资产</strong>
          <span>{{ traceStats.requirements }} 需求 · {{ traceStats.testcases }} 用例</span>
        </div>
        <section class="asset-group">
          <div class="group-head"><strong>需求</strong><span>{{ traceStats.requirements }}</span></div>
          <div v-if="!detail?.criteria.length" class="empty-card">暂无需求数据</div>
          <button
            v-for="item in detail?.criteria || []"
            :key="item.id"
            :class="['asset-card', 'requirement', { active: selectedId === requirementNodeId(item.id) }]"
            @click="selectId(requirementNodeId(item.id))"
          >
            <b>{{ item.requirementKey }}/{{ item.acKey }}</b>
            <strong>{{ item.title || item.content }}</strong>
            <small>{{ item.testable ? '可测试' : '待澄清' }} · {{ requirementTestCount(item.id) }} 个关联用例</small>
          </button>
        </section>
        <section class="asset-group testcase-group">
          <div class="group-head"><strong>测试用例</strong><span>{{ traceStats.testcases }}</span></div>
          <div v-if="!detail?.testcases.length" class="empty-card">暂无测试用例数据</div>
          <button
            v-for="item in detail?.testcases || []"
            :key="item.id"
            :class="['asset-card', 'testcase', { active: selectedId === testcaseNodeId(item.id) }]"
            @click="selectId(testcaseNodeId(item.id))"
          >
            <b>{{ item.externalKey || item.id }}</b>
            <strong>{{ item.title || '未命名测试用例' }}</strong>
            <small>{{ testcaseRequirementCount(item.id) }} 个关联需求</small>
          </button>
        </section>
      </aside>

      <!-- 中间：追溯详情面板 -->
      <main class="trace-pane">
        <div v-if="loading" class="trace-empty"><span class="spin">◌</span><span>加载中…</span></div>
        <div v-else-if="!selectedId" class="trace-empty">
          <span class="trace-hint-icon">⇌</span>
          <strong>点击左侧需求或测试用例，或右侧代码节点</strong>
          <span>面板将展示该节点的双向追溯关系</span>
        </div>
        <template v-else>
          <div class="trace-selected-card" :class="selectedNodeTone">
            <div class="tsc-left">
              <span class="tsc-type">{{ selectedNodeTypeLabel }}</span>
              <strong>{{ selectedNodeLabel }}</strong>
              <span v-if="selectedNodeDesc" class="tsc-desc">{{ selectedNodeDesc }}</span>
            </div>
            <button class="tsc-clear" type="button" title="取消选中" @click="selectedId = ''">✕</button>
          </div>

          <div v-if="focusedLinks.length" class="trace-link-groups">
            <section v-for="group in focusedLinks" :key="group.label" class="trace-group">
              <div class="tg-head">
                <span :class="['tg-dot', group.tone]"></span>
                <strong>{{ group.label }}</strong>
                <em>{{ group.items.length }}</em>
              </div>
              <button
                v-for="link in group.items"
                :key="link.id"
                :class="['trace-link-card', group.tone, { active: selectedId === link.id }]"
                @click="selectId(link.id)"
              >
                <span class="tlc-label">{{ link.label }}</span>
                <span v-if="link.sub" class="tlc-sub">{{ link.sub }}</span>
                <span :class="['tlc-rel', group.tone]">{{ group.relLabel }}</span>
              </button>
            </section>
          </div>
          <div v-else class="trace-empty-links">
            该节点暂无追溯关系。导入分析结果后将自动关联需求、用例和代码。
          </div>
        </template>
      </main>

      <!-- 右侧：代码树（可折叠目录/类/方法） -->
      <aside class="code-pane">
        <div class="pane-head">
          <strong>代码树</strong>
          <span>{{ traceStats.sources }} 个符号</span>
        </div>
        <label class="code-search">
          <input v-model.trim="codeKeyword" placeholder="搜索文件、类、方法…" />
        </label>
        <div v-if="!codeTreeFiltered.length" class="empty-card">暂无已导入源码。请先导入源码或连接代码仓库。</div>
        <div v-else class="tree-scroll">
          <TreeNodeRow
            v-for="node in codeTreeFiltered"
            :key="node.key"
            :node="node"
            :depth="0"
            :open-dirs="openDirs"
            :open-classes="openClasses"
            :selected-id="selectedId"
            :linked-ids="linkedNodeIds"
            :get-link-count="sourceLinkCount"
            @toggle-dir="toggleDir"
            @toggle-class="toggleClass"
            @select="selectId"
          />
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchMapSourceTree, fetchProjectApps } from '@/api/bootstrap'
import type { SourceTreeClass } from '@/api/bootstrap'
import { fetchBaselineDetail, fetchVerificationOverview } from '@/api/verification'
import type { BaselineDetail, TraceLink, VerificationBaseline, VerificationFinding, VerificationOverview } from '@/api/verification'
import type { RelationEdge, RelationNode } from '@/features/map/types'
import TreeNodeRow from '@/components/map/TreeNodeRow.vue'

interface CodeTreeMethod { nodeId: string; name: string; line?: number }
interface CodeTreeClass  { id: string; nodeId: string; name: string; methods: CodeTreeMethod[] }
interface CodeTreeDir    { path: string; name: string; classes: CodeTreeClass[] }
interface TreeNode {
  key:         string
  name:        string
  displayName: string   // collapsed path label, e.g. "src/main/java/com/oAT"
  isDir:       boolean
  children:    TreeNode[]
  file?:       CodeTreeClass
}
interface TraceGroup     { label: string; relLabel: string; tone: string; items: { id: string; label: string; sub?: string }[] }

const route            = useRoute()
const projectId        = computed(() => String(route.params.projectId || ''))
const loading          = ref(false)
const error            = ref('')
const overview         = ref<VerificationOverview>(emptyOverview())
const detail           = ref<BaselineDetail | null>(null)
const activeBaselineId = ref('')
const sourceTree       = ref<SourceTreeClass[]>([])
const selectedId       = ref('')
const codeKeyword      = ref('')
const openDirs         = ref<Set<string>>(new Set())
const openClasses      = ref<Set<string>>(new Set())

const activeBaseline = computed(() =>
  overview.value.baselines.find((b) => b.id === activeBaselineId.value) || null)

const baselineStatusText = computed(() => {
  if (!overview.value.baselines.length) return '尚未生成 AI 验证分析基线'
  if (!activeBaseline.value) return '请选择分析基线'
  return `当前基线：${activeBaseline.value.name || activeBaseline.value.id} · ${activeBaseline.value.status}`
})

const traceGraph = computed(() => buildTraceGraph(detail.value))
const nodes      = computed(() => traceGraph.value.nodes)
const edges      = computed(() => traceGraph.value.edges)

// ── code tree hierarchy ──────────────────────────────────────────────────────

function buildTrie(classes: SourceTreeClass[]): TreeNode[] {
  // Each node's children stored as a Map keyed by segment name for O(1) lookup
  interface MutableNode {
    key:      string
    name:     string
    isDir:    boolean
    childMap: Map<string, MutableNode>
    file?:    { id: string; nodeId: string; name: string; methods: CodeTreeMethod[] }
  }

  const rootMap = new Map<string, MutableNode>()

  function ensureDir(map: Map<string, MutableNode>, segment: string, key: string): MutableNode {
    if (!map.has(segment)) {
      map.set(segment, { key, name: segment, isDir: true, childMap: new Map() })
    }
    return map.get(segment)!
  }

  classes.forEach((cls) => {
    const rawPath = cls.filePath
      ? cls.filePath.replace(/\\/g, '/')
      : cls.className.replace(/\./g, '/').replace(/\$.*$/, '') + '.java'
    const filePath = rawPath.endsWith('.java') ? rawPath : rawPath + '.java'
    const parts = filePath.split('/').filter(Boolean)

    let currentMap = rootMap
    let prefix = ''

    parts.forEach((segment, idx) => {
      const key = prefix ? `${prefix}/${segment}` : segment
      prefix = key
      const isLast = idx === parts.length - 1

      if (isLast) {
        const fileName = segment.replace(/\.java$/, '')
        if (!currentMap.has(segment)) {
          currentMap.set(segment, {
            key,
            name:     fileName,
            isDir:    false,
            childMap: new Map(),
            file: {
              id:      cls.id,
              nodeId:  sourceNodeId(`class:${cls.id}`),
              name:    fileName,
              methods: cls.methods.map((m, i) => ({
                nodeId: sourceNodeId(`method:${cls.id}:${i}`),
                name:   m.methodName,
                line:   m.lineNumber ?? undefined,
              })),
            },
          })
        }
      } else {
        const dirNode = ensureDir(currentMap, segment, key)
        currentMap = dirNode.childMap
      }
    })
  })

  function toTreeNodes(map: Map<string, MutableNode>): TreeNode[] {
    return [...map.values()]
      .map((n): TreeNode => ({
        key:         n.key,
        name:        n.name,
        displayName: n.name,
        isDir:       n.isDir,
        children:    toTreeNodes(n.childMap),
        file:        n.file,
      }))
      .sort((a, b) => {
        if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
        return a.name.localeCompare(b.name)
      })
  }

  // Compact single-child dir chains, like VS Code "compact folders":
  // src → main → java → com → oAT becomes one node "src/main/java/com/oAT"
  function compact(nodes: TreeNode[]): TreeNode[] {
    return nodes.map((node) => {
      if (!node.isDir) return node
      const kids = compact(node.children)
      // collapse: this dir has exactly one child that is also a dir (not a file)
      if (kids.length === 1 && kids[0].isDir) {
        const only = kids[0]
        return {
          ...only,
          displayName: `${node.displayName}/${only.displayName}`,
        }
      }
      return { ...node, children: kids }
    })
  }

  return compact(toTreeNodes(rootMap))
}

const codeTrieRoot = computed<TreeNode[]>(() => {
  if (!sourceTree.value.length) return []
  return buildTrie(sourceTree.value)
})

const codeTreeFiltered = computed<TreeNode[]>(() => {
  const kw = codeKeyword.value.toLowerCase().trim()
  if (!kw) return codeTrieRoot.value

  function filterNode(node: TreeNode): TreeNode | null {
    if (!node.isDir) {
      const matchFile    = node.name.toLowerCase().includes(kw)
      const filtMethods  = node.file?.methods.filter((m) => m.name.toLowerCase().includes(kw)) ?? []
      if (!matchFile && filtMethods.length === 0) return null
      return { ...node, file: node.file ? { ...node.file, methods: matchFile ? node.file.methods : filtMethods } : undefined }
    }
    const filteredChildren = node.children.map(filterNode).filter(Boolean) as TreeNode[]
    if (!filteredChildren.length) return null
    return { ...node, children: filteredChildren }
  }

  return codeTrieRoot.value.map(filterNode).filter(Boolean) as TreeNode[]
})

const allCodeTreeDirs = computed<CodeTreeDir[]>(() => {
  const dirs = new Map<string, CodeTreeDir>()
  sourceTree.value.forEach((cls) => {
    const rawPath = cls.filePath
      ? cls.filePath.replace(/\\/g, '/')
      : cls.className.replace(/\./g, '/').replace(/\$.*$/, '') + '.java'
    const filePath = rawPath.endsWith('.java') ? rawPath : rawPath + '.java'
    const lastSlash = filePath.lastIndexOf('/')
    const dirPath   = lastSlash >= 0 ? filePath.substring(0, lastSlash) : '.'
    const fileName  = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath
    if (!dirs.has(dirPath)) dirs.set(dirPath, { path: dirPath, name: dirPath, classes: [] })
    dirs.get(dirPath)!.classes.push({
      id: cls.id, nodeId: sourceNodeId(`class:${cls.id}`),
      name: fileName.replace(/\.java$/, ''),
      methods: cls.methods.map((m, i) => ({ nodeId: sourceNodeId(`method:${cls.id}:${i}`), name: m.methodName, line: m.lineNumber ?? undefined })),
    })
  })
  return [...dirs.values()]
})

// ── source nodes (for counts and fallback) ───────────────────────────────────

const sourceNodes = computed<RelationNode[]>(() => {
  if (sourceTree.value.length) {
    return sourceTree.value.flatMap((cls) => [
      { id: sourceNodeId(`class:${cls.id}`), label: cls.className, type: 'source code class', classes: ['source', 'static'] } as RelationNode,
      ...cls.methods.map((m, idx) => ({ id: sourceNodeId(`method:${cls.id}:${idx}`), label: `${m.methodName}${m.lineNumber ? ` · L${m.lineNumber}` : ''}`, type: 'source code method', classes: ['source', 'static'] } as RelationNode)),
    ])
  }
  const linked = nodes.value.filter((n) => n.id.startsWith('src:'))
  if (linked.length) return linked
  return overview.value.sources.map((s) => ({ id: sourceNodeId(s.id), label: s.fileName || s.id, type: 'source code', classes: ['source', 'static'] }))
})

// ── selected node info ───────────────────────────────────────────────────────

const selectedNodeInfo = computed(() => {
  const id = selectedId.value
  if (!id) return null
  return nodes.value.find((n) => n.id === id) || sourceNodes.value.find((n) => n.id === id) || null
})
const selectedNodeLabel     = computed(() => selectedNodeInfo.value?.label || selectedId.value)
const selectedNodeDesc      = computed(() => selectedNodeInfo.value?.description || '')
const selectedNodeTone      = computed(() => {
  const t = selectedNodeInfo.value?.type || ''
  if (t.includes('requirement')) return 'tone-req'
  if (t.includes('testcase'))    return 'tone-tc'
  if (t.includes('source') || t.includes('code')) return 'tone-src'
  if (t.includes('bug'))         return 'tone-bug'
  return 'tone-default'
})
const selectedNodeTypeLabel = computed(() => {
  const t = selectedNodeInfo.value?.type || ''
  if (t.includes('requirement')) return '需求'
  if (t.includes('testcase'))    return '测试用例'
  if (t.includes('method'))      return '代码方法'
  if (t.includes('class'))       return '代码类'
  if (t.includes('source') || t.includes('code')) return '代码'
  if (t.includes('bug'))         return 'Bug/问题'
  return '节点'
})

// ── focused trace links ────────────────────────────────────────────────────

const focusedLinks = computed<TraceGroup[]>(() => {
  const id = selectedId.value
  if (!id) return []
  const outEdges = edges.value.filter((e) => e.source === id)
  const inEdges  = edges.value.filter((e) => e.target === id)
  const nodeMap  = new Map([...nodes.value, ...sourceNodes.value].map((n) => [n.id, n]))
  const groups: TraceGroup[] = []

  const addGroup = (label: string, relLabel: string, tone: string, edgeList: RelationEdge[], pick: (e: RelationEdge) => string) => {
    const items = edgeList.map((e) => {
      const nid = pick(e)
      const n = nodeMap.get(nid)
      return { id: nid, label: n?.label || nid, sub: n?.description || '' }
    })
    if (items.length) groups.push({ label, relLabel, tone, items })
  }

  if (id.startsWith('req:')) {
    addGroup('验收覆盖 → 测试用例', '验收覆盖', 'tone-tc',  outEdges.filter((e) => e.target.startsWith('tc:')),  (e) => e.target)
    addGroup('需求实现 → 代码',     '需求实现', 'tone-src', outEdges.filter((e) => e.target.startsWith('src:')), (e) => e.target)
  } else if (id.startsWith('tc:')) {
    addGroup('← 关联需求',   '验收依据', 'tone-req', inEdges.filter((e) => e.source.startsWith('req:')), (e) => e.source)
    addGroup('测试覆盖 → 代码', '测试覆盖', 'tone-src', outEdges.filter((e) => e.target.startsWith('src:')), (e) => e.target)
  } else if (id.startsWith('src:')) {
    addGroup('← 实现该需求',  '需求实现', 'tone-req', inEdges.filter((e) => e.source.startsWith('req:')), (e) => e.source)
    addGroup('← 被测试覆盖',  '测试覆盖', 'tone-tc',  inEdges.filter((e) => e.source.startsWith('tc:')),  (e) => e.source)
    addGroup('调用其他代码 →','调用',     'tone-src', outEdges.filter((e) => e.target.startsWith('src:')), (e) => e.target)
  }
  return groups
})

const linkedNodeIds = computed(() => {
  const ids = new Set<string>()
  edges.value.forEach((e) => { ids.add(e.source); ids.add(e.target) })
  return ids
})

const traceStats = computed(() => ({
  requirements: detail.value?.criteria.length || 0,
  testcases:    detail.value?.testcases.length  || 0,
  sources:      sourceNodes.value.length,
}))

// ── tree interactions ───────────────────────────────────────────────────────

function toggleDir(path: string) {
  const s = new Set(openDirs.value)
  s.has(path) ? s.delete(path) : s.add(path)
  openDirs.value = s
}

function toggleClass(id: string) {
  const s = new Set(openClasses.value)
  s.has(id) ? s.delete(id) : s.add(id)
  openClasses.value = s
}

function selectId(id: string) {
  selectedId.value = id
  function expandAncestors(nodes: TreeNode[], target: string): boolean {
    for (const n of nodes) {
      if (!n.isDir) {
        if (n.file?.nodeId === target || n.file?.methods.some((m) => m.nodeId === target)) {
          if (n.file) { const cs = new Set(openClasses.value); cs.add(n.file.id); openClasses.value = cs }
          return true
        }
      } else {
        if (expandAncestors(n.children, target)) {
          const ds = new Set(openDirs.value); ds.add(n.key); openDirs.value = ds
          return true
        }
      }
    }
    return false
  }
  expandAncestors(codeTrieRoot.value, id)
}

function requirementTestCount(criterionId: string) {
  return edges.value.filter((e) => e.source === requirementNodeId(criterionId) && e.target.startsWith('tc:')).length
}

function testcaseRequirementCount(testcaseId: string) {
  return edges.value.filter((e) => e.target === testcaseNodeId(testcaseId) && e.source.startsWith('req:')).length
}

function sourceLinkCount(nodeId: string) {
  return edges.value.filter((e) => e.target === nodeId || e.source === nodeId).length
}

// ── data loading ────────────────────────────────────────────────────────────

// Watch only fires when user switches baseline manually via the dropdown.
// Initial load goes through load() which controls sequencing explicitly.
watch(activeBaselineId, async (value, oldValue) => {
  if (!value || value === oldValue) return
  loading.value = true
  error.value   = ''
  try {
    await loadBaselineDetail(value)
    await loadSourceTree()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载基线失败'
  } finally {
    loading.value = false
  }
})

async function load() {
  loading.value = true
  error.value   = ''
  try {
    overview.value = await fetchVerificationOverview(projectId.value)
    const id = pickBaselineId(overview.value.baselines, activeBaselineId.value)
    // Load detail FIRST so loadSourceTree can read sourceAssetId from detail.value.baseline.
    // Only then update activeBaselineId, so the watch does NOT fire a redundant second load.
    detail.value = null
    if (id) await loadBaselineDetail(id)
    if (id !== activeBaselineId.value) {
      // suppress watch by marking current as equal before assigning
      activeBaselineId.value = id
    }
    await loadSourceTree()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载追溯地图失败'
  } finally {
    loading.value = false
  }
}

async function loadSourceTree() {
  const apps        = await fetchProjectApps(projectId.value)
  // Use the fully-loaded detail baseline (has sourceAssetId); fall back to overview summary
  const baseline    = detail.value?.baseline ?? activeBaseline.value
  const sourceAssetId = baseline?.sourceAssetId ?? undefined
  const preferredAppId = baseline?.sourceAppId ?? undefined

  // Collect static-index results from ALL apps (merged by backend when sourceAssetId is also passed)
  const orderedApps = preferredAppId
    ? [...apps.filter((a) => a.id === preferredAppId), ...apps.filter((a) => a.id !== preferredAppId)]
    : apps

  const seenIds = new Set<string>()
  const combined: (typeof sourceTree.value) = []

  // One request per app, each also carries sourceAssetId so backend merges both sources
  await Promise.all(orderedApps.map(async (app) => {
    try {
      const result = await fetchMapSourceTree(projectId.value, app.id, sourceAssetId)
      result.forEach((r) => { if (seenIds.add(r.id)) combined.push(r) })
    } catch { /* skip failing apps */ }
  }))

  // If we still have nothing from static index, try the asset alone
  if (combined.length === 0 && sourceAssetId) {
    try {
      const result = await fetchMapSourceTree(projectId.value, undefined, sourceAssetId)
      result.forEach((r) => { if (seenIds.add(r.id)) combined.push(r) })
    } catch { /* skip */ }
  }

  sourceTree.value = combined

  if (sourceTree.value.length) {
    const firstRoot = sourceTree.value[0]?.filePath
      ? sourceTree.value[0].filePath.replace(/\\/g, '/').split('/')[0]
      : sourceTree.value[0]?.className.replace(/\./g, '/').split('/')[0]
    if (firstRoot) {
      const s = new Set(openDirs.value)
      s.add(firstRoot)
      openDirs.value = s
    }
  }
}

async function loadBaselineDetail(baselineId: string) {
  if (!baselineId) { detail.value = null; return }
  loading.value = true
  error.value   = ''
  try {
    detail.value = await fetchBaselineDetail(projectId.value, baselineId)
    selectedId.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载分析基线失败'
  } finally {
    loading.value = false
  }
}

function pickBaselineId(baselines: VerificationBaseline[], current: string) {
  if (current && baselines.some((b) => b.id === current)) return current
  return baselines.find((b) => ['WAITING_REVIEW', 'COMPLETED'].includes(b.status))?.id || baselines[0]?.id || ''
}

// ── graph building ──────────────────────────────────────────────────────────

function buildTraceGraph(current: BaselineDetail | null): { nodes: RelationNode[]; edges: RelationEdge[] } {
  if (!current) return { nodes: [], edges: [] }
  const ns = new Map<string, RelationNode>()
  const es = new Map<string, RelationEdge>()
  const criteriaById = new Map(current.criteria.map((c) => [c.id, c]))
  const testcasesByExternalKey = new Map(current.testcases.map((t) => [t.externalKey, t]))
  current.criteria.forEach((c) => ns.set(requirementNodeId(c.id), { id: requirementNodeId(c.id), label: `${c.requirementKey}/${c.acKey}`, type: 'requirement', classes: ['requirement'], description: c.title || c.content }))
  current.testcases.forEach((t) => ns.set(testcaseNodeId(t.id), { id: testcaseNodeId(t.id), label: t.externalKey || t.title || t.id, type: 'testcase', classes: ['testcase'], description: t.title || t.expected || t.steps }))
  current.traceLinks.forEach((link) => addNormalizedTraceLink(ns, es, link))
  addDerivedTestcaseCodeLinks(ns, es)
  addSourceAssetFallbacks(ns, current)
  current.findings.forEach((finding) => {
    const bugId = bugNodeId(finding.id)
    ns.set(bugId, { id: bugId, label: finding.title || finding.findingType, type: `bug ${finding.severity.toLowerCase()}`, classes: ['bug'], description: finding.description })
    if (finding.acId && criteriaById.has(finding.acId)) {
      const rId = requirementNodeId(finding.acId)
      es.set(`finding:${finding.id}`, { id: `finding:${finding.id}`, source: rId, target: bugId, label: '需求↔Bug', action: finding.severity, sourceLabel: ns.get(rId)?.label, targetLabel: ns.get(bugId)?.label })
    } else {
      const tc = testcaseFromFindingEvidence(finding, testcasesByExternalKey)
      if (tc) {
        const tId = testcaseNodeId(tc.id)
        es.set(`finding:${finding.id}`, { id: `finding:${finding.id}`, source: tId, target: bugId, label: '用例↔Bug', action: finding.severity, sourceLabel: ns.get(tId)?.label, targetLabel: ns.get(bugId)?.label })
      }
    }
  })
  return { nodes: [...ns.values()], edges: [...es.values()] }
}

function addNormalizedTraceLink(ns: Map<string, RelationNode>, es: Map<string, RelationEdge>, link: TraceLink) {
  const src = resolveTraceNodeId(link.sourceType, link.sourceId)
  const tgt = resolveTraceNodeId(link.targetType, link.targetId)
  if (!src || !tgt) return
  ensureTraceNode(ns, src, link.sourceType, link.sourceId, link)
  ensureTraceNode(ns, tgt, link.targetType, link.targetId, link)
  if (!ns.has(src) || !ns.has(tgt)) return
  const pair = normalizeTracePair(src, tgt)
  if (!pair) return
  es.set(`trace:${link.id}`, { id: `trace:${link.id}`, source: pair.source, target: pair.target, label: pair.label, action: pair.label, sourceLabel: ns.get(pair.source)?.label, targetLabel: ns.get(pair.target)?.label })
}

function ensureTraceNode(ns: Map<string, RelationNode>, id: string, type: string, rawId: string, link: TraceLink) {
  if (ns.has(id) || !id.startsWith('src:')) return
  const dynamic = /EXECUTION|RUNTIME|DYNAMIC/.test(`${type} ${link.relationType} ${JSON.stringify(link.evidence || {})}`.toUpperCase())
  ns.set(id, { id, label: sourceLabel(rawId), type: dynamic ? 'source dynamic' : 'source code', classes: ['source', dynamic ? 'dynamic' : 'static'], description: evidenceText(link.evidence) || '' })
}

function normalizeTracePair(a: string, b: string) {
  if (a.startsWith('req:') && b.startsWith('tc:'))  return { source: a, target: b, label: '验收覆盖' }
  if (a.startsWith('tc:')  && b.startsWith('req:')) return { source: b, target: a, label: '验收覆盖' }
  if (a.startsWith('req:') && b.startsWith('src:')) return { source: a, target: b, label: '需求实现' }
  if (a.startsWith('src:') && b.startsWith('req:')) return { source: b, target: a, label: '需求实现' }
  if (a.startsWith('tc:')  && b.startsWith('src:')) return { source: a, target: b, label: '测试覆盖' }
  if (a.startsWith('src:') && b.startsWith('tc:'))  return { source: b, target: a, label: '测试覆盖' }
  if (a.startsWith('src:') && b.startsWith('src:')) return { source: a, target: b, label: '调用' }
  return null
}

function addDerivedTestcaseCodeLinks(ns: Map<string, RelationNode>, es: Map<string, RelationEdge>) {
  const rToT = new Map<string, string[]>()
  const rToS = new Map<string, string[]>()
  es.forEach((e) => {
    if (e.source.startsWith('req:') && e.target.startsWith('tc:'))  rToT.set(e.source, [...(rToT.get(e.source) || []), e.target])
    if (e.source.startsWith('req:') && e.target.startsWith('src:')) rToS.set(e.source, [...(rToS.get(e.source) || []), e.target])
  })
  rToT.forEach((tests, rId) => (rToS.get(rId) || []).forEach((sId) => tests.forEach((tId) => {
    const id = `derived:${tId}:${sId}`
    if (!es.has(id)) es.set(id, { id, source: tId, target: sId, label: '测试覆盖', action: '测试覆盖', sourceLabel: ns.get(tId)?.label, targetLabel: ns.get(sId)?.label })
  })))
}

function addSourceAssetFallbacks(ns: Map<string, RelationNode>, current: BaselineDetail) {
  if (ns.size === current.criteria.length + current.testcases.length && current.baseline.sourceAssetId) {
    const id = sourceNodeId(current.baseline.sourceAssetId)
    ns.set(id, { id, label: '源码资产', type: 'source code', classes: ['source', 'static'], description: '已导入源码，尚未生成方法级追溯链接' })
  }
}

function resolveTraceNodeId(type: string, id: string) {
  if (type === 'AC' || type === 'REQUIREMENT') return requirementNodeId(id)
  if (type === 'TESTCASE') return testcaseNodeId(id)
  if (['SOURCE_SYMBOL','SOURCE','CODE','METHOD','CLASS','FILE','EXECUTION','RUNTIME'].includes(type) || type.includes('SOURCE')) return sourceNodeId(id)
  return ''
}

function requirementNodeId(id: string) { return `req:${id}` }
function testcaseNodeId(id: string)    { return `tc:${id}` }
function sourceNodeId(id: string)      { return `src:${id}` }
function bugNodeId(id: string)         { return `bug:${id}` }

function sourceLabel(value: string) {
  return value.replace(/^SOURCE_ASSET:/, '').split(/[/.#]/).filter(Boolean).slice(-2).join('.') || value
}
function evidenceText(e?: Record<string, unknown>) {
  return e ? String(e.method || e.className || e.locator || e.reason || '') : ''
}
function testcaseFromFindingEvidence(finding: VerificationFinding, byKey: Map<string, { id: string }>) {
  for (const item of finding.evidence || []) {
    const k = String(item.testcaseId || '')
    const t = k ? byKey.get(k) : undefined
    if (t) return t
  }
  return null
}
function emptyOverview(): VerificationOverview {
  return { requirements: [], testcases: [], sources: [], executions: [], coverages: [], defects: [], baselines: [] }
}

onMounted(load)
</script>

<style scoped>
.trace-workspace { min-width: 0; }

.workspace-toolbar { display:flex; justify-content:space-between; gap:16px; align-items:center; margin-bottom:14px; padding:15px 17px; border:1px solid rgba(15,23,42,.08); border-radius:22px; background:rgba(255,255,255,.94); }
.workspace-toolbar > div:first-child { display:grid; gap:4px; }
.workspace-toolbar > div:first-child > strong { color:#172033; font-size:19px; }
.workspace-toolbar span { color:#64748b; font-size:13px; }
.eyebrow { color:#0f766e !important; font-size:11px !important; font-weight:900; letter-spacing:.12em; text-transform:uppercase; }
.toolbar-actions { display:flex; gap:8px; flex-wrap:wrap; justify-content:flex-end; }
.toolbar-actions button { border:0; border-radius:999px; padding:9px 13px; background:#eef2f5; font-weight:800; cursor:pointer; }
.toolbar-actions button:hover:not(:disabled) { background:#0f766e; color:#fff; }
.toolbar-actions button:disabled { opacity:.5; cursor:not-allowed; }
.baseline-select { display:flex; align-items:center; gap:7px; padding:5px 10px; border:1px solid #e2e8f0; border-radius:999px; font-weight:800; }
.baseline-select select { max-width:260px; border:0; background:transparent; outline:0; }
.workspace-notice { margin-bottom:14px; padding:11px 14px; border-radius:14px; background:#f0fdfa; color:#0f766e; font-weight:700; }
.workspace-notice.error { background:#fef2f2; color:#b91c1c; }

.trace-columns { display:grid; grid-template-columns:minmax(220px,.68fr) minmax(340px,1fr) minmax(260px,.82fr); gap:14px; min-height:calc(100vh - 245px); }

.asset-pane, .code-pane { overflow:hidden; border:1px solid rgba(15,23,42,.08); border-radius:22px; background:rgba(255,255,255,.94); box-shadow:0 14px 36px rgba(15,23,42,.05); }
.asset-pane { display:flex; flex-direction:column; }
.pane-head { display:flex; justify-content:space-between; gap:8px; padding:14px 15px; border-bottom:1px solid #eef2f5; color:#172033; font-size:14px; }
.pane-head span { color:#64748b; font-size:11px; font-weight:700; }
.empty-card { padding:18px; color:#94a3b8; text-align:center; font-size:13px; }

.asset-group { display:grid; gap:7px; padding:12px; min-height:0; overflow:auto; }
.testcase-group { flex:1; border-top:1px solid #eef2f5; }
.group-head { display:flex; justify-content:space-between; align-items:center; color:#334155; font-size:13px; font-weight:700; }
.group-head span { display:grid; place-items:center; min-width:20px; height:20px; border-radius:999px; background:#eff6ff; color:#2563eb; font-size:11px; }
.asset-card { display:grid; gap:3px; border:1px solid transparent; border-radius:12px; padding:9px 10px; background:#f8fafc; color:#172033; text-align:left; cursor:pointer; }
.asset-card:hover, .asset-card.active { border-color:rgba(15,118,110,.4); background:#f0fdfa; }
.asset-card b { color:#0f766e; font-size:11px; font-weight:900; }
.asset-card.testcase b { color:#2563eb; }
.asset-card strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }
.asset-card small { color:#64748b; font-size:11px; }

.trace-pane { display:flex; flex-direction:column; gap:14px; overflow-y:auto; padding:16px; border:1px solid rgba(15,23,42,.08); border-radius:22px; background:rgba(255,255,255,.94); box-shadow:0 14px 36px rgba(15,23,42,.05); }
.trace-empty { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; flex:1; min-height:240px; color:#94a3b8; text-align:center; }
.trace-hint-icon { font-size:36px; }
.trace-empty strong { color:#64748b; font-size:14px; }
.spin { display:inline-block; animation:spin 1.2s linear infinite; font-size:28px; }
@keyframes spin { to { transform:rotate(360deg); } }

.trace-selected-card { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; padding:14px; border-radius:16px; border:1px solid; }
.trace-selected-card.tone-req { color:#0f766e; background:#f0fdfa; border-color:rgba(15,118,110,.25); }
.trace-selected-card.tone-tc  { color:#1d4ed8; background:#eff6ff; border-color:rgba(37,99,235,.25); }
.trace-selected-card.tone-src { color:#6d28d9; background:#f5f3ff; border-color:rgba(109,40,217,.25); }
.trace-selected-card.tone-bug { color:#b91c1c; background:#fef2f2; border-color:rgba(185,28,28,.25); }
.trace-selected-card.tone-default { color:#475569; background:#f8fafc; border-color:#e2e8f0; }
.tsc-left { display:grid; gap:4px; }
.tsc-type { font-size:10px; font-weight:900; text-transform:uppercase; letter-spacing:.1em; opacity:.7; }
.tsc-left strong { font-size:15px; color:#172033; }
.tsc-desc { font-size:12px; color:#64748b; }
.tsc-clear { flex-shrink:0; border:0; border-radius:999px; width:28px; height:28px; background:rgba(0,0,0,.06); color:#475569; cursor:pointer; font-size:14px; }
.tsc-clear:hover { background:rgba(0,0,0,.12); }

.trace-link-groups { display:grid; gap:14px; }
.trace-group { display:grid; gap:7px; }
.tg-head { display:flex; align-items:center; gap:8px; font-size:12px; color:#475569; }
.tg-head strong { font-size:13px; color:#172033; }
.tg-head em { margin-left:auto; border-radius:999px; padding:2px 8px; background:#f1f5f9; color:#64748b; font-size:11px; font-style:normal; font-weight:800; }
.tg-dot { width:9px; height:9px; border-radius:999px; flex-shrink:0; }
.tg-dot.tone-req { background:#0f766e; }
.tg-dot.tone-tc  { background:#2563eb; }
.tg-dot.tone-src { background:#7c3aed; }
.tg-dot.tone-bug { background:#dc2626; }

.trace-link-card { display:flex; flex-wrap:wrap; align-items:center; gap:6px; width:100%; border:1px solid transparent; border-radius:11px; padding:9px 11px; background:#f8fafc; text-align:left; cursor:pointer; }
.trace-link-card:hover, .trace-link-card.active { border-color:rgba(15,118,110,.35); background:#f0fdfa; }
.trace-link-card.tone-tc:hover,  .trace-link-card.tone-tc.active  { border-color:rgba(37,99,235,.35);  background:#eff6ff; }
.trace-link-card.tone-src:hover, .trace-link-card.tone-src.active { border-color:rgba(109,40,217,.35); background:#f5f3ff; }
.tlc-label { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; color:#172033; font-weight:700; }
.tlc-sub   { width:100%; font-size:11px; color:#64748b; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tlc-rel   { flex-shrink:0; font-size:10px; font-weight:900; border-radius:999px; padding:2px 8px; }
.tlc-rel.tone-req { background:rgba(15,118,110,.1); color:#0f766e; }
.tlc-rel.tone-tc  { background:rgba(37,99,235,.1);  color:#1d4ed8; }
.tlc-rel.tone-src { background:rgba(109,40,217,.1); color:#6d28d9; }
.trace-empty-links { color:#94a3b8; font-size:13px; text-align:center; padding:24px 0; }

.code-pane { display:flex; flex-direction:column; }
.code-search { display:block; padding:10px 14px; border-bottom:1px solid #eef2f5; }
.code-search input { width:100%; box-sizing:border-box; border:1px solid #e2e8f0; border-radius:10px; padding:7px 10px; font-size:13px; outline:0; background:#f8fafc; }
.code-search input:focus { border-color:#7c3aed; background:#fff; }
.tree-scroll { flex:1; overflow-y:auto; padding:8px 6px 12px; }

.tree-dir { margin-bottom:2px; }
.tree-dir-row { display:flex; align-items:center; gap:6px; width:100%; border:0; border-radius:10px; padding:7px 8px; background:transparent; text-align:left; cursor:pointer; color:#334155; font-size:13px; font-weight:700; }
.tree-dir-row:hover { background:#f1f5f9; }
.tree-dir-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tree-dir-row em { margin-left:auto; border-radius:999px; padding:1px 7px; background:#eef2f5; color:#64748b; font-size:11px; font-style:normal; }
.tree-dir-body { margin-left:14px; border-left:2px solid #eef2f5; padding-left:8px; }

.tree-class { margin-bottom:1px; }
.tree-class-row { display:flex; align-items:center; gap:6px; width:100%; border:0; border-radius:9px; padding:6px 8px; background:transparent; text-align:left; cursor:pointer; color:#475569; font-size:12px; font-weight:700; }
.tree-class-row:hover { background:#f5f3ff; color:#6d28d9; }
.tree-class-row.active { background:#ede9fe; color:#6d28d9; }
.tree-class-row.linked .tree-class-name { color:#6d28d9; }
.tree-class-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tree-class-row em { flex-shrink:0; border-radius:999px; padding:1px 6px; background:#ede9fe; color:#6d28d9; font-size:10px; font-style:normal; font-weight:900; }
.tree-class-icon { color:#7c3aed; font-style:normal; flex-shrink:0; }

.tree-methods { margin-left:14px; border-left:2px solid #ede9fe; padding-left:8px; display:grid; gap:1px; }
.tree-method-row { display:flex; align-items:center; gap:6px; width:100%; border:0; border-radius:8px; padding:5px 8px; background:transparent; text-align:left; cursor:pointer; color:#64748b; font-size:12px; }
.tree-method-row:hover { background:#f5f3ff; color:#6d28d9; }
.tree-method-row.active { background:#ede9fe; color:#6d28d9; }
.tree-method-row.linked .tree-method-name { color:#6d28d9; font-weight:700; }
.tree-method-icon { color:#94a3b8; flex-shrink:0; font-style:normal; width:14px; text-align:center; }
.tree-method-row.linked .tree-method-icon { color:#7c3aed; }
.tree-method-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tree-method-line { flex-shrink:0; color:#94a3b8; font-size:11px; }
.tree-method-row em { flex-shrink:0; border-radius:999px; padding:1px 6px; background:#ede9fe; color:#6d28d9; font-size:10px; font-style:normal; font-weight:900; }
.tree-toggle { flex-shrink:0; width:14px; color:#94a3b8; font-size:11px; }

@media(max-width:1100px) { .trace-columns { grid-template-columns:minmax(200px,.68fr) minmax(320px,1fr); } .code-pane { grid-column:1/-1; } }
@media(max-width:760px) { .workspace-toolbar { flex-direction:column; align-items:flex-start; } .toolbar-actions { justify-content:flex-start; } .trace-columns { grid-template-columns:1fr; } .code-pane { grid-column:auto; } .asset-pane { max-height:480px; } }
</style>

