import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import { asBool, asStr, hasCommand } from './util.js'

function buildArgs(values: Record<string, string>): { capture: string[]; genhtml: string[] | null; reportDir: string } {
  const buildDir = asStr(values.buildDir)
  const outputDir = path.resolve(asStr(values.projectDir, process.cwd()), asStr(values.outputDir, 'coverage_html'))
  const infoFile = path.resolve(asStr(values.projectDir, process.cwd()), asStr(values.infoFile, 'cov.info'))
  const capture = ['--capture', '--directory', buildDir, '--output-file', infoFile]
  const genhtml = asBool(values.genHtml) ? [infoFile, '--output-directory', outputDir] : null
  return { capture, genhtml, reportDir: outputDir }
}

export const gcovLcovBackend: CoverageBackend = {
  id: 'gcov-lcov',
  name: 'C / C++',
  languages: ['C', 'C++'],
  description: 'gcov / lcov / genhtml：默认进程级归因；lcov 采集、genhtml 生成原生 HTML（C/C++ 不按请求级 Key 拆分）',
  status: 'builtin',
  detect() {
    return hasCommand('lcov') || hasCommand('gcov')
  },
  params: [
    { key: 'buildDir', label: '构建目录', type: 'path', pick: 'dir', default: '', required: true, help: 'lcov --directory（含 .gcno/.gcda）' },
    { key: 'projectDir', label: '工作目录（cwd）', type: 'path', pick: 'dir', default: '', help: 'info/html 路径基准（默认当前目录）' },
    { key: 'outputDir', label: 'HTML 目录', type: 'path', pick: 'dir', default: 'coverage_html', help: 'genhtml 输出目录' },
    { key: 'infoFile', label: 'info 文件', type: 'path', pick: 'file', default: 'cov.info', help: 'lcov 中间 info 文件' },
    { key: 'genHtml', label: '生成 HTML', type: 'boolean', default: 'true', help: '是否 genhtml 生成 HTML' }
  ],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const { capture, genhtml, reportDir } = buildArgs(values)
    const steps: RunStep[] = [{ cmd: 'lcov', args: capture, description: 'lcov --capture 采集覆盖数据' }]
    if (genhtml) {
      steps.push({ cmd: 'genhtml', args: genhtml, description: 'genhtml 生成 HTML 报告' })
    }
    return { steps, reportDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const { capture, genhtml } = buildArgs(values)
    const lines: string[] = []
    lines.push('# 生成报告（lcov / genhtml）')
    lines.push(`lcov ${capture.join(' ')}`)
    if (genhtml) lines.push(`genhtml ${genhtml.join(' ')}`)
    return lines.join('\n')
  },
  // ===== gcov / lcov / genhtml 全部指令（参数 UI 化；C/C++ 进程级归因） =====
  commands: [
    {
      id: 'capture',
      label: 'lcov capture · 采集',
      description: 'lcov --capture：从构建目录收集 gcov 覆盖数据生成 .info（--directory/--output-file）',
      params: [],
      build(values, env, ctx) {
        const { capture } = buildArgs(values)
        return [{ cmd: 'lcov', args: capture, description: 'lcov 采集覆盖数据' }]
      }
    },
    {
      id: 'summary',
      label: 'lcov summary · 摘要',
      description: 'lcov --summary：输出 .info 的总覆盖率摘要',
      params: [],
      build(values, env, ctx) {
        const infoFile = path.resolve(asStr(values.projectDir, process.cwd()), asStr(values.infoFile, 'cov.info'))
        return [{ cmd: 'lcov', args: ['--summary', infoFile], description: 'lcov 覆盖率摘要' }]
      }
    },
    {
      id: 'genhtml',
      label: 'genhtml · HTML 报告',
      description: 'genhtml：由 .info 生成原生 HTML 报告（--output-directory）',
      params: [],
      build(values, env, ctx) {
        const { genhtml } = buildArgs(values)
        if (!genhtml) return []
        return [{ cmd: 'genhtml', args: genhtml, description: 'genhtml 生成 HTML 报告' }]
      }
    }
  ]
}
