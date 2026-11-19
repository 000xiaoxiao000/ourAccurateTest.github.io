/**
 * 变更影响分析引擎（源码结构版）
 *
 * 原理一句话：**「改了哪些符号」沿调用/注入/类型引用图反向回溯 → 命中的 Controller 接口 → 抓包用例**。
 *   - 改了哪些符号：新旧两份源码做符号级结构 diff（类 / 方法 / 字段）
 *   - 反向回溯：谁调用 / 注入 / 引用了变更符号，一路走到 @RestController 方法
 *   - 命中用例：Spring 端点 URL 归一化后与抓包记录匹配，反查 sessions.case_name
 *
 * 与 impact.ts 的关系：
 *   - impact.ts  = 「变更行 ∩ 覆盖率证据」，需要 exec + key 抓包，精确但冷启动不可用
 *   - 本模块     = 「代码结构推导」，只需要新旧源码，冷启动可用，但是静态候选需人工复核
 *   两者互补，互不依赖。
 *
 * ⚠️ 刻意不 import electron / database —— 流量由调用方注入，核心逻辑可在纯 Node 下单测
 *    （与 git.ts / impact.ts 同构）。
 */
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'

// ===== 输入 / 输出类型 =====

/** 一条抓包记录（用于 URL → 用例反查；调用方从流量库注入） */
export interface SourceImpactTraffic {
  method: string
  url: string
  protocol?: string
  caseName: string
  timestamp?: number
}

export interface SourceImpactRequest {
  /** 新版源码根目录（git.ts 检出的 newSourceRoot） */
  newSourceRoot: string
  /** 旧版源码根目录（git.ts 检出的 oldSourceRoot） */
  oldSourceRoot: string
  /** 抓包流量（调用方注入；可为空 —— 为空则只输出接口不输出用例） */
  traffic?: SourceImpactTraffic[]
  /** 时间窗起点（毫秒），过滤流量；不传 = 全部 */
  since?: number
  /** 图回溯最大跳数，默认 4 */
  maxDepth?: number
  /** 单文件最大字符数，超过跳过，默认 500000 */
  maxFileChars?: number
  /** 最多扫描多少 .java 文件，默认 5000 */
  maxFiles?: number
}

export interface SourceImpactStage { text: string; percent: number }

export interface SourceChange {
  key: string
  kind: 'TYPE' | 'METHOD'
  changeType: 'ADD' | 'DELETE' | 'MODIFY'
  qualifiedName: string
  path: string
  reason: string
}

export interface SourceEndpoint {
  httpMethod: string
  /** Spring 路径模板，如 /api/order/{id} */
  path: string
  className: string
  methodName: string
  /** 端点所在方法符号 key */
  symbolKey: string
  locator: string
}

export interface AffectedEndpoint {
  endpoint: SourceEndpoint
  /** 最高置信度（沿影响路径连乘） */
  confidence: number
  /** 由哪个变更符号引发 */
  seed: string
  /** 影响路径：符号链 */
  path: string[]
  /** 路径上的边类型 */
  edgeTypes: string[]
  /** 命中的抓包 URL → 用例名 */
  cases: string[]
  /** 命中的抓包条数 */
  hits: number
  evidence: string[]
}

export interface AffectedCase {
  caseName: string
  endpoints: string[]
  hits: number
  order: number
}

export interface SourceImpactResult {
  success: boolean
  error?: string
  errorKind?: 'usage' | 'io' | 'notfound'
  stages: SourceImpactStage[]
  summary: {
    scannedFiles: number
    changedSymbols: number
    changedClasses: number
    endpoints: number
    affectedEndpoints: number
    affectedCases: number
    unmatchedEndpoints: number
  }
  changes: SourceChange[]
  endpoints: SourceEndpoint[]
  affected: AffectedEndpoint[]
  cases: AffectedCase[]
  warnings: string[]
}

type OnStage = (s: SourceImpactStage) => void

// ===== Java 源码解析（正则版，与后端 JavaTreeSitterAnalyzer 同思路，但做整库索引）=====

interface EndpointMeta {
  httpMethod: string
  rawPath: string
}

interface SymbolNode {
  key: string
  kind: 'TYPE' | 'METHOD'
  qualifiedName: string
  /** 类名（全限定） */
  className: string
  methodName?: string
  signature?: string
  path: string
  line: number
  astHash: string
  apiHash: string
  invokedNames: string[]
  superTypes: string[]
  fieldTypes: string[]
  injectFields: string[]
  paramTypes: string[]
  /** 类级 @RequestMapping 前缀 */
  classPath: string
  /** 方法级 mapping（METHOD 节点用） */
  endpoint?: EndpointMeta
  isController: boolean
}

const PACKAGE = /^\s*package\s+([\w.]+)\s*;/m
const IMPORT = /^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;/gm
const TYPE = /\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/g
const EXTENDS = /\bextends\s+([\w.$,\s]+?)(?=\s*(?:implements|\{|$))/
const IMPLEMENTS = /\bimplements\s+([\w.$,\s]+?)(?=\s*\{)/
const METHOD = /\b(?:public|protected|private)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|native\s+)*(?:<[^>]*>\s*)?([\w$<>?,.\[\]\s]+?)\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*(?:throws\s+[\w.,\s]+?)?\{/g
const INVOCATION = /\b([A-Za-z_$][\w$]*)\s*\(/g
const FIELD = /^\s*(?:(@(?:Autowired|Resource|Inject)[^\n]*)\s*)?(?:private|protected|public|static|final|volatile|transient|\s)*\s*([\w$.<>?,\[\]]+)\s+([A-Za-z_$][\w$]*)\s*[;=]/gm
const MAPPING = /@(Request|Get|Post|Put|Delete|Patch)Mapping\s*\(([^)]*)\)|@(Request|Get|Post|Put|Delete|Patch)Mapping\b/g
const CTRL_ANNOTATION = /@(RestController|Controller)\b/
const PATH_VALUE = /(?:value|path)\s*=\s*"([^"]*)"|"([^"]*)"/

const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'try', 'synchronized', 'return', 'new', 'super', 'this'])

function sha(value: string): string {
  return crypto.createHash('sha256').update(value).digest('hex')
}

function normalizeBody(value: string): string {
  return value.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '').replace(/\s+/g, ' ').trim()
}

function simpleName(value: string): string {
  if (!value) return ''
  const base = value.split('<')[0].trim()
  const dot = base.lastIndexOf('.')
  return dot >= 0 ? base.substring(dot + 1) : base
}

function splitTypeList(csv: string): string[] {
  return csv.split(',').map(s => simpleName(s.trim())).filter(Boolean)
}

/** 解析方法参数里的类型名（去注解、去泛型、取每段第一个类型 token） */
function paramTypes(params: string): string[] {
  if (!params.trim()) return []
  const out = new Set<string>()
  for (const seg of params.split(',')) {
    const cleaned = seg.replace(/@[\w.]+(\([^)]*\))?/g, '').trim()
    const tokens = cleaned.split(/\s+/).filter(Boolean)
    if (!tokens.length) continue
    let typeToken = tokens[0]
    if (tokens.length >= 2 && /^(final|var)$/.test(tokens[0])) typeToken = tokens[1]
    const name = simpleName(typeToken)
    if (name && /^[A-Z]/.test(name) && !KEYWORDS.has(name)) out.add(name)
  }
  return [...out]
}

function lineAt(source: string, offset: number): number {
  let line = 1
  for (let i = 0; i < offset; i++) if (source[i] === '\n') line++
  return line
}

function braceEnd(source: string, openOffset: number): number {
  let depth = 0
  for (let i = openOffset; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}' && --depth === 0) return i
  }
  return -1
}

/** 提取 mapping 注解的 http method 与路径 */
function parseMapping(type: string, args: string): EndpointMeta {
  const pm = PATH_VALUE.exec(args || '')
  const rawPath = pm ? (pm[1] || pm[2] || '') : ''
  const http = type.toUpperCase() === 'REQUEST' ? 'ANY' : type.toUpperCase()
  return { httpMethod: http, rawPath }
}

/**
 * 在方法声明之前的注解区里找 mapping。
 * @param scanStart 上一个方法体结束位置（或类声明位置）—— 不能用 lastIndexOf('}')，
 *                  因为 @GetMapping("/{id}") 的路径变量里就有 '}'，会把注解截断。
 */
function annotationsBefore(source: string, methodStart: number, scanStart: number): EndpointMeta[] {
  const region = source.substring(Math.max(0, scanStart), methodStart)
  const out: EndpointMeta[] = []
  let m: RegExpExecArray | null
  MAPPING.lastIndex = 0
  while ((m = MAPPING.exec(region)) !== null) {
    if (m[1]) out.push(parseMapping(m[1], m[2] || ''))
    else if (m[3]) out.push(parseMapping(m[3], ''))
  }
  return out
}

/**
 * 解析单个 Java 文件 → 符号节点列表（TYPE + METHOD）。
 * 不做精确语义，只求覆盖 Controller→Service→POJO 的常见写法。
 */
export function parseJavaSource(relPath: string, source: string, maxFileChars = 500000): SymbolNode[] {
  if (!source || source.length > maxFileChars) return []
  const pm = PACKAGE.exec(source)
  const packageName = pm ? pm[1] : ''
  const imports: string[] = []
  let im: RegExpExecArray | null
  IMPORT.lastIndex = 0
  while ((im = IMPORT.exec(source)) !== null) imports.push(im[1])

  // 主类型（取第一个 class/interface/enum/record 声明）
  TYPE.lastIndex = 0
  const tm = TYPE.exec(source)
  if (!tm) return []
  const typeName = tm[2]
  const fqcn = packageName ? `${packageName}.${typeName}` : typeName
  const classStart = tm.index

  const em = EXTENDS.exec(source)
  const superTypes = em ? splitTypeList(em[1]) : []
  const imps = IMPLEMENTS.exec(source)
  if (imps) superTypes.push(...splitTypeList(imps[1]))

  const classPrefix = source.substring(Math.max(0, classStart - 600), classStart + 200)
  const isController = CTRL_ANNOTATION.test(classPrefix)
  let classPath = ''
  let m: RegExpExecArray | null
  MAPPING.lastIndex = 0
  while ((m = MAPPING.exec(classPrefix)) !== null) {
    if (m[3] === 'Request' || m[1] === 'Request') {
      const meta = parseMapping('Request', m[2] || '')
      if (meta.rawPath) classPath = meta.rawPath
    }
  }

  const fieldTypes: string[] = []
  const injectFields: string[] = []
  FIELD.lastIndex = 0
  let fm: RegExpExecArray | null
  while ((fm = FIELD.exec(source)) !== null) {
    const t = simpleName(fm[2])
    if (!t || !/^[A-Z]/.test(t)) continue
    if (!fieldTypes.includes(t)) fieldTypes.push(t)
    if (fm[1] && !injectFields.includes(t)) injectFields.push(t)
  }

  const typeNode: SymbolNode = {
    key: `java://${fqcn}`,
    kind: 'TYPE',
    qualifiedName: fqcn,
    className: fqcn,
    path: relPath,
    line: lineAt(source, classStart),
    astHash: sha(normalizeBody(source)),
    apiHash: sha([...fieldTypes, ...injectFields, ...superTypes, isController ? 'controller' : ''].sort().join('|')),
    invokedNames: [],
    superTypes,
    fieldTypes,
    injectFields,
    paramTypes: [],
    classPath,
    isController
  }
  const nodes: SymbolNode[] = [typeNode]

  METHOD.lastIndex = 0
  let mm: RegExpExecArray | null
  let scanStart = classStart
  while ((mm = METHOD.exec(source)) !== null) {
    const name = mm[2]
    if (KEYWORDS.has(name)) continue
    const params = mm[3] || ''
    const open = source.indexOf('{', mm.index + mm[0].length - 1)
    const end = open >= 0 ? braceEnd(source, open) : -1
    const rawBody = end >= 0 ? source.substring(mm.index, end + 1) : mm[0]
    const sig = `${name}(${params.replace(/@[\w.]+/g, '').replace(/\s+/g, ' ').trim()})`
    const endpoints = annotationsBefore(source, mm.index, scanStart)
    const invoked: string[] = []
    let inv: RegExpExecArray | null
    INVOCATION.lastIndex = 0
    while ((inv = INVOCATION.exec(rawBody)) !== null) {
      const v = inv[1]
      if (!KEYWORDS.has(v) && !invoked.includes(v)) invoked.push(v)
    }
    nodes.push({
      key: `java://${fqcn}#${sig}`,
      kind: 'METHOD',
      qualifiedName: `${fqcn}.${name}`,
      className: fqcn,
      methodName: name,
      signature: sig,
      path: relPath,
      line: lineAt(source, mm.index),
      astHash: sha(normalizeBody(rawBody)),
      apiHash: sha(sig),
      invokedNames: invoked,
      superTypes: [],
      fieldTypes: [],
      injectFields: [],
      paramTypes: paramTypes(params),
      classPath,
      endpoint: endpoints.length ? endpoints[endpoints.length - 1] : undefined,
      isController
    })
    if (end > mm.index) { METHOD.lastIndex = end + 1; scanStart = end + 1 }
  }
  return nodes
}

// ===== 目录扫描 =====

function walkJava(root: string, maxFiles: number): string[] {
  const out: string[] = []
  const skip = new Set(['node_modules', 'target', 'build', '.git', '.idea', 'dist'])
  const stack = [root]
  while (stack.length && out.length < maxFiles) {
    const dir = stack.pop()!
    let entries: fs.Dirent[]
    try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      if (e.isDirectory()) {
        if (!skip.has(e.name)) stack.push(path.join(dir, e.name))
      } else if (e.isFile() && e.name.endsWith('.java')) {
        out.push(path.join(dir, e.name))
      }
    }
  }
  return out
}

interface Snapshot {
  nodes: Map<string, SymbolNode>
  files: number
}

/**
 * 支持「;」分隔的多模块源码根（Git 面板 detectSourceRoot 在多模块仓库会返回
 *   /a/src/main/java;/b/src/main/java 这样的串）。
 * 逐根扫描，某个根不存在只记录 warning，不影响其它根。
 */
function buildSnapshot(rootSpec: string, maxFiles: number, maxFileChars: number, warnings?: string[]): Snapshot {
  const nodes = new Map<string, SymbolNode>()
  const roots = rootSpec.split(';').map(s => s.trim()).filter(Boolean)
  const files: Array<{ abs: string; root: string }> = []
  for (const root of roots) {
    if (!fs.existsSync(root)) {
      warnings?.push(`源码根不存在，已跳过：${root}`)
      continue
    }
    for (const abs of walkJava(root, maxFiles - files.length)) files.push({ abs, root })
    if (files.length >= maxFiles) break
  }
  for (const { abs, root } of files) {
    let source: string
    try { source = fs.readFileSync(abs, 'utf8') } catch { continue }
    if (source.length > maxFileChars) continue
    const rel = path.relative(root, abs).split(path.sep).join('/')
    for (const node of parseJavaSource(rel, source, maxFileChars)) {
      if (!nodes.has(node.key)) nodes.set(node.key, node)
    }
  }
  return { nodes, files: files.length }
}

// ===== 结构 diff =====

function diffSnapshots(oldSnap: Snapshot, newSnap: Snapshot): SourceChange[] {
  const changes: SourceChange[] = []
  for (const [key, oldNode] of oldSnap.nodes) {
    const newNode = newSnap.nodes.get(key)
    if (!newNode) {
      changes.push({ key, kind: oldNode.kind, changeType: 'DELETE', qualifiedName: oldNode.qualifiedName, path: oldNode.path, reason: '符号被删除' })
    } else if (oldNode.astHash !== newNode.astHash || oldNode.apiHash !== newNode.apiHash) {
      const reason = oldNode.apiHash !== newNode.apiHash ? '签名/字段/注解变化' : '方法体变化'
      changes.push({ key, kind: newNode.kind, changeType: 'MODIFY', qualifiedName: newNode.qualifiedName, path: newNode.path, reason })
    }
  }
  for (const [key, newNode] of newSnap.nodes) {
    if (!oldSnap.nodes.has(key)) {
      changes.push({ key, kind: newNode.kind, changeType: 'ADD', qualifiedName: newNode.qualifiedName, path: newNode.path, reason: '符号新增' })
    }
  }
  return changes
}

// ===== 依赖图 =====

type EdgeType = 'CALLS' | 'INJECTS' | 'READS' | 'EXTENDS'
interface Edge { from: string; to: string; type: EdgeType; confidence: number }

const EDGE_WEIGHT: Record<EdgeType, number> = {
  CALLS: 0.8, INJECTS: 0.85, READS: 0.6, EXTENDS: 0.85
}

function buildEdges(snap: Snapshot): Edge[] {
  const edges: Edge[] = []
  const bySimpleType = new Map<string, string[]>()
  const byMethodName = new Map<string, string[]>()
  for (const node of snap.nodes.values()) {
    if (node.kind === 'TYPE') {
      const s = simpleName(node.className)
      if (!bySimpleType.has(s)) bySimpleType.set(s, [])
      bySimpleType.get(s)!.push(node.key)
    } else if (node.methodName) {
      if (!byMethodName.has(node.methodName)) byMethodName.set(node.methodName, [])
      byMethodName.get(node.methodName)!.push(node.key)
    }
  }
  const typeTargets = (names: string[]): string[] => {
    const out: string[] = []
    for (const n of names) {
      for (const t of bySimpleType.get(simpleName(n)) ?? []) if (!out.includes(t)) out.push(t)
    }
    return out
  }

  for (const node of snap.nodes.values()) {
    if (node.kind === 'TYPE') {
      for (const t of typeTargets(node.superTypes)) edges.push({ from: node.key, to: t, type: 'EXTENDS', confidence: EDGE_WEIGHT.EXTENDS })
      for (const t of typeTargets(node.injectFields)) edges.push({ from: node.key, to: t, type: 'INJECTS', confidence: EDGE_WEIGHT.INJECTS })
      for (const t of typeTargets(node.fieldTypes)) {
        if (node.injectFields.some(f => simpleName(f) === simpleName(t))) continue
        edges.push({ from: node.key, to: t, type: 'READS', confidence: EDGE_WEIGHT.READS })
      }
    } else {
      // 参数类型 / 返回类型引用（POJO 变更的关键传播路径）
      for (const t of typeTargets(node.paramTypes)) edges.push({ from: node.key, to: t, type: 'READS', confidence: 0.7 })
      // 方法调用：按简单方法名跨文件解析（这是相对后端逐文件建边的核心改进）
      for (const inv of node.invokedNames) {
        for (const target of byMethodName.get(inv) ?? []) {
          if (target === node.key) continue
          edges.push({ from: node.key, to: target, type: 'CALLS', confidence: EDGE_WEIGHT.CALLS })
        }
      }
    }
  }
  return edges
}

// ===== 反向传播 =====

interface Propagated { key: string; confidence: number; path: string[]; edgeTypes: string[]; seed: string }

function propagate(seeds: string[], edges: Edge[], maxDepth: number, nodes: Map<string, SymbolNode>): Map<string, Propagated> {
  const reverse = new Map<string, Edge[]>()
  for (const e of edges) {
    if (!reverse.has(e.to)) reverse.set(e.to, [])
    reverse.get(e.to)!.push(e)
  }
  const best = new Map<string, Propagated>()
  for (const seed of seeds) {
    const queue: Propagated[] = [{ key: seed, confidence: 1, path: [seed], edgeTypes: [], seed }]
    const visited = new Set<string>()
    while (queue.length) {
      const cur = queue.shift()!
      const visitKey = `${cur.seed}|${cur.key}`
      if (visited.has(visitKey)) continue
      visited.add(visitKey)
      const prev = best.get(cur.key)
      if (!prev || prev.confidence < cur.confidence) best.set(cur.key, cur)
      if (cur.path.length - 1 >= maxDepth) continue
      for (const e of reverse.get(cur.key) ?? []) {
        const conf = cur.confidence * e.confidence
        if (conf < 0.25) continue
        queue.push({
          key: e.from,
          confidence: conf,
          path: [...cur.path, e.from],
          edgeTypes: [...cur.edgeTypes, e.type],
          seed: cur.seed
        })
      }
    }
  }
  return best
}

// ===== URL 归一化与匹配 =====

function normalizeTemplate(tpl: string): string {
  let p = (tpl || '').trim()
  if (!p) p = '/'
  if (!p.startsWith('/')) p = '/' + p
  p = p.replace(/\/{2,}/g, '/')
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1)
  return p
}

function templateToRegex(tpl: string): RegExp {
  const norm = normalizeTemplate(tpl)
  const escaped = norm.replace(/[.*+?^${}()|[\]\\]/g, (ch) => ch === '*' ? '*' : `\\${ch}`)
  // 上面的转义会把 * 保留为字面量，需把 ** 还原为通配；再处理 {var}
  const withWildcard = escaped.replace(/\*/g, '.*')
  const withVars = withWildcard.replace(/\\?\{[^}]+\}/g, '[^/]+')
  // 允许任意层级 context-path 前缀（server.servlet.context-path=/ctx 这类部署很常见）：
  //   /ctx + /api/order/123  →  命中  /api/order/{id}
  // 用惰性量词，优先最短前缀，避免把模板首段误吃掉。
  return new RegExp('^/(?:[^/]+/)*?' + withVars.replace(/^\//, '') + '/?$')
}

function normalizeCapturedUrl(url: string): string {
  let u = (url || '').trim()
  try {
    if (/^https?:\/\//i.test(u)) u = new URL(u).pathname
  } catch { /* 保留原值 */ }
  const q = u.indexOf('?')
  if (q >= 0) u = u.substring(0, q)
  const h = u.indexOf('#')
  if (h >= 0) u = u.substring(0, h)
  try { u = decodeURIComponent(u) } catch { /* 保留原值 */ }
  return normalizeTemplate(u)
}

function methodMatches(endpointMethod: string, trafficMethod: string): boolean {
  if (endpointMethod === 'ANY') return true
  return endpointMethod.toUpperCase() === (trafficMethod || '').toUpperCase()
}

// ===== 主流程 =====

export async function analyzeSourceImpact(req: SourceImpactRequest, onStage?: OnStage): Promise<SourceImpactResult> {
  const stages: SourceImpactStage[] = []
  const warnings: string[] = []
  const say = (text: string, percent: number) => {
    stages.push({ text, percent })
    onStage?.({ text, percent })
  }
  const empty = (error: string, errorKind: 'usage' | 'io' | 'notfound'): SourceImpactResult => ({
    success: false, error, errorKind, stages,
    summary: { scannedFiles: 0, changedSymbols: 0, changedClasses: 0, endpoints: 0, affectedEndpoints: 0, affectedCases: 0, unmatchedEndpoints: 0 },
    changes: [], endpoints: [], affected: [], cases: [], warnings
  })

  const maxFiles = req.maxFiles ?? 5000
  const maxFileChars = req.maxFileChars ?? 500000
  const maxDepth = req.maxDepth ?? 4
  if (!req.newSourceRoot || !req.oldSourceRoot) return empty('缺少新版或旧版源码目录', 'usage')
  // 精确指出是哪个版本、哪个路径不存在（支持 ; 分隔的多模块路径）
  const missing = (spec: string) => spec.split(';').map(s => s.trim()).filter(Boolean).filter(p => !fs.existsSync(p))
  const missNew = missing(req.newSourceRoot)
  const missOld = missing(req.oldSourceRoot)
  if (missNew.length || missOld.length) {
    const parts: string[] = []
    if (missNew.length) parts.push(`新版源码目录不存在：${missNew.join('；')}`)
    if (missOld.length) parts.push(`旧版源码目录不存在：${missOld.join('；')}`)
    return empty(parts.join('；'), 'io')
  }

  say('正在扫描新旧源码…', 5)
  const oldSnap = buildSnapshot(req.oldSourceRoot, maxFiles, maxFileChars, warnings)
  const newSnap = buildSnapshot(req.newSourceRoot, maxFiles, maxFileChars, warnings)
  if (!newSnap.nodes.size) return empty('新版源码目录未解析到任何 Java 符号', 'notfound')
  say(`扫描完成：旧版 ${oldSnap.files} 文件 / 新版 ${newSnap.files} 文件`, 15)

  say('正在比对符号差异…', 22)
  const changes = diffSnapshots(oldSnap, newSnap)
  const changedSymbols = changes.filter(c => c.kind === 'METHOD').map(c => c.key)
  const changedTypes = changes.filter(c => c.kind === 'TYPE').map(c => c.key)
  // 类型变更（尤其 POJO 字段）也要作为种子：谁引用了这个类型
  const seeds = [...changedSymbols, ...changedTypes]
  const changedClasses = new Set(changes.map(c => c.qualifiedName.split('.').slice(0, -1).join('.') || c.qualifiedName)).size
  say(`差异完成：${changes.length} 个变更符号（${changedSymbols.length} 方法 / ${changedTypes.length} 类型）`, 32)
  if (!changes.length) warnings.push('新旧源码未发现符号级差异 —— 确认两个源码版本是否选对')

  say('正在构建跨文件依赖图…', 40)
  const edges = buildEdges(newSnap)
  say(`依赖图：${newSnap.nodes.size} 个节点 / ${edges.length} 条边`, 50)

  say('正在反向回溯受影响接口…', 58)
  const reached = propagate(seeds, edges, maxDepth, newSnap.nodes)
  const endpoints: SourceEndpoint[] = []
  for (const node of newSnap.nodes.values()) {
    if (node.kind === 'METHOD' && node.endpoint) {
      endpoints.push({
        httpMethod: node.endpoint.httpMethod,
        path: normalizeTemplate(node.classPath ? joinPath(node.classPath, node.endpoint.rawPath) : node.endpoint.rawPath),
        className: node.className,
        methodName: node.methodName || '',
        symbolKey: node.key,
        locator: `${node.path}:${node.line}`
      })
    }
  }
  say(`识别到 ${endpoints.length} 个 Spring 端点`, 64)

  // 端点命中：端点方法符号本身被 reached；或其所属 Controller 类型节点被 reached
  const typeNodeByClass = new Map<string, string>()
  for (const node of newSnap.nodes.values()) {
    if (node.kind === 'TYPE') typeNodeByClass.set(node.className, node.key)
  }
  const candidateAffected: AffectedEndpoint[] = []
  for (const ep of endpoints) {
    const direct = reached.get(ep.symbolKey)
    const ownerKey = typeNodeByClass.get(ep.className)
    const ownerHit = ownerKey ? reached.get(ownerKey) : undefined
    // 优先方法级命中（通常带 CALLS 证据，路径更精确），其次才是 Controller 类型级命中（INJECTS）
    const hit = direct || ownerHit
    if (!hit) continue
    candidateAffected.push({
      endpoint: ep,
      confidence: hit.confidence,
      seed: hit.seed,
      path: hit.path,
      edgeTypes: hit.edgeTypes,
      cases: [],
      hits: 0,
      evidence: hit.path.slice(0, 8)
    })
  }
  candidateAffected.sort((a, b) => b.confidence - a.confidence)
  say(`回溯命中 ${candidateAffected.length} 个受影响端点`, 70)

  say('正在用抓包流量反查用例…', 78)
  const traffic = (req.traffic ?? []).filter(t => !req.since || !t.timestamp || t.timestamp >= req.since)
  const caseSet = new Map<string, Set<string>>()
  const hitsCount = new Map<string, number>()
  for (const item of candidateAffected) {
    const regex = templateToRegex(item.endpoint.path)
    const cases = new Set<string>()
    let hits = 0
    for (const t of traffic) {
      if (!methodMatches(item.endpoint.httpMethod, t.method)) continue
      if (!regex.test(normalizeCapturedUrl(t.url))) continue
      hits++
      const cn = (t.caseName || '').trim() || '（未命名会话）'
      cases.add(cn)
    }
    item.cases = [...cases].sort()
    item.hits = hits
    caseSet.set(`${item.endpoint.httpMethod} ${item.endpoint.path}`, cases)
    hitsCount.set(`${item.endpoint.httpMethod} ${item.endpoint.path}`, hits)
  }
  const unmatched = candidateAffected.filter(a => a.hits === 0).length
  if (!traffic.length) {
    warnings.push('未提供抓包流量 —— 只输出受影响接口，无法反查用例（属于预期降级）')
  } else if (unmatched) {
    warnings.push(`${unmatched} 个受影响端点没有抓包记录命中：可能是历史没覆盖该接口，或 URL 归一化未匹配（检查路径变量 / context-path）`)
  }
  say(`反查完成：${candidateAffected.length} 个端点 / ${unmatched} 个未命中流量`, 88)

  // 用例聚合（贪心排序：命中端点多的优先）
  const caseAgg = new Map<string, Set<string>>()
  for (const item of candidateAffected) {
    for (const cn of item.cases) {
      if (!caseAgg.has(cn)) caseAgg.set(cn, new Set())
      caseAgg.get(cn)!.add(`${item.endpoint.httpMethod} ${item.endpoint.path}`)
    }
  }
  const cases: AffectedCase[] = [...caseAgg.entries()]
    .map(([caseName, eps]) => ({ caseName, endpoints: [...eps].sort(), hits: eps.size, order: 0 }))
    .sort((a, b) => b.hits - a.hits || a.caseName.localeCompare(b.caseName))
    .map((c, i) => ({ ...c, order: i + 1 }))
  say('分析完成', 100)

  return {
    success: true,
    stages,
    summary: {
      scannedFiles: (oldSnap.files + newSnap.files) / 2,
      changedSymbols: changes.length,
      changedClasses,
      endpoints: endpoints.length,
      affectedEndpoints: candidateAffected.length,
      affectedCases: cases.length,
      unmatchedEndpoints: unmatched
    },
    changes,
    endpoints,
    affected: candidateAffected,
    cases,
    warnings
  }
}

function joinPath(base: string, sub: string): string {
  const b = normalizeTemplate(base)
  const s = normalizeTemplate(sub)
  if (b === '/') return s
  if (s === '/') return b
  return normalizeTemplate(b + '/' + s.replace(/^\//, ''))
}
