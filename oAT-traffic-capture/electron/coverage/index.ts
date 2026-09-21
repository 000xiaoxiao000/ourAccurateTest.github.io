import fs from 'fs'
import os from 'os'
import path from 'path'
import { spawn } from 'child_process'
import { app } from 'electron'
import type { CoverageConfig, CoverageExecInfo } from '../types.js'
import { fileSize, runJacocoCli } from './cliRunner.js'
import { resolveClassfiles } from './classfiles.js'
import { buildRuntimeEnv, getBackend } from './backends.js'
import { fmtStep } from './lang/types.js'
import type { RunStep } from './lang/types.js'

export { resolveClassfiles }

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
  switch (backendId) {
    case 'jacoco':
      return commandId === 'report' ? path.join(workdir, 'report') : undefined
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
  console.info('[覆盖率] 指令完成 %s.%s 产出=%d 个%s', backendId, commandId, outputs.length, reportDir ? ` 报告=${reportDir}` : '')
  return { success: true, text: run.stdout || run.stderr || undefined, outputs, reportDir, execs: execsInfo }
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
  console.info('[覆盖率] 报告生成完成: %s', plan.reportDir)
  return { success: true, reportDir: plan.reportDir }
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

/** 导出报告：打包报告目录为 zip（mac/linux 用 zip，缺失则 tar） */
export async function exportReport(opts: { reportDir: string }) {
  const out = `${opts.reportDir}.zip`
  const useZip = await new Promise<boolean>((resolve) => {
    const p = spawn('zip', ['--version'], { windowsHide: true })
    p.on('error', () => resolve(false))
    p.on('close', (c) => resolve(c === 0))
  })
  const cmd = useZip ? 'zip' : 'tar'
  const args = useZip
    ? ['-r', out, '.', '-i', '*']
    : ['-czf', out, '-C', opts.reportDir, '.']
  await new Promise<void>((resolve) => {
    const p = spawn(cmd, args, { cwd: opts.reportDir, windowsHide: true })
    p.on('close', () => resolve())
  })
  return { success: true, filePath: out }
}
