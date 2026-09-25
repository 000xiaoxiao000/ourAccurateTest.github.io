/**
 * 影响分析引擎（P0）
 *
 * 原理一句话：**「改了哪行」∩「谁跑过这行」= 受影响的接口与用例**。
 *   - 改了哪行：incremental 命令的 --json 差异明细（类 + 变更行号）
 *   - 谁跑过：report --perkey 一次产出每个 key 一份 XML，解析出行覆盖
 *   - key 是谁：抓包记录里注入的 X-Coverage-Key（traffic 由调用方注入）
 *
 * 刻意不 import electron / database —— 流量数据由调用方注入，
 * 这样本文件可以被 esbuild 打成 cjs 用纯 Node 跑测试（与 git.ts 同构）。
 */
import fs from 'fs'
import path from 'path'
import { runJacocoCli } from './cliRunner.js'

/** 一条带 coverage key 的抓包记录（影响分析反查 key → 接口/用例 用） */
export interface ImpactTraffic {
  key: string
  method: string
  url: string
  protocol: string
  caseName: string
  timestamp: number
}

export interface ImpactRequest {
  /** 产物输出目录（diff.json / perkey XML 都落在这里） */
  workDir: string
  /** .exec 所在目录 */
  execDir: string
  /** 新版 classfiles（解码 exec 必需，多路径数组） */
  classfiles: string[]
  /** 比对基准 A：旧版 classfiles */
  oldClassfiles?: string[]
  /** 比对基准 B：新版源码 + 旧版源码（与 A 二选一，优先 A） */
  newSources?: string[]
  oldSources?: string[]
  /** 抓包流量（带 coverage key 的那些；调用方从流量库注入） */
  traffic: ImpactTraffic[]
  /** 时间窗内抓包记录总数（含没带 key 的）——用于自检区分「没抓包」和「抓了但没注入 key」 */
  trafficTotalCount?: number
  /** 时间窗起点（毫秒），过滤流量；不传 = 全部 */
  since?: number
}

export interface ImpactApi {
  method: string
  url: string
  protocol: string
  /** 命中这些变更行的 key */
  keys: string[]
  /** 关联的用例名 */
  cases: string[]
  /** 命中的变更行数 */
  hits: number
  /** 证据：类:行 */
  evidence: string[]
}

export interface ImpactCase {
  caseName: string
  hits: number
  /** 命中的变更行 / 全部变更行 */
  coveredRatio: number
  keys: string[]
  /** 建议执行顺序（1 开始） */
  order: number
}

export interface ImpactRisk {
  cls: string
  source?: string
  lines: number[]
  methods: string[]
}

export interface ImpactStage {
  text: string
  percent: number
}

export interface ImpactResult {
  success: boolean
  error?: string
  errorKind?: 'usage' | 'io' | 'notfound'
  stages: ImpactStage[]
  /** 真实执行过的命令（供 UI 展示 / 复制到 CI） */
  commands: string[]
  summary: {
    changedLines: number
    changedClasses: number
    affectedApis: number
    affectedCases: number
    uncoveredChanged: number
    keysTotal: number
    keysHit: number
    /** 时间窗内抓包记录总数（含没带 key 的）——区分「没抓包」与「抓了但没注入 key」 */
    trafficTotal: number
    /** 其中带 X-Coverage-Key 的条数（0 = 抓包时覆盖率采集开关没开或 key 为空） */
    trafficKeyed: number
  }
  apis: ImpactApi[]
  cases: ImpactCase[]
  /** 贪心算出的最小回归集（用例名，按建议顺序） */
  minimalSet: string[]
  minimalStats: { cases: number; lines: number; totalLines: number }
  risks: ImpactRisk[]
  warnings: string[]
  /** key 粒度自检提示（疑似请求级 key 会碎片化） */
  keyHint?: string
}

type OnStage = (s: ImpactStage) => void

// ===== 差异明细（incremental --json）=====

interface DiffClass {
  cls: string
  source?: string
  status: string
  lines: number[]
  methods: Array<{ name: string; desc: string }>
}

interface DiffJson {
  summary?: { changedLines?: number; addedClasses?: number; modifiedClasses?: number }
  classes?: Array<{
    class: string
    status?: string
    source?: string | null
    changedLines?: number[]
    methods?: Array<{ name?: string; desc?: string }>
  }>
}

function parseDiff(file: string): DiffClass[] {
  const raw = fs.readFileSync(file, 'utf8')
  const json = JSON.parse(raw) as DiffJson
  const out: DiffClass[] = []
  for (const c of json.classes ?? []) {
    if (!c.class) continue
    out.push({
      cls: c.class,
      source: c.source ?? undefined,
      status: c.status ?? 'MODIFIED',
      lines: (c.changedLines ?? []).filter((n) => Number.isFinite(n)),
      methods: (c.methods ?? []).map((m) => ({ name: m.name ?? '', desc: m.desc ?? '' }))
    })
  }
  return out
}

// ===== perkey XML 行覆盖 =====

/**
 * 逐行扫描 JaCoCo XML，收集「被覆盖的行」。
 * 只取 <class name="X"> 之后 <line nr="N" ... ci="M"> 且 ci>0 的行 —— 指令覆盖数 > 0 才算真跑到。
 */
function parseJacocoXmlLines(file: string): Map<string, Set<number>> {
  const byClass = new Map<string, Set<number>>()
  const text = fs.readFileSync(file, 'utf8')

  // ⚠️ 两个坑（都是实测踩到的）：
  //  1. JaCoCo 的 XML 是【单行】输出，绝不能按 \n 切行解析 —— 那样每行只会匹配到第一个元素。
  //  2. <sourcefile>（真正带 <line> 的元素）是 <package> 的【兄弟节点】，不在 <class> 里面。
  //     所以不能用「当前 class」作为上下文，必须先建立 sourcefilename → class 的映射。
  const srcToClasses = new Map<string, string[]>()
  const classRe = /<class\s+name="([^"]+)"\s+sourcefilename="([^"]+)"/g
  let m: RegExpExecArray | null
  while ((m = classRe.exec(text)) !== null) {
    const arr = srcToClasses.get(m[2])
    if (arr) arr.push(m[1])
    else srcToClasses.set(m[2], [m[1]])
  }

  const sfRe = /<sourcefile\s+name="([^"]+)">([\s\S]*?)<\/sourcefile>/g
  while ((m = sfRe.exec(text)) !== null) {
    const classes = srcToClasses.get(m[1])
    if (!classes) continue
    const lineRe = /<line\s+nr="(\d+)"[^>]*?ci="(\d+)"/g
    let lm: RegExpExecArray | null
    while ((lm = lineRe.exec(m[2])) !== null) {
      if (Number(lm[2]) <= 0) continue
      for (const cls of classes) {
        let set = byClass.get(cls)
        if (!set) byClass.set(cls, (set = new Set<number>()))
        set.add(Number(lm[1]))
      }
    }
  }
  return byClass
}

/** exec 文件名 → key：与 CLI 的 ReportCommand.keyOf 同规则（去 .exec 后取第一个 '-' 之后） */
function keyFromStem(stem: string): string {
  const dash = stem.indexOf('-')
  return dash >= 0 ? stem.substring(dash + 1) : stem
}

// ===== 主流程 =====

function splitPaths(v?: string[]): string[] {
  return (v ?? [])
    .flatMap((p) => String(p).split(';'))
    .map((s) => s.trim())
    .filter(Boolean)
}

export async function analyzeImpact(req: ImpactRequest, onStage?: OnStage): Promise<ImpactResult> {
  const stages: ImpactStage[] = []
  const commands: string[] = []
  const warnings: string[] = []
  const say = (text: string, percent: number) => {
    stages.push({ text, percent })
    onStage?.({ text, percent })
  }

  const empty: ImpactResult = {
    success: false,
    stages,
    commands,
    summary: { changedLines: 0, changedClasses: 0, affectedApis: 0, affectedCases: 0, uncoveredChanged: 0, keysTotal: 0, keysHit: 0, trafficTotal: 0, trafficKeyed: 0 },
    apis: [],
    cases: [],
    minimalSet: [],
    minimalStats: { cases: 0, lines: 0, totalLines: 0 },
    risks: [],
    warnings
  }

  const classfiles = splitPaths(req.classfiles)
  if (!req.execDir) return { ...empty, error: '请先选择 exec 目录', errorKind: 'usage' }
  if (!fs.existsSync(req.execDir)) return { ...empty, error: `exec 目录不存在：${req.execDir}`, errorKind: 'io' }
  if (!classfiles.length) return { ...empty, error: '缺少新版 classfiles（解码 exec 必需）', errorKind: 'usage' }

  const oldClassfiles = splitPaths(req.oldClassfiles)
  const newSources = splitPaths(req.newSources)
  const oldSources = splitPaths(req.oldSources)
  if (!oldClassfiles.length && !(newSources.length && oldSources.length)) {
    return {
      ...empty,
      error: '缺少比对基准：给「旧版 classfiles」，或同时给「新版源码 + 旧版源码」（二选一）',
      errorKind: 'usage'
    }
  }

  const outDir = path.join(req.workDir, 'impact')
  const diffJson = path.join(outDir, 'incremental-diff.json')
  const perkeyDir = path.join(outDir, 'perkey')
  fs.rmSync(perkeyDir, { recursive: true, force: true })
  fs.mkdirSync(perkeyDir, { recursive: true })

  // ---------- 阶段 1：差异比对 ----------
  say('正在比对差异（找出变更行）…', 5)
  const diffArgs: string[] = ['incremental', '--execdir', req.execDir]
  for (const c of classfiles) diffArgs.push('--classfiles', c)
  for (const c of oldClassfiles) diffArgs.push('--old-classfiles', c)
  for (const s of newSources) diffArgs.push('--sourcefiles', s)
  for (const s of oldSources) diffArgs.push('--old-sourcefiles', s)
  diffArgs.push('--json', diffJson)
  commands.push(`java -jar xiaoxiao-jacoco-cli.jar ${diffArgs.join(' ')}`)
  const dr = await runJacocoCli(diffArgs)
  if (dr.code !== 0) {
    return {
      ...empty,
      error: `差异比对失败：${(dr.stderr || dr.stdout || '').trim().slice(0, 400)}`,
      errorKind: 'io'
    }
  }
  if (!fs.existsSync(diffJson)) {
    return { ...empty, error: '差异明细未生成（incremental 未输出 JSON）', errorKind: 'io' }
  }
  const diff = parseDiff(diffJson)
  const changedByClass = new Map<string, Set<number>>()
  const diffMeta = new Map<string, DiffClass>()
  let changedLineCount = 0
  for (const d of diff) {
    if (!d.lines.length) continue
    changedByClass.set(d.cls, new Set(d.lines))
    diffMeta.set(d.cls, d)
    changedLineCount += d.lines.length
  }
  say(`差异比对完成：${diff.length} 个变更类 / ${changedLineCount} 行`, 30)
  if (!changedLineCount) {
    warnings.push('本次未发现变更行 —— 报告里不会有任何受影响接口（确认比对基准是否选对版本）')
  }

  // 变更行的全局标识：类:行
  const allChanged = new Set<string>()
  for (const [cls, lines] of changedByClass) for (const ln of lines) allChanged.add(`${cls}:${ln}`)

  // ---------- 阶段 2：按 key 扫描覆盖 ----------
  say('正在按 key 扫描行覆盖…', 35)
  const reportArgs: string[] = ['report', '--execdir', req.execDir]
  for (const c of classfiles) reportArgs.push('--classfiles', c)
  reportArgs.push('--xml', perkeyDir, '--perkey')
  commands.push(`java -jar xiaoxiao-jacoco-cli.jar ${reportArgs.join(' ')}`)
  const rr = await runJacocoCli(reportArgs)
  if (rr.code !== 0) {
    return {
      ...empty,
      error: `按 key 生成覆盖失败：${(rr.stderr || rr.stdout || '').trim().slice(0, 400)}`,
      errorKind: 'io'
    }
  }
  const xmlFiles = fs.existsSync(perkeyDir)
    ? fs.readdirSync(perkeyDir).filter((f) => f.endsWith('.xml'))
    : []
  if (!xmlFiles.length) {
    return { ...empty, error: `未生成任何 perkey 覆盖报告（exec 目录里有 .exec 吗？）：${req.execDir}`, errorKind: 'notfound' }
  }

  /** key → 命中的变更行（"类:行"） */
  const hitsByKey = new Map<string, Set<string>>()
  const keysTotal = xmlFiles.length
  for (let i = 0; i < xmlFiles.length; i++) {    const f = xmlFiles[i]
    const key = keyFromStem(f.replace(/\.xml$/, ''))
    const covered = parseJacocoXmlLines(path.join(perkeyDir, f))
    const hits = new Set<string>()
    for (const [cls, lines] of covered) {
      const changed = changedByClass.get(cls)
      if (!changed) continue
      for (const ln of lines) {
        if (changed.has(ln)) hits.add(`${cls}:${ln}`)
      }
    }
    // 同一 key 可能有多份 exec（多次 dump）→ 并集合并
    const exist = hitsByKey.get(key)
    if (exist) for (const h of hits) exist.add(h)
    else hitsByKey.set(key, hits)
    say(`扫描 key 覆盖 ${i + 1}/${xmlFiles.length}…`, 35 + Math.round((i / xmlFiles.length) * 35))
  }
  let keysHit = [...hitsByKey.values()].filter((s) => s.size > 0).length
  say(`覆盖扫描完成：${keysTotal} 个 key，其中 ${keysHit} 个跑到过变更行`, 72)

  // ---------- 阶段 3：反查流量 → 接口 / 用例 ----------
  say('正在反查接口与用例…', 75)
  const traffic = req.since ? req.traffic.filter((t) => t.timestamp >= (req.since as number)) : req.traffic
  const keyToTraffic = new Map<string, ImpactTraffic[]>()
  for (const t of traffic) {
    if (!t.key) continue
    const arr = keyToTraffic.get(t.key)
    if (arr) arr.push(t)
    else keyToTraffic.set(t.key, [t])
  }
  const knownKeys = new Set(keyToTraffic.keys())

  // ---- 容错归并：exec 文件名 key 与流量 key 对不上时，按「-后缀」前缀归并 ----
  // exec 文件常被重命名保留 A/B 对比（如 coverage-1.exec 改名 coverage-1-new.exec），
  // keyFromStem 会解析成「1-new」，而流量里落的是「1」。此时唯一候选才归并，避免误挂。
  const remaps: Array<{ from: string; to: string }> = []
  for (const k of [...hitsByKey.keys()]) {
    if (knownKeys.has(k)) continue
    const cands = new Set<string>()
    for (const tk of knownKeys) {
      if (k.startsWith(tk + '-') || tk.startsWith(k + '-')) cands.add(tk)
    }
    if (cands.size === 1) remaps.push({ from: k, to: [...cands][0] })
  }
  if (remaps.length) {
    for (const r of remaps) {
      const hits = hitsByKey.get(r.from) ?? new Set<string>()
      const exist = hitsByKey.get(r.to)
      if (exist) for (const h of hits) exist.add(h)
      else hitsByKey.set(r.to, hits)
      hitsByKey.delete(r.from)
      warnings.push(
        `exec 文件名解析出的 key「${r.from}」在流量记录里没有，已按文件名前缀归并到流量 key「${r.to}」——` +
          'exec 文件被重命名过（如 -new/-old 后缀保留 A/B 对比）会出现这种情况，不影响结论。'
      )
    }
    keysHit = [...hitsByKey.values()].filter((s) => s.size > 0).length
  }

  // 注意：req.traffic 只含带 key 的记录；「抓包总数」由调用方另给，用来区分没抓包 / 抓了没 key
  const trafficKeyed = traffic.length
  const trafficTotal = req.trafficTotalCount ?? trafficKeyed
  const orphanKeys = [...hitsByKey.keys()].filter((k) => !knownKeys.has(k))
  if (orphanKeys.length) {
    if (!trafficTotal) {
      warnings.push(
        `流量库里查不到任何抓包记录 —— exec 里的 key（${orphanKeys.slice(0, 3).join(', ')}）无法反查接口/用例。` +
          '确认抓包记录没有被清空，且时间窗覆盖抓包区间。'
      )
    } else if (!trafficKeyed) {
      // 抓了包但一条带 key 的都没有 —— 最常见根因：抓包时覆盖率采集开关没开或 key 为空
      warnings.push(
        `本次抓包共 ${trafficTotal} 条记录，没有任何一条带 X-Coverage-Key —— 抓包时「覆盖率采集」开关没开或 key 为空，` +
          '代理不会注入请求头，探针也就无法按 key 归因。请到「采集工作台」开启覆盖率采集、填写 key 后重新抓包并 dump。'
      )
    } else {
      warnings.push(
        `有 ${orphanKeys.length} 个 key 在流量记录里查不到（${orphanKeys.slice(0, 5).join(', ')}${orphanKeys.length > 5 ? '…' : ''}）` +
          '：通常是 dump 时间窗与抓包时间窗不一致（exec 是旧一轮的）、该 key 是进程外用 setkey 设置的，' +
          '或 exec 文件被重命名成了流量里没有的前缀（如 coverage-1-x.exec 但没有 key=1 的流量）。可在「时间窗」里放宽范围。'
      )
    }
  }

  // key → 用例名（用例名来自会话；为空则退化为 key 本身，界面上标注「未命名」）
  const keyToCases = new Map<string, Set<string>>()
  for (const [k, list] of keyToTraffic) {
    const set = new Set<string>()
    for (const t of list) set.add(t.caseName?.trim() || '')
    keyToCases.set(k, set)
  }

  // 接口聚合：method + url → 命中行 / key / 用例
  const apiMap = new Map<string, ImpactApi>()
  const caseMap = new Map<string, { hits: Set<string>; keys: Set<string> }>()
  for (const [key, hits] of hitsByKey) {
    if (!hits.size) continue
    const trs = keyToTraffic.get(key) ?? []
    const caseNames = [...(keyToCases.get(key) ?? [])]
    for (const t of trs) {
      const id = `${t.method} ${t.url}`
      let api = apiMap.get(id)
      if (!api) apiMap.set(id, (api = { method: t.method, url: t.url, protocol: t.protocol, keys: [], cases: [], hits: 0, evidence: [] }))
      if (!api.keys.includes(key)) api.keys.push(key)
      for (const c of caseNames) {
        const cn = c || '（未命名会话）'
        if (!api.cases.includes(cn)) api.cases.push(cn)
      }
    }
    for (const cnRaw of caseNames) {
      const cn = cnRaw || '（未命名会话）'
      let e = caseMap.get(cn)
      if (!e) caseMap.set(cn, (e = { hits: new Set<string>(), keys: new Set<string>() }))
      for (const h of hits) e.hits.add(h)
      e.keys.add(key)
    }
  }
  // 每个接口的命中行 = 其关联 key 的命中行并集（跨请求）
  for (const api of apiMap.values()) {
    const union = new Set<string>()
    for (const k of api.keys) for (const h of hitsByKey.get(k) ?? []) union.add(h)
    api.hits = union.size
    api.evidence = [...union].sort().slice(0, 8).map((h) => h.replace(':', ':'))
  }

  const apis = [...apiMap.values()].sort((a, b) => b.hits - a.hits)
  const cases: ImpactCase[] = [...caseMap.entries()]
    .map(([caseName, v]) => ({
      caseName,
      hits: v.hits.size,
      coveredRatio: allChanged.size ? v.hits.size / allChanged.size : 0,
      keys: [...v.keys]
    }))
    .sort((a, b) => b.hits - a.hits)
    .map((c, i) => ({ ...c, order: i + 1 }))
  say(`反查完成：${apis.length} 个受影响接口 / ${cases.length} 个用例`, 88)

  // ---------- 阶段 4：最小回归集（贪心集合覆盖） ----------
  say('正在计算最小回归集…', 90)
  const remaining = new Set(allChanged)
  const minimalSet: string[] = []
  const pool = [...caseMap.entries()].sort((a, b) => b[1].hits.size - a[1].hits.size)
  while (remaining.size && pool.length) {
    let bestIdx = -1
    let bestGain = 0
    for (let i = 0; i < pool.length; i++) {
      let gain = 0
      for (const h of pool[i][1].hits) if (remaining.has(h)) gain++
      if (gain > bestGain) {
        bestGain = gain
        bestIdx = i
      }
    }
    if (bestIdx < 0 || bestGain === 0) break
    const [name, v] = pool[bestIdx]
    minimalSet.push(name || '（未命名会话）')
    for (const h of v.hits) remaining.delete(h)
    pool.splice(bestIdx, 1)
  }

  // ---------- 阶段 5：未覆盖风险 ----------
  const coveredByAnybody = new Set<string>()
  for (const hits of hitsByKey.values()) for (const h of hits) coveredByAnybody.add(h)
  const riskLines = new Map<string, number[]>()
  for (const h of allChanged) {
    if (coveredByAnybody.has(h)) continue
    const p = h.lastIndexOf(':')
    const cls = h.slice(0, p)
    const ln = Number(h.slice(p + 1))
    const arr = riskLines.get(cls)
    if (arr) arr.push(ln)
    else riskLines.set(cls, [ln])
  }
  const risks: ImpactRisk[] = [...riskLines.entries()].map(([cls, lines]) => ({
    cls,
    source: diffMeta.get(cls)?.source,
    lines: lines.sort((a, b) => a - b),
    methods: (diffMeta.get(cls)?.methods ?? []).map((m) => m.name).filter(Boolean)
  }))
  say('分析完成', 100)

  // key 粒度自检
  let keyHint: string | undefined
  if (keysTotal >= 30) {
    const looksRandom = [...hitsByKey.keys()].some((k) => k.length >= 24 || /[0-9a-f]{8}-[0-9a-f]{4}-/.test(k))
    if (looksRandom) {
      keyHint =
        `当前有 ${keysTotal} 个 key 且疑似请求级随机值（如 uuid）——结果会碎片化成「谁访问过」，答不了「哪个用例该回归」。` +
        '建议把 X-Coverage-Key 换成用例级（用例 ID），或用 `cli setkey --key <用例ID>` 在进程外切换。'
    }
  }

  return {
    success: true,
    stages,
    commands,
    summary: {
      changedLines: changedLineCount,
      changedClasses: diff.length,
      affectedApis: apis.length,
      affectedCases: cases.length,
      uncoveredChanged: [...riskLines.values()].reduce((a, b) => a + b.length, 0),
      keysTotal,
      keysHit,
      trafficTotal,
      trafficKeyed
    },
    apis,
    cases,
    minimalSet,
    minimalStats: {
      cases: minimalSet.length,
      lines: allChanged.size - remaining.size,
      totalLines: allChanged.size
    },
    risks,
    warnings,
    keyHint
  }
}
