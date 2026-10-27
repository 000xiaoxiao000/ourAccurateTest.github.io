import path from 'path'
import fs from 'fs'
import os from 'os'
import type { CoverageBackend, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import type { CoverageParamSpec } from '../../types.js'
import { asBool, asStr, hasCommand } from './util.js'
import { fmtStep } from './types.js'

// lcov / genhtml 常见安装目录（GUI 启动 PATH 不含 homebrew / usr/local 时用，思路同 go-cov 的 go 探测）
const LCOV_CANDIDATE_DIRS = [
  '/opt/homebrew/bin', // Apple Silicon homebrew
  '/usr/local/bin', // Intel homebrew / 手动安装
  '/usr/bin',
  path.join(os.homedir(), '.local', 'bin'),
  process.env.LCOV_HOME ? path.join(process.env.LCOV_HOME, 'bin') : ''
].filter(Boolean) as string[]

/** 解析可执行文件绝对路径：显式指定优先 → PATH → 常见安装目录 → 回退裸名（由 PATH 兜底） */
function resolveBin(explicit: string | undefined, name: string): string {
  const e = (explicit ?? '').trim()
  if (e && fs.existsSync(e)) return e
  if (hasCommand(name)) return name
  for (const d of LCOV_CANDIDATE_DIRS) {
    const p = path.join(d, name)
    if (fs.existsSync(p)) return p
  }
  return e || name
}

/** lcov / genhtml 是否可用（含常见安装目录），供 detect 使用 */
function hasLcov(): boolean {
  if (hasCommand('lcov')) return true
  for (const d of LCOV_CANDIDATE_DIRS) {
    if (fs.existsSync(path.join(d, 'lcov'))) return true
  }
  return false
}
function hasGenhtml(): boolean {
  if (hasCommand('genhtml')) return true
  for (const d of LCOV_CANDIDATE_DIRS) {
    if (fs.existsSync(path.join(d, 'genhtml'))) return true
  }
  return false
}

/** 多路径（merge 的多 .info）用 ';' 分隔；路径本身含 ';' 的概率极低，且 CLI 支持重复传参 */
function splitPaths(v: string): string[] {
  return (v || '').split(';').map((s) => s.trim()).filter(Boolean)
}

// ===== 参数片段（对齐 JaCoCo 的「每条指令按需声明参数」，避免 capture 上也出现 HTML 目录这类无关字段） =====
function pBuildDir(required = true): CoverageParamSpec {
  return { key: 'buildDir', label: '构建目录', type: 'path', pick: 'dir', default: '.', required, help: 'lcov --directory：含 .gcno / .gcda 的构建输出目录（插桩编译产物所在处）' }
}
function pProjectDir(): CoverageParamSpec {
  return { key: 'projectDir', label: '工作目录（cwd）', type: 'path', pick: 'dir', default: '', help: 'info / html 路径基准（默认跟随运行上下文 workdir）；lcov 与 genhtml 在此目录下解析相对路径' }
}
function pInfoFile(): CoverageParamSpec {
  return { key: 'infoFile', label: 'info 文件', type: 'path', pick: 'file', default: '', help: 'lcov --output-file / --diff 的输入或输出 .info（留空按 Key 自动命名 cov[-<key>].info）' }
}
function pOutputDir(): CoverageParamSpec {
  return { key: 'outputDir', label: 'HTML 目录', type: 'path', pick: 'dir', default: '', help: 'genhtml --output-directory（留空按 Key 自动命名 coverage_html[-<key>]）' }
}
function pLcovBin(): CoverageParamSpec {
  return { key: 'lcovBin', label: 'lcov 可执行文件', type: 'path', pick: 'file', default: '', help: '默认自动探测；GUI 环境 PATH 不含 homebrew 时手动选 /opt/homebrew/bin/lcov' }
}
function pGenhtmlBin(): CoverageParamSpec {
  return { key: 'genhtmlBin', label: 'genhtml 可执行文件', type: 'path', pick: 'file', default: '', help: 'lcov 包自带，与 lcov 同目录；默认自动探测' }
}

/** 按 Key 归因：仿 JaCoCo 的 coverage-${key}.exec / --perkey，把 .info 与 HTML 目录按 key 区分 */
function infoFileFor(values: Record<string, string>, ctx: CoverageContext): string {
  const e = asStr(values.infoFile, '').trim()
  if (e) return e
  const k = (ctx.key || '').trim()
  return k ? `cov-${k}.info` : 'cov.info'
}
function outputDirFor(values: Record<string, string>, ctx: CoverageContext): string {
  const o = asStr(values.outputDir, '').trim()
  if (o) return o
  const k = (ctx.key || '').trim()
  return k ? `coverage_html-${k}` : 'coverage_html'
}
function projectDirFor(values: Record<string, string>, ctx: CoverageContext): string {
  const p = asStr(values.projectDir, '').trim()
  return p || ctx.workdir || process.cwd()
}

function captureStep(values: Record<string, string>, ctx: CoverageContext): RunStep {
  const lcov = resolveBin(values.lcovBin, 'lcov')
  const buildDir = asStr(values.buildDir, '.') || '.'
  const projectDir = projectDirFor(values, ctx)
  const infoFile = path.resolve(projectDir, infoFileFor(values, ctx))
  return { cmd: lcov, args: ['--capture', '--directory', buildDir, '--output-file', infoFile], cwd: projectDir, description: 'lcov --capture 采集 gcov 覆盖数据' }
}

export const gcovLcovBackend: CoverageBackend = {
  id: 'gcov-lcov',
  name: 'C / C++',
  languages: ['C', 'C++'],
  description: 'gcov / lcov / genhtml：支持按 Key 归因（进程级，需目标嵌入 __gcov_dump / __gcov_reset 并按 Key 落盘 .gcda）；lcov 采集、genhtml 原生 HTML、merge 合并、diff 增量、zerocounters 重置——覆盖 JaCoCo 同级能力',
  status: 'builtin',
  detect() {
    // 真正需要的是 lcov + genhtml（gcov 只是插桩产物的读取器，单独存在不足以就绪）
    return hasLcov() && hasGenhtml()
  },
  // 后端级 params 在 UI 不渲染，实际参数挂在各指令下（见各 command.params）
  params: [],
  report(values: Record<string, string>, _env: RuntimeEnv, ctx: CoverageContext): ReportResult {
    const lcov = resolveBin(values.lcovBin, 'lcov')
    const genhtml = resolveBin(values.genhtmlBin, 'genhtml')
    const buildDir = asStr(values.buildDir, '.') || '.'
    const projectDir = projectDirFor(values, ctx)
    const infoFile = path.resolve(projectDir, infoFileFor(values, ctx))
    const outputDir = path.resolve(projectDir, outputDirFor(values, ctx))
    const steps: RunStep[] = [captureStep(values, ctx)]
    // 高阶层 genHtml 缺省为 true（与 UI 指令的默认勾选一致）；仅显式 'false' 才跳过 HTML
    if (asStr(values.genHtml, 'true') !== 'false') {
      steps.push({ cmd: genhtml, args: [infoFile, '--output-directory', outputDir], cwd: projectDir, description: 'genhtml 生成原生 HTML 报告' })
    }
    return { steps, reportDir: outputDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, ctx: CoverageContext): string {
    const lcov = resolveBin(values.lcovBin, 'lcov')
    const genhtml = resolveBin(values.genhtmlBin, 'genhtml')
    const buildDir = asStr(values.buildDir, '.') || '.'
    const projectDir = projectDirFor(values, ctx)
    const infoFile = path.resolve(projectDir, infoFileFor(values, ctx))
    const outputDir = path.resolve(projectDir, outputDirFor(values, ctx))
    const lines: string[] = ['# 生成报告（lcov / genhtml）', fmtStep(captureStep(values, ctx))]
    if (asStr(values.genHtml, 'true') !== 'false') {
      lines.push(fmtStep({ cmd: genhtml, args: [infoFile, '--output-directory', outputDir], cwd: projectDir, description: 'genhtml 生成原生 HTML 报告' }))
    }
    return lines.join('\n')
  },
  // ===== gcov / lcov / genhtml 全部指令（参数 UI 化；对齐 JaCoCo 的 12 条子命令能力） =====
  commands: [
    {
      id: 'capture',
      label: 'capture · 采集',
      description: 'lcov --capture：从构建目录收集 gcov 覆盖数据生成 .info（--directory/--output-file）。按 Key 自动命名 cov[-<key>].info',
      params: [pBuildDir(), pProjectDir(), pInfoFile(), pLcovBin()],
      build(values, _env, ctx) {
        return [captureStep(values, ctx)]
      }
    },
    {
      id: 'summary',
      label: 'summary · 摘要',
      description: 'lcov --summary：输出 .info 的总覆盖率摘要（行 / 函数 / 分支覆盖率）',
      params: [pProjectDir(), pInfoFile(), pLcovBin()],
      build(values, _env, ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        const projectDir = projectDirFor(values, ctx)
        const infoFile = path.resolve(projectDir, infoFileFor(values, ctx))
        return [{ cmd: lcov, args: ['--summary', infoFile], cwd: projectDir, description: 'lcov 覆盖率摘要' }]
      }
    },
    {
      id: 'report',
      label: 'report · HTML 报告',
      description: 'genhtml：由 .info 生成原生 HTML 报告（--output-directory）。若 .info 不存在则先自动 lcov --capture；入口页 index.html 由 UI iframe 加载',
      params: [pProjectDir(), pOutputDir(), pInfoFile(), pBuildDir(false), pGenhtmlBin(), pLcovBin()],
      build(values, _env, ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        const genhtml = resolveBin(values.genhtmlBin, 'genhtml')
        const projectDir = projectDirFor(values, ctx)
        const infoFile = path.resolve(projectDir, infoFileFor(values, ctx))
        const outputDir = path.resolve(projectDir, outputDirFor(values, ctx))
        const steps: RunStep[] = []
        // 与 report() 高阶层一致：.info 缺失时自动采集，一条指令出完整报告
        if (!fs.existsSync(infoFile)) {
          steps.push({ cmd: lcov, args: ['--capture', '--directory', asStr(values.buildDir, '.') || '.', '--output-file', infoFile], cwd: projectDir, description: 'lcov --capture（.info 缺失时自动采集）' })
        }
        steps.push({ cmd: genhtml, args: [infoFile, '--output-directory', outputDir], cwd: projectDir, description: 'genhtml 生成原生 HTML 报告' })
        return steps
      }
    },
    {
      id: 'merge',
      label: 'merge · 合并 info',
      description: 'lcov -a：把多个 .info 合并为一个（等价 JaCoCo merge）。多文件用「;」分隔，可含路径；输出默认 merged.info',
      params: [
        { key: 'infoFiles', label: '待合并 .info（; 分隔）', type: 'text', default: '', required: true, placeholder: 'a.info;b.info;c.info', help: '多个 .info 路径，用「;」分隔；相对路径基于工作目录解析' },
        { key: 'mergedFile', label: '合并输出文件', type: 'text', default: 'merged.info', help: '合并结果文件名或绝对路径；仅填文件名时落到工作目录' },
        pLcovBin()
      ],
      build(values, _env, ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        const projectDir = projectDirFor(values, ctx)
        const files = splitPaths(asStr(values.infoFiles, '')).map((f) => path.resolve(projectDir, f))
        const merged = path.resolve(projectDir, asStr(values.mergedFile, 'merged.info') || 'merged.info')
        const args: string[] = []
        for (const f of files) args.push('-a', f)
        args.push('-o', merged)
        return [{ cmd: lcov, args, cwd: projectDir, description: 'lcov -a 合并多个 .info' }]
      }
    },
    {
      id: 'incremental',
      label: 'incremental · 增量报告',
      description: 'lcov --diff：以基线 .info 为参照，只保留当前相对基线新增的覆盖（等价 JaCoCo incremental）；再 genhtml 出增量 HTML',
      params: [
        { key: 'currentInfo', label: '当前 .info', type: 'path', pick: 'file', default: '', required: true, help: '本次采集的 .info（留空按 Key 自动命名 cov[-<key>].info）' },
        { key: 'baselineInfo', label: '基线 .info', type: 'path', pick: 'file', default: '', required: true, help: '基线（旧版本）覆盖 .info，作为对照' },
        pOutputDir(),
        pGenhtmlBin(),
        pLcovBin()
      ],
      build(values, _env, ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        const genhtml = resolveBin(values.genhtmlBin, 'genhtml')
        const projectDir = projectDirFor(values, ctx)
        const current = path.resolve(projectDir, asStr(values.currentInfo, '').trim() || infoFileFor(values, ctx))
        const baseline = path.resolve(projectDir, asStr(values.baselineInfo, '').trim())
        const diff = path.resolve(projectDir, 'diff.info')
        const outputDir = path.resolve(projectDir, outputDirFor(values, ctx))
        return [
          { cmd: lcov, args: ['--diff', current, baseline, '--output-file', diff], cwd: projectDir, description: 'lcov --diff 基线对比' },
          { cmd: genhtml, args: [diff, '--output-directory', outputDir], cwd: projectDir, description: 'genhtml 增量报告' }
        ]
      }
    },
    {
      id: 'zero',
      label: 'zerocounters · 重置',
      description: 'lcov --zerocounters：清空构建目录下的 .gcda 计数器（等价 JaCoCo dump --reset），便于时间窗口式 A/B 分离',
      params: [pBuildDir(), pLcovBin()],
      build(values, _env, ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        const buildDir = asStr(values.buildDir, '.') || '.'
        return [{ cmd: lcov, args: ['--zerocounters', '--directory', buildDir], cwd: projectDirFor(values, ctx), description: 'lcov --zerocounters 清零计数器' }]
      }
    },
    {
      id: 'version',
      label: 'version · 版本',
      description: 'lcov --version：查看 lcov / genhtml 版本与能力信息',
      params: [pLcovBin()],
      build(values, _env, _ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        return [{ cmd: lcov, args: ['--version'], description: '查看 lcov 版本' }]
      }
    },
    {
      id: 'help',
      label: 'help · 帮助',
      description: 'lcov --help：显示 lcov 用法与全部选项',
      params: [pLcovBin()],
      build(values, _env, _ctx) {
        const lcov = resolveBin(values.lcovBin, 'lcov')
        return [{ cmd: lcov, args: ['--help'], description: '查看 lcov 帮助' }]
      }
    }
  ]
}
