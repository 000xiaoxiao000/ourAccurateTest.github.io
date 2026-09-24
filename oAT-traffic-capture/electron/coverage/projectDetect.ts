import fs from 'fs'
import path from 'path'
import { spawnSync } from 'child_process'

/**
 * 工程探测：只填「项目根目录」，自动推导覆盖率报告的两个分母输入
 *   ① classfiles（覆盖率分母）：target/classes → build/classes/java/main → ... → 仓库 zip（按项目名匹配）
 *   ② sourcefiles（源码根，报告可跳源码）：src/main/java → src/main/kotlin → src/java → src
 * 并做可达性校验：源码根必须能命中 classfiles 里的包结构，否则报告无源码跳转。
 */

export interface PathProbe {
  path: string
  kind: 'dir' | 'zip' | 'jar' | 'none'
  exists: boolean
  /** classfiles: .class 数量；sourcefiles: .java 数量 */
  fileCount: number
  /** 源码根与 classfiles 包结构的命中数（仅源码根有意义） */
  packageHits: number
  note: string
}

export interface DetectResult {
  success: boolean
  projectDir: string
  classfilesPath: string      // 多模块用 ';' 拼接
  sourcefilesPath: string     // 多模块用 ';' 拼接
  classfiles?: PathProbe
  sourcefiles?: PathProbe
  classfilesCandidates: PathProbe[]
  sourcefilesCandidates: PathProbe[]
  warnings: string[]
  error?: string
}

const CLASS_DIR_CANDIDATES = ['target/classes', 'build/classes/java/main', 'build/classes/kotlin/main', 'out/production/classes', 'target/test-classes', 'build/classes/java/test']
const SOURCE_DIR_CANDIDATES = ['src/main/java', 'src/main/kotlin', 'src/main/scala', 'src/java', 'src/main', 'src']
const DEFAULT_CLASSFILES_REPO = '/Users/xiaoxiao/oATagent/classfiles'
const SCAN_CAP = 30000

function statDir(p: string): fs.Stats | undefined {
  try { const s = fs.statSync(p); return s.isDirectory() ? s : undefined } catch { return undefined }
}

function zipBin(): string {
  for (const c of ['/usr/bin/unzip', '/usr/local/bin/unzip', 'unzip']) {
    try { if (fs.existsSync(c)) return c } catch { /* ignore */ }
  }
  return 'unzip'
}

/** 统计目录下的目标文件数量（限量扫描） */
function countFilesDeep(dir: string, ext: string, cap = SCAN_CAP): number {
  let n = 0
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop() as string
    let entries: fs.Dirent[]
    try { entries = fs.readdirSync(d, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      if (n >= cap) return n
      const full = path.join(d, e.name)
      if (e.isDirectory()) stack.push(full)
      else if (e.name.endsWith(ext)) n++
    }
  }
  return n
}

/** zip/jar 内的 .class entry 数量（用 unzip -Z1 列条目，避免引入三方库） */
function countClassEntries(archive: string): number {
  try {
    const r = spawnSync(zipBin(), ['-Z1', archive], { encoding: 'utf8', timeout: 20000 })
    const out = (r.stdout || '') + (r.error ? '' : '')
    if (!out) return -1
    return out.split('\n').filter((l) => l.trim().endsWith('.class')).length
  } catch {
    return -1
  }
}

/** 从 classfiles（目录或 zip）采样若干包路径，如 com/xxl/job/admin */
function samplePackages(src: string, limit = 8): string[] {
  const pkgs = new Set<string>()
  const isArchive = /\.(zip|jar|war)$/i.test(src)
  if (isArchive) {
    try {
      const r = spawnSync(zipBin(), ['-Z1', src], { encoding: 'utf8', timeout: 20000 })
      for (const line of (r.stdout || '').split('\n')) {
        const l = line.trim()
        if (!l.endsWith('.class')) continue
        const dir = path.posix.dirname(l)
        if (dir && dir !== '.' && !dir.includes('..')) pkgs.add(dir)
        if (pkgs.size >= limit * 4) break
      }
    } catch { /* ignore */ }
  } else {
    const stack = [src]
    while (stack.length && pkgs.size < limit * 4) {
      const d = stack.pop() as string
      let entries: fs.Dirent[]
      try { entries = fs.readdirSync(d, { withFileTypes: true }) } catch { continue }
      for (const e of entries) {
        const full = path.join(d, e.name)
        if (e.isDirectory()) { stack.push(full); continue }
        if (e.name.endsWith('.class')) {
          const rel = path.relative(src, path.dirname(full)).split(path.sep).join('/')
          if (rel && rel !== '.') pkgs.add(rel)
        }
      }
    }
  }
  return Array.from(pkgs).slice(0, limit)
}

/** 源码根是否命中包结构：检查 <srcRoot>/<pkgPath> 或前两段存在 */
function countPackageHits(srcRoot: string, pkgs: string[]): number {
  let hits = 0
  for (const p of pkgs) {
    if (fs.existsSync(path.join(srcRoot, p))) hits++
    else {
      const seg = p.split('/').slice(0, 2).join('/')
      if (seg && fs.existsSync(path.join(srcRoot, seg))) hits += 0.5
    }
  }
  return hits
}

function probeClassfiles(p: string): PathProbe {
  if (!p) return { path: p, kind: 'none', exists: false, fileCount: 0, packageHits: 0, note: '未填写' }
  const st = statDir(p)
  if (st) {
    const n = countFilesDeep(p, '.class')
    return { path: p, kind: 'dir', exists: true, fileCount: n, packageHits: 0, note: n > 0 ? `目录下 ${n} 个 .class` : '目录下没有 .class（可能不是构建产物目录）' }
  }
  if (fs.existsSync(p)) {
    const n = countClassEntries(p)
    const kind = /\.jar$/i.test(p) ? 'jar' : 'zip'
    return { path: p, kind, exists: true, fileCount: n, packageHits: 0, note: n > 0 ? `${kind.toUpperCase()} 内 ${n} 个 .class` : `无法读取 ${kind.toUpperCase()} 条目（unzip 不可用或文件损坏）` }
  }
  return { path: p, kind: 'none', exists: false, fileCount: 0, packageHits: 0, note: '路径不存在' }
}

function probeSourcefiles(p: string, pkgs: string[]): PathProbe {
  if (!p) return { path: p, kind: 'none', exists: false, fileCount: 0, packageHits: 0, note: '未填写（报告无源码跳转）' }
  const st = statDir(p)
  if (!st) return { path: p, kind: 'none', exists: fs.existsSync(p), fileCount: 0, packageHits: 0, note: '路径不存在或不是目录' }
  const n = countFilesDeep(p, '.java')
  const hits = pkgs.length ? countPackageHits(p, pkgs) : 0
  let note = `目录下 ${n} 个 .java`
  if (pkgs.length && hits === 0) note += '，但包名结构与 classfiles 对不上（源码跳转会失效，需指到 src/main/java 这一层）'
  else if (pkgs.length) note += `，包结构命中 ${hits}/${pkgs.length}`
  return { path: p, kind: 'dir', exists: true, fileCount: n, packageHits: hits, note }
}

/** 收集模块（项目根 + 一层子模块，识别 pom.xml / build.gradle） */
function collectModules(projectDir: string): string[] {
  const mods = [projectDir]
  let entries: fs.Dirent[] = []
  try { entries = fs.readdirSync(projectDir, { withFileTypes: true }) } catch { return mods }
  for (const e of entries) {
    if (!e.isDirectory()) continue
    const full = path.join(projectDir, e.name)
    if (fs.existsSync(path.join(full, 'pom.xml')) || fs.existsSync(path.join(full, 'build.gradle')) || fs.existsSync(path.join(full, 'build.gradle.kts'))) {
      mods.push(full)
    }
  }
  return mods
}

/** 在项目根下探测候选（相对路径列表），返回已存在且非空（或允许空）的绝对路径 */
function expandCandidates(projectDir: string, rels: string[], needFiles: boolean, ext: string): string[] {
  const mods = collectModules(projectDir)
  const out: string[] = []
  for (const m of mods) {
    for (const rel of rels) {
      const p = path.join(m, rel)
      if (!statDir(p)) continue
      if (needFiles && countFilesDeep(p, ext) === 0) continue
      if (!out.includes(p)) out.push(p)
    }
  }
  return out
}

/** 仓库 zip 按项目名匹配：<repo>/<项目名>.zip|.jar，退化为包含项目名的模糊匹配 */
function matchRepoArchive(projectDir: string, repo: string): string | undefined {
  if (!repo || !statDir(repo)) return undefined
  const name = path.basename(projectDir)
  for (const ext of ['.zip', '.jar']) {
    const p = path.join(repo, name + ext)
    if (fs.existsSync(p)) return p
  }
  try {
    const files = fs.readdirSync(repo)
    const fuzzy = files.filter((f) => /\.(zip|jar)$/i.test(f) && f.toLowerCase().includes(name.toLowerCase()))
    if (fuzzy.length) return path.join(repo, fuzzy.sort()[0])
  } catch { /* ignore */ }
  return undefined
}

/** 主入口：由项目根目录推导 classfiles 与源码根 */
export function detectProject(opts: { projectDir: string; classfilesRepo?: string }): DetectResult {
  const projectDir = (opts.projectDir || '').trim()
  const repo = (opts.classfilesRepo || '').trim() || DEFAULT_CLASSFILES_REPO
  const warnings: string[] = []
  if (!projectDir) return { success: false, projectDir: '', classfilesPath: '', sourcefilesPath: '', classfilesCandidates: [], sourcefilesCandidates: [], warnings, error: '请先填写项目根目录' }
  if (!statDir(projectDir)) return { success: false, projectDir, classfilesPath: '', sourcefilesPath: '', classfilesCandidates: [], sourcefilesCandidates: [], warnings, error: '项目根目录不存在或不是目录: ' + projectDir }

  // ① classfiles：仓库归档优先（通常是从运行实例拉回的当前版本字节码，与运行时最一致），再退到项目构建产物
  const repoArchive = matchRepoArchive(projectDir, repo)
  const cfDirs = expandCandidates(projectDir, CLASS_DIR_CANDIDATES, true, '.class')
  const ordered = [repoArchive, ...cfDirs].filter((p): p is string => !!p)
  const cfProbes = ordered.map(probeClassfiles).filter((p) => p.exists && p.fileCount > 0)
  const cfPaths = cfProbes.map((p) => p.path)
  // 全部候选（含无 .class 的目录）也回传，供 UI 一键切换
  const cfAllProbes = ordered.map(probeClassfiles)
  if (repoArchive && cfProbes[0]?.path === repoArchive) {
    warnings.push('已优先选用 classfiles 仓库归档（与运行实例版本一致）：' + repoArchive)
  }
  if (!repoArchive && cfDirs.length && cfProbes.length === 0) {
    warnings.push('找到构建产物目录但其中没有 .class，请确认已编译（mvn package / gradle build）')
  }

  // ② 源码根（用 classfiles 的包结构打分；只保留真正命中包结构的，避免 src / src/main 这类冗余根）
  const pkgs = cfPaths.length ? samplePackages(cfPaths[0]) : []
  const srcDirs = expandCandidates(projectDir, SOURCE_DIR_CANDIDATES, true, '.java')
  const srcProbesAll = srcDirs.map((p) => probeSourcefiles(p, pkgs)).sort((a, b) => b.packageHits - a.packageHits || b.fileCount - a.fileCount)
  const srcProbes = srcProbesAll.filter((p) => p.packageHits > 0)
  const srcPicked = srcProbes.length ? srcProbes : srcProbesAll.slice(0, 1)
  const sourcefilesPath = srcPicked.map((p) => p.path).join(';')

  const classfilesPath = cfProbes.length ? cfProbes[0].path : (cfPaths.length ? cfPaths.join(';') : '')

  if (!classfilesPath) warnings.push('未找到 classfiles（覆盖率分母）：请手动指定 target/classes 或构建产物 zip')
  if (!sourcefilesPath) warnings.push('未找到源码目录（可选，缺失则报告无源码跳转）')
  else if (srcPicked.length > 1) warnings.push(`多模块：已合并 ${srcPicked.length} 个源码目录`)

  return {
    success: !!classfilesPath || !!sourcefilesPath,
    projectDir,
    classfilesPath,
    sourcefilesPath,
    classfiles: cfProbes[0],
    sourcefiles: srcPicked[0],
    classfilesCandidates: cfAllProbes,
    sourcefilesCandidates: srcProbesAll,
    warnings
  }
}

/**
 * 源码根定位（供 Git 检出后的工作区复用）：在项目根/各模块下按候选表找第一个含 .java 的目录。
 * 多模块用 ';' 拼接（CLI 的 --sourcefiles 支持重复传参）。找不到则退化为「目录本身含 .java 就用它」。
 */
export function detectSourceRoot(dir: string): string {
  if (!dir || !statDir(dir)) return ''
  const mods = collectModules(dir)
  const out: string[] = []
  for (const m of mods) {
    for (const rel of SOURCE_DIR_CANDIDATES) {
      const p = path.join(m, rel)
      if (!statDir(p)) continue
      if (countFilesDeep(p, '.java') === 0) continue
      out.push(p)
      break
    }
  }
  if (out.length) return out.join(';')
  return countFilesDeep(dir, '.java') > 0 ? dir : ''
}

/** 单路径即时校验（输入框改动时调用，轻量） */
export function checkPath(opts: { path: string; role: 'classfiles' | 'sourcefiles'; classfilesPath?: string }): PathProbe {
  const p = (opts.path || '').trim()
  if (opts.role === 'sourcefiles') {
    const pkgs = (opts.classfilesPath || '').split(';').map((s) => s.trim()).filter(Boolean)
      .slice(0, 1).flatMap((c) => samplePackages(c, 6))
    // 源码目录支持 ; 拼接多个源码根（Git 拉取多模块仓库时会回填多个），逐个探测后汇总
    const parts = p.split(';').map((s) => s.trim()).filter(Boolean)
    if (parts.length <= 1) return probeSourcefiles(p, pkgs)
    const probes = parts.map((s) => probeSourcefiles(s, pkgs))
    const missing = parts.filter((s, i) => !probes[i].exists)
    if (missing.length === parts.length) return probes[0]
    const files = probes.reduce((n, r) => n + r.fileCount, 0)
    const hits = probes.reduce((n, r) => n + r.packageHits, 0)
    const hitTotal = probes.length * (pkgs.length || 0)
    let note = `${parts.length} 个源码根，共 ${files} 个 .java`
    if (pkgs.length) note += `，包结构命中 ${hits}/${hitTotal}`
    if (missing.length) note += `；⚠ ${missing.length} 个源码根不存在：${missing.join(' , ')}`
    return { path: p, kind: 'dir', exists: missing.length < parts.length, fileCount: files, packageHits: pkgs.length ? hits : 0, note }
  }
  return probeClassfiles(p)
}
