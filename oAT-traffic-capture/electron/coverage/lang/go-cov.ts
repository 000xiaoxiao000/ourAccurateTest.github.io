import path from 'path'
import fs from 'fs'
import os from 'os'
import { spawnSync } from 'child_process'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, CollectResult, ReportResult, RunStep, RuntimeEnv } from './types.js'
import type { CoverageParamSpec } from '../../types.js'
import { fmtStep } from './types.js'
import { asStr } from './util.js'

// go 可执行文件常见安装目录（GUI 启动 PATH 不含 homebrew / usr/local 时用）
const GO_CANDIDATE_DIRS = [
  '/opt/homebrew/bin',
  '/opt/homebrew/opt/go/libexec/bin',
  '/usr/local/bin',
  '/usr/bin',
  '/usr/local/go/bin',
  path.join(os.homedir(), 'go', 'bin'),
  path.join(process.env.GOROOT || '', 'bin')
].filter(Boolean) as string[]

/** PATH 里是否有 go（status 0 视为就绪） */
function goInPath(): boolean {
  try { return spawnSync('go', ['version'], { windowsHide: true, timeout: 5000 }).status === 0 } catch { return false }
}

/** 解析 go 可执行文件绝对路径：显式指定优先 → PATH → 常见安装目录 → 回退 'go' */
export function resolveGo(explicit?: string): string {
  const e = (explicit ?? '').trim()
  if (e && fs.existsSync(e)) return e
  if (goInPath()) return 'go'
  const exe = process.platform === 'win32' ? 'go.exe' : 'go'
  for (const d of GO_CANDIDATE_DIRS) {
    const p = path.join(d, exe)
    if (fs.existsSync(p)) return p
  }
  return 'go'
}

/** 探测 go 是否可用（含常见安装目录），供 detect 使用 */
export function hasGo(): boolean {
  if (goInPath()) return true
  const exe = process.platform === 'win32' ? 'go.exe' : 'go'
  for (const d of GO_CANDIDATE_DIRS) {
    if (fs.existsSync(path.join(d, exe))) return true
  }
  return false
}

/**
 * 参数 schema（JaCoCo 标准：参数全部挂在各指令下，前端才渲染；
 * 后端级 params 在 CoveragePanel 里并不渲染，这是 Go 此前「无参数可填」的根因）。
 * 采集与报告分两阶段，各自一套参数（与 JaCoCo 的 collect / report 两阶段对应）。
 */

// ===== ① 采集阶段：go build -cover + 运行，GOCOVERDIR 写入覆盖数据 =====
function collectParams(): CoverageParamSpec[] {
  return [
    { key: 'moduleDir', label: 'Go 模块根目录', type: 'path', pick: 'dir', default: '', required: true, help: '含 go.mod 的项目根目录；go build 与 go tool cover 都在模块内解析包路径与源码' },
    { key: 'covDir', label: 'covdata 输出目录 (GOCOVERDIR)', type: 'path', pick: 'dir', default: '', required: true, help: '程序运行期写入覆盖数据的目录；需与「生成报告」指令的 covdata 输入目录保持一致' },
    { key: 'binary', label: '产物二进制名', type: 'text', default: 'app', placeholder: 'app', help: 'go build -cover -o 输出的插桩二进制文件名（落到模块根目录下）' },
    { key: 'programArgs', label: '程序运行参数', type: 'text', default: '', placeholder: '如 main.go stat/stat.go 或 -', help: '传给插桩二进制的命令行参数；决定哪些代码被执行到（留空则按程序默认行为运行）' },
    { key: 'goBin', label: 'go 可执行文件', type: 'path', pick: 'file', default: '', help: '默认自动探测；GUI 环境 PATH 不含 homebrew 时手动选 /opt/homebrew/bin/go' }
  ]
}

// ===== ② 报告阶段：covdata textfmt + go tool cover =====
function reportParams(): CoverageParamSpec[] {
  return [
    { key: 'moduleDir', label: 'Go 模块根目录', type: 'path', pick: 'dir', default: '', required: true, help: '含 go.mod 的项目根目录；go tool cover 需在模块内解析包路径与源码（否则报 go.mod not found）' },
    { key: 'covDir', label: 'covdata 输入目录', type: 'path', pick: 'dir', default: '', required: true, help: 'GOCOVERDIR / covdata -i 输入目录（含覆盖数据的目录）' },
    { key: 'outputDir', label: '工作目录', type: 'path', pick: 'dir', default: '', help: 'cover.out / index.html 所在目录（默认当前目录）' },
    { key: 'outputFile', label: '中间 cover.out', type: 'text', default: 'cover.out', placeholder: 'cover.out', help: 'covdata textfmt 输出的 profile 文件名（写入到工作目录下）' },
    { key: 'goBin', label: 'go 可执行文件', type: 'path', pick: 'file', default: '', help: '默认自动探测；GUI 环境 PATH 不含 homebrew 时手动选 /opt/homebrew/bin/go' }
  ]
}

/** ① 采集步骤：build -cover + 运行（GOCOVERDIR 写入覆盖数据）。Go 的「采集」等价于 JaCoCo 的 dump .exec */
function buildCollectSteps(values: Record<string, string>): RunStep[] {
  const goBin = resolveGo(values.goBin)
  const moduleDir = asStr(values.moduleDir) || undefined
  const covDir = asStr(values.covDir)
  const bin = path.join(moduleDir || '.', asStr(values.binary, 'app'))
  const programArgs = asStr(values.programArgs).split(/\s+/).filter(Boolean)
  const buildStep: RunStep = {
    cmd: goBin,
    args: ['build', '-cover', `-o=${bin}`, '.'],
    cwd: moduleDir,
    description: 'go build -cover 编译插桩二进制'
  }
  // 运行插桩二进制；GOCOVERDIR 由 runtime/coverage 在退出时写入覆盖数据（env 经 RunStep 透传到子进程）
  const runStep: RunStep = {
    cmd: bin,
    args: programArgs,
    cwd: moduleDir,
    env: { GOCOVERDIR: covDir },
    description: '运行插桩二进制，GOCOVERDIR 写入覆盖数据'
  }
  return [buildStep, runStep]
}

/** ② 报告步骤：covdata textfmt 汇聚 + go tool cover 生成 HTML / -func 文本 */
function buildReportArgs(values: Record<string, string>): { textfmt: RunStep; cover: RunStep; outHtml: string; profile: string } {
  const goBin = resolveGo(values.goBin)
  const cwd = asStr(values.moduleDir) || undefined
  const covDir = asStr(values.covDir)
  const outputFile = path.resolve(asStr(values.outputDir, process.cwd()), asStr(values.outputFile, 'cover.out'))
  const textfmt: RunStep = {
    cmd: goBin,
    args: ['tool', 'covdata', 'textfmt', `-i=${covDir}`, `-o=${outputFile}`],
    cwd,
    description: 'go tool covdata textfmt 汇聚覆盖数据'
  }
  // 原生 HTML 必须叫 index.html，CoveragePanel 的 iframe 按 reportDir/index.html 加载
  const outHtml = path.join(path.dirname(outputFile), 'index.html')
  const cover: RunStep = {
    cmd: goBin,
    args: ['tool', 'cover', '-html', outputFile, `-o=${outHtml}`],
    cwd,
    description: 'go tool cover 生成 HTML 报告'
  }
  return { textfmt, cover, outHtml, profile: outputFile }
}

export const goCovBackend: CoverageBackend = {
  id: 'go-cov',
  name: 'Go',
  languages: ['Go'],
  description: 'Go runtime/coverage：① 编译插桩二进制并运行（GOCOVERDIR 写入 covdata）② go tool covdata / go tool cover 生成原生 HTML（对齐 JaCoCo 采集→报告两阶段）',
  status: 'builtin',
  detect() {
    return hasGo()
  },
  // 后端级 params 在 UI 不渲染，实际参数挂在各指令下（见 collectParams / reportParams）
  params: [],
  // ===== ① 采集：等价于 JaCoCo 的 collect（dump .exec）=====
  collect(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): CollectResult {
    const steps = buildCollectSteps(values)
    const covDir = asStr(values.covDir)
    return { steps, outputs: [covDir] }
  },
  // ===== ② 报告：等价于 JaCoCo 的 report（由 .exec 生成报告）=====
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const { textfmt, cover, outHtml } = buildReportArgs(values)
    return { steps: [textfmt, cover], reportDir: path.dirname(outHtml) }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    // JaCoCo 标准：编号阶段，采集 → 报告
    const lines: string[] = []
    lines.push('# ① 采集：go build -cover + 运行写入 covdata（等价于 JaCoCo 的 dump .exec）')
    for (const s of buildCollectSteps(values)) lines.push(fmtStep(s))
    const { textfmt, cover, profile } = buildReportArgs(values)
    lines.push('')
    lines.push('# ② 报告：covdata textfmt + go tool cover -html')
    lines.push(fmtStep(textfmt))
    lines.push(fmtStep(cover))
    lines.push('')
    lines.push('# （可选）按函数覆盖率：go tool cover -func ' + profile)
    return lines.join('\n')
  },
  // ===== Go 覆盖率全部指令（参数 UI 化，每条指令自带参数 schema；① 采集在前，② 报告在后） =====
  commands: [
    {
      id: 'collect',
      label: 'collect · 采集覆盖',
      description: 'go build -cover 编译插桩二进制并运行，GOCOVERDIR 写入覆盖数据（Go 的「采集」等价于 JaCoCo 的 dump .exec）',
      params: collectParams(),
      build(values) {
        return buildCollectSteps(values)
      }
    },
    {
      id: 'textfmt',
      label: 'covdata textfmt · 转文本',
      description: 'go tool covdata textfmt：把 GOCOVERDIR 二进制覆盖数据转成 profile 文本（-i/-o）',
      params: reportParams(),
      build(values) {
        const { textfmt } = buildReportArgs(values)
        return [textfmt]
      }
    },
    {
      id: 'cover',
      label: 'cover · HTML 报告',
      description: 'go tool covdata textfmt + go tool cover -html：先汇聚覆盖数据再生成原生 HTML 报告（自带 textfmt，可单独执行）',
      params: reportParams(),
      build(values) {
        const { textfmt, cover } = buildReportArgs(values)
        return [textfmt, cover]
      }
    },
    {
      id: 'percent',
      label: 'cover -func · 函数覆盖',
      description: 'go tool covdata textfmt + go tool cover -func：先汇聚覆盖数据再按函数列出覆盖率（含 total）',
      params: reportParams(),
      build(values) {
        const { textfmt, profile } = buildReportArgs(values)
        const goBin = resolveGo(values.goBin)
        const cwd = asStr(values.moduleDir) || undefined
        return [
          textfmt,
          { cmd: goBin, args: ['tool', 'cover', '-func', profile], cwd, description: '按函数列出覆盖率' }
        ]
      }
    }
  ]
}
