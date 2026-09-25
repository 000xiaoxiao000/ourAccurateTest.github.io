import fs from 'fs'
import os from 'os'
import path from 'path'
import { spawn } from 'child_process'
import { app } from 'electron'
import type { CoverageConfig, CoverageExecInfo } from '../types.js'
import { fileSize, runJacocoCli } from './cliRunner.js'
import { resolveClassfiles } from './classfiles.js'
import { buildRuntimeEnv, getBackend } from './backends.js'
import { injectReportI18n } from './reportI18n.js'
import { fmtStep } from './lang/types.js'
import type { RunStep } from './lang/types.js'
export { checkPath } from './projectDetect.js'
export type { PathProbe } from './projectDetect.js'

// ===== 在线探针：发现（lsof）+ 探活（JaCoCo RemoteControl 二进制协议）+ 识别 =====
import { discoverLocal, expandTargets, parsePorts, probeMany, probeOne, scanRange, sortProbes, summarize } from './probes.js'
export type {
  LocalCandidate,
  ProbeAgent,
  ProbeDiagnostics,
  ProbeKeyStat,
  ProbeResult,
  ProbeStatus,
  ProbeSummary
} from './probes.js'

export { probeOne, scanRange, expandTargets, parsePorts }
/**
 * 心跳/刷新用：批量探测（不同地址之间并发；单个连接一次完成判定，绝不在同一 agent 上排两次队）。
 * 调用方需保证 list 里同一 host:port 只出现一次。
 */
export async function probeAgents(req: {
  agents: Array<{ id?: string; host: string; port: number; label?: string; source?: import('./probes.js').ProbeResult['source']; pid?: string }>
  timeoutMs?: number
  /**
   * 并发度。默认 12：登记几十上百个探针时，旧值 6 会让一轮心跳耗时超过 20s 心跳间隔（追尾）。
   * ⚠️ 并发是「对不同地址」，同一地址仍只有一次握手，不会因为调高而重复排队。
   */
  concurrency?: number
}) {
  const results = await probeMany(
    req.agents.map((a) => ({
      id: a.id ?? `${a.host}:${a.port}`,
      host: a.host,
      port: a.port,
      label: a.label,
      source: a.source ?? 'registered',
      pid: a.pid
    })),
    { timeoutMs: req.timeoutMs ?? 1500, concurrency: req.concurrency ?? 12 }
  )
  return { probes: sortProbes(results), summary: summarize(results) }
}

export { discoverLocal }

export { resolveClassfiles }

// ===== Git 源码供给（增量报告的源码输入；classfiles 仍需从构建侧拿） =====
import {
  cleanupGitSource,
  detectGit,
  listGitCommits,
  listGitRefs,
  prepareGitSource
} from './git.js'
export {
  cleanupGitSource,
  detectGit,
  listGitCommits,
  listGitRefs,
  prepareGitSource
}
export type {
  GitCapability,
  GitCommit,
  GitCredentials,
  GitLogFn,
  GitPrepareRequest,
  GitPrepareResult,
  GitRefItem,
  GitRefQuery
} from './git.js'

export function gitCapability(force = false) {
  return detectGit(force)
}
export function gitRefs(q: import('./git.js').GitRefQuery) {
  return listGitRefs(coverageWorkDir(), q)
}
export function gitCommits(q: import('./git.js').GitRefQuery & { ref?: string; limit?: number; keyword?: string }, onLog?: import('./git.js').GitLogFn) {
  return listGitCommits(coverageWorkDir(), q, onLog)
}
export function gitPrepare(req: import('./git.js').GitPrepareRequest, onLog?: import('./git.js').GitLogFn) {
  return prepareGitSource(coverageWorkDir(), req, onLog)
}
export function gitCleanup(opts?: { all?: boolean }) {
  return cleanupGitSource(coverageWorkDir(), opts)
}

// ===== 影响分析（变更行 ∩ 谁跑过 = 受影响接口 / 用例） =====
import { analyzeImpact } from './impact.js'
import { loadCoverageKeyTraffic, countRecords } from '../database.js'
export type { ImpactRequest, ImpactResult } from './impact.js'

/** 多路径参数按 ; 拆分（与前端「多模块用 ; 分隔」一致） */
function splitPaths(v?: string): string[] {
  return String(v ?? '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean)
}

/**
 * 影响分析。traffic（key → 接口 / 用例）在这里从流量库注入，
 * impact.ts 本身不依赖 electron / 数据库，方便纯 Node 单测。
 */
export async function impactAnalyze(
  req: {
    execDir: string
    classfiles: string
    oldClassfiles?: string
    newSources?: string
    oldSources?: string
    since?: number
  },
  onStage?: (s: { text: string; percent: number }) => void
) {
  return analyzeImpact(
    {
      workDir: coverageWorkDir(),
      execDir: req.execDir,
      classfiles: splitPaths(req.classfiles),
      oldClassfiles: splitPaths(req.oldClassfiles),
      newSources: splitPaths(req.newSources),
      oldSources: splitPaths(req.oldSources),
      since: req.since,
      traffic: loadCoverageKeyTraffic({ since: req.since }),
      trafficTotalCount: countRecords({ since: req.since })
    },
    onStage
  )
}

function coverageWorkDir(): string {
  const base = (app && app.getPath) ? app.getPath('userData') : os.tmpdir()
  const dir = path.join(base, 'oat-coverage')
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

function defaultExecOutDir(): string {
  const dir = path.join(coverageWorkDir(), 'execs')
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

/** 顺序执行一组步骤（spawn）；任一失败即中止 */
async function runSteps(steps: RunStep[]): Promise<{ success: boolean; stdout: string; stderr: string; error?: string }> {
  let out = ''
  let err = ''
  for (const s of steps) {
    console.info('[覆盖率] $ %s %s%s', s.cmd, s.args.join(' '), s.description ? `  # ${s.description}` : '')
    const r = await new Promise<{ code: number; stdout: string; stderr: string }>((resolve) => {
      const p = spawn(s.cmd, s.args, { windowsHide: true, cwd: s.cwd })
      let so = ''
      let se = ''
      p.stdout.on('data', (d) => (so += d.toString()))
      p.stderr.on('data', (d) => (se += d.toString()))
      p.on('error', () => resolve({ code: -1, stdout: so, stderr: `未找到命令: ${s.cmd}` }))
      p.on('close', (code) => resolve({ code: code ?? -1, stdout: so, stderr: se }))
    })
    out += r.stdout
    err += r.stderr
    if (r.code !== 0) {
      console.error('[覆盖率] 步骤失败 (%d) %s: %s', r.code, s.cmd, (err || out || `命令失败: ${s.cmd}`).slice(0, 500))
      return { success: false, stdout: out, stderr: err, error: err || out || `命令失败: ${s.cmd}` }
    }
  }
  return { success: true, stdout: out, stderr: err }
}

/** ① 抓取 .exec（仅 Java 后端有采集步骤；其它语言采集在服务运行期完成） */
export async function dumpExecs(config: CoverageConfig, backendId: string, values: Record<string, string>, key: string) {
  const backend = getBackend(backendId)
  if (!backend?.collect) {
    return { success: false, error: '该后端无需采集步骤（采集在服务运行期完成，直接生成报告即可）' }
  }
  const env = buildRuntimeEnv(config.classfilesPath)
  const workdir = defaultExecOutDir()
  let plan
  try {
    plan = backend.collect(values, env, { key, workdir, execs: [], agentAddress: config.agentAddress })
  } catch (e) {
    console.error('[覆盖率] collect 构建异常: %s', (e as Error)?.stack || e)
    return { success: false, error: '采集参数异常: ' + ((e as Error)?.message || String(e)) }
  }
  const run = await runSteps(plan.steps)
  if (!run.success) return { success: false, error: run.error }
  const execs: CoverageExecInfo[] = plan.outputs.map((f) => ({
    file: f,
    key,
    size: fileSize(f),
    fetchedAt: Date.now(),
    source: 'dump · tcpserver 远程拉取'
  }))
  return { success: true, execs }
}

/** 由步骤里带输出路径的 flag 收集产出文件（--destfile/--zip/-o/-d/--report-dir 等） */
function collectOutputPaths(steps: RunStep[]): string[] {
  const outFlags = new Set(['--destfile', '--zip', '-o', '-d', '--output-directory', '--report-dir', '--output-file', '--html', '--xml', '--csv'])
  const outputs: string[] = []
  for (const s of steps) {
    for (let i = 0; i < s.args.length; i++) {
      if (outFlags.has(s.args[i]) && s.args[i + 1]) outputs.push(s.args[i + 1])
    }
  }
  return outputs
}

/** 推断 HTML 报告目录（iframe 按 reportDir/index.html 加载） */
function inferReportDir(backendId: string, commandId: string, values: Record<string, string>, workdir: string): string | undefined {
  const resolveIn = (base: string | undefined, d: string) => (path.isAbsolute(d) ? d : path.resolve(base || process.cwd(), d))
  const asReportOut = (v: Record<string, string>) => {
    const raw = (v.reportOutDir || '').trim()
    return raw ? resolveIn(workdir, raw) : undefined
  }
  switch (backendId) {
    case 'jacoco':
      // ⚠️ 必须与 buildReportArgs / incremental.build 的输出目录解析完全一致（含自定义 reportOutDir），否则报告目录指向不存在的路径
      if (commandId === 'report') return asReportOut(values) ?? path.join(workdir, 'report')
      if (commandId === 'incremental') return asReportOut(values) ?? path.join(workdir, 'incremental-report')
      return undefined
    case 'nyc':
      return commandId === 'report' ? resolveIn(values.projectDir, values.reportDir || 'coverage') : undefined
    case 'coverage-py':
      return commandId === 'html' ? resolveIn(values.projectDir, values.outputDir || 'coverage_html') : undefined
    case 'go-cov':
      return commandId === 'cover' ? path.resolve(values.outputDir || process.cwd()) : undefined
    case 'gcov-lcov':
      return commandId === 'genhtml' ? resolveIn(values.projectDir, values.outputDir || 'coverage_html') : undefined
    default:
      return undefined
  }
}

/** 单条指令预览：按 commandId 构造真实命令行文本（不执行） */
export async function commandPreview(config: CoverageConfig, backendId: string, commandId: string, values: Record<string, string>, execs: string[]) {
  try {
    const backend = getBackend(backendId)
    if (!backend) return { success: false, error: '未知后端: ' + backendId }
    const cmd = backend.commands.find((c) => c.id === commandId)
    if (!cmd) return { success: false, error: '未知指令: ' + commandId }
    const env = buildRuntimeEnv(config.classfilesPath)
    const steps = cmd.build(values ?? {}, env, { key: config.key, workdir: defaultExecOutDir(), execs: execs ?? [], agentAddress: config.agentAddress })
    if (steps.length === 0) return { success: true, text: '# 该指令无产出（如「生成 HTML」关闭时）' }
    return { success: true, text: steps.map(fmtStep).join('\n') }
  } catch (e) {
    // 预览任何异常都显式回传，渲染端绝不停留在「加载中」
    console.error('[覆盖率] 指令预览异常 %s.%s: %s', backendId, commandId, (e as Error)?.stack || e)
    return { success: false, error: '预览异常: ' + ((e as Error)?.message || String(e)) }
  }
}

/** 执行单条指令（插件 commands 定义的真实 CLI 命令） */
export async function runCommand(config: CoverageConfig, backendId: string, commandId: string, values: Record<string, string>, key: string, execs: string[]) {
  const backend = getBackend(backendId)
  if (!backend) return { success: false, error: '未知后端: ' + backendId }
  const cmd = backend.commands.find((c) => c.id === commandId)
  if (!cmd) return { success: false, error: '未知指令: ' + commandId }
  const env = buildRuntimeEnv(config.classfilesPath)
  const workdir = defaultExecOutDir()
  console.info('[覆盖率] 执行指令 %s.%s key=%s', backendId, commandId, key || '(空)')
  let steps
  try {
    steps = cmd.build(values ?? {}, env, { key: key ?? config.key, workdir, execs: execs ?? [], agentAddress: config.agentAddress })
  } catch (e) {
    console.error('[覆盖率] 指令构建异常 %s.%s: %s', backendId, commandId, (e as Error)?.stack || e)
    return { success: false, error: '指令参数异常: ' + ((e as Error)?.message || String(e)) }
  }
  if (steps.length === 0) return { success: false, error: '该指令当前配置下无产出步骤' }
  const run = await runSteps(steps)
  if (!run.success) return { success: false, error: run.error, stdout: run.stdout, stderr: run.stderr }
  const outputs = collectOutputPaths(steps).filter((f) => fs.existsSync(f))
  const reportDir = inferReportDir(backendId, commandId, values ?? {}, workdir)
  // dump 类指令把 .exec 纳入数据源列表
  let execsInfo: CoverageExecInfo[] | undefined
  if (backendId === 'jacoco' && commandId === 'dump') {
    // .exec 产出路径由指令参数（--destfile）决定，从步骤里派生，兼容自选输出目录
    const execOuts = outputs.filter((f) => f.endsWith('.exec'))
    execsInfo = execOuts.map((f) => ({ file: f, key: key || 'default', size: fileSize(f), fetchedAt: Date.now(), source: 'dump · tcpserver 远程拉取' }))
  }
  // incremental 的入口页是 incremental-summary.html（不是 index.html），报告预览要用它
  const reportEntry = commandId === 'incremental' ? 'incremental-summary.html' : 'index.html'
  const hasHtml = reportDir ? fs.existsSync(path.join(reportDir, reportEntry)) : undefined
  if (reportDir && hasHtml) injectReportI18n(reportDir)
  console.info('[覆盖率] 指令完成 %s.%s 产出=%d 个%s', backendId, commandId, outputs.length, reportDir ? ` 报告=${reportDir}` : '')
  return { success: true, text: run.stdout || run.stderr || undefined, outputs, reportDir, hasHtml, reportEntry: reportDir ? reportEntry : undefined, execs: execsInfo }
}

/** 命令预览（UI 展示真实指令与参数） */
export async function previewCommand(config: CoverageConfig, backendId: string, values: Record<string, string>, execs: string[]) {
  try {
    const backend = getBackend(backendId)
    if (!backend) return { success: false, error: '未知后端: ' + backendId }
    const env = buildRuntimeEnv(config.classfilesPath)
    const text = backend.preview(values, env, { key: config.key, workdir: '', execs: execs ?? [], agentAddress: config.agentAddress })
    return { success: true, text }
  } catch (e) {
    // 预览任何异常都显式回传，渲染端绝不停留在「加载中」
    console.error('[覆盖率] 预览异常 %s: %s', backendId, (e as Error)?.stack || e)
    return { success: false, error: '预览异常: ' + ((e as Error)?.message || String(e)) }
  }
}

/** ③ 生成报告：解析 classfiles（仅本地）→ 后端产出原生报告 */
export async function generateReport(config: CoverageConfig, backendId: string, values: Record<string, string>, execs: string[]) {
  const backend = getBackend(backendId)
  if (!backend) return { success: false, error: '未知后端: ' + backendId }
  if (backend.collect && (!execs || execs.length === 0)) {
    console.warn('[覆盖率] report 中止：后端 %s 需要 .exec 输入但列表为空', backendId)
    return { success: false, error: '请先抓取 .exec（Java 后端需要 exec 输入）' }
  }
  const env = buildRuntimeEnv(config.classfilesPath)
  const workdir = path.join(coverageWorkDir(), 'reports', config.key || 'default')
  console.info('[覆盖率] 开始生成报告 后端=%s key=%s classfiles=%s exec=%d 个 输出目录=%s', backendId, config.key || '(空)', config.classfilesPath || '(未设置)', execs?.length ?? 0, workdir)
  let plan
  try {
    plan = backend.report(values, env, { key: config.key, workdir, execs: execs ?? [], agentAddress: config.agentAddress })
  } catch (e) {
    console.error('[覆盖率] report 构建异常: %s', (e as Error)?.stack || e)
    return { success: false, error: '报告参数异常: ' + ((e as Error)?.message || String(e)) }
  }
  const run = await runSteps(plan.steps)
  if (!run.success) return { success: false, error: run.error }
  if (!fs.existsSync(plan.reportDir)) {
    console.error('[覆盖率] 报告目录未落盘: %s', plan.reportDir)
    return { success: false, error: '报告目录未生成: ' + plan.reportDir + '（请确认勾选了 HTML 报告，或查看指令输出排查）' }
  }
  console.info('[覆盖率] 报告生成完成: %s', plan.reportDir)
  const hasHtml = fs.existsSync(path.join(plan.reportDir, 'index.html'))
  if (hasHtml) injectReportI18n(plan.reportDir)
  return { success: true, reportDir: plan.reportDir, hasHtml }
}

// ===== 以下为 xiaoxiao-jacoco-cli 直接命令（Java 后端辅助能力） =====

/** 列出 agent 已采集的 key */
export async function listKeys(config: CoverageConfig) {
  const [host, port] = config.agentAddress.split(':')
  const res = await runJacocoCli(['keys', '--address', host || '127.0.0.1', '--port', port || '8899'])
  if (res.code !== 0) return { success: false, error: res.stderr || res.stdout }
  const keys = res.stdout.split('\n').map((l) => l.trim()).filter(Boolean)
  return { success: true, keys }
}

/** 插桩统计 */
export async function getStats(config: CoverageConfig, opts: { limit?: number }) {
  const [host, port] = config.agentAddress.split(':')
  const args = ['stats', '--address', host || '127.0.0.1', '--port', port || '8899']
  if (opts.limit) args.push('--limit', String(opts.limit))
  const res = await runJacocoCli(args)
  if (res.code !== 0) return { success: false, error: res.stderr || res.stdout }
  return { success: true, text: res.stdout }
}

/** 拉取被插桩类的原始字节码（classfiles 与运行实例不一致时使用） */
export async function dumpClasses(config: CoverageConfig, opts: { zip?: string }) {
  const [host, port] = config.agentAddress.split(':')
  const zip = opts.zip ?? path.join(coverageWorkDir(), `classfiles-${config.key}.zip`)
  const res = await runJacocoCli(['dumpclasses', '--address', host || '127.0.0.1', '--port', port || '8899', '--zip', zip])
  if (res.code !== 0) return { success: false, error: res.stderr || res.stdout }
  return { success: true, file: zip }
}

/** 远程设定全局 key（零改业务代码的进程外驱动） */
export async function setKey(config: CoverageConfig, opts: { key?: string; clear?: boolean }) {
  const [host, port] = config.agentAddress.split(':')
  const args = ['setkey', '--address', host || '127.0.0.1', '--port', port || '8899']
  if (opts.clear) args.push('--clear')
  else if (opts.key) args.push('--key', opts.key)
  const res = await runJacocoCli(args)
  if (res.code !== 0) return { success: false, error: res.stderr || res.stdout }
  return { success: true }
}

/** 合并多个 exec */
export async function mergeExecs(config: CoverageConfig, opts: { execs: string[]; destfile: string }) {
  const res = await runJacocoCli(['merge', ...opts.execs, '--destfile', opts.destfile])
  if (res.code !== 0) return { success: false, error: res.stderr || res.stdout }
  return { success: true, file: opts.destfile }
}

/** 导出报告：打包报告目录为 zip（GUI 启动 PATH 受限，按绝对路径候选探测 zip；任何异常显式回传，绝不裸崩主进程） */
export async function exportReport(opts: { reportDir: string }) {
  try {
    const out = `${opts.reportDir}.zip`
    // ⚠️ Node spawn 在 cwd 不存在时会把错误误报成 "spawn <bin> ENOENT"，必须先校验目录
    if (!fs.existsSync(opts.reportDir) || !fs.statSync(opts.reportDir).isDirectory()) {
      return { success: false, error: '报告目录不存在: ' + opts.reportDir + '（请先生成报告）' }
    }
    const candidates = ['/usr/bin/zip', '/usr/local/bin/zip', '/opt/homebrew/bin/zip', 'zip']
    let zipBin: string | undefined
    for (const c of candidates) {
      const ok = await new Promise<boolean>((resolve) => {
        const p = spawn(c, ['--version'], { windowsHide: true })
        p.on('error', () => resolve(false))
        p.on('close', (code) => resolve(code === 0))
      })
      if (ok) { zipBin = c; break }
    }
    if (!zipBin) return { success: false, error: '系统未找到 zip 命令，无法打包；可手动压缩报告目录: ' + opts.reportDir }
    const code = await new Promise<number>((resolve) => {
      const p = spawn(zipBin as string, ['-r', out, '.', '-i', '*'], { cwd: opts.reportDir, windowsHide: true })
      p.on('error', (e) => { console.error('[覆盖率] zip 打包异常: %s', (e as Error).message); resolve(-1) }) // ENOENT 多半是 cwd（报告目录）缺失
      p.on('close', (c) => resolve(c ?? -1))
    })
    if (code !== 0) return { success: false, error: `zip 打包失败（exit ${code}）` }
    return { success: true, filePath: out }
  } catch (e) {
    console.error('[覆盖率] 导出异常: %s', (e as Error)?.stack || e)
    return { success: false, error: '导出异常: ' + ((e as Error)?.message || String(e)) }
  }
}
