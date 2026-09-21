import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import { asStr, hasCommand } from './util.js'

function buildReportArgs(values: Record<string, string>): string[] {
  const reporter = asStr(values.reporter, 'html')
  const reportDir = asStr(values.reportDir, 'coverage')
  return ['nyc', 'report', '--reporter', reporter, '--report-dir', reportDir]
}

export const nycBackend: CoverageBackend = {
  id: 'nyc',
  name: 'JavaScript / TypeScript (Istanbul)',
  languages: ['JavaScript', 'TypeScript'],
  description: 'nyc / istanbul：采集在服务运行期由 nyc 插桩完成，oAT 负责生成原生 HTML 报告',
  status: 'builtin',
  detect() {
    return hasCommand('npx') || hasCommand('nyc')
  },
  params: [
    { key: 'projectDir', label: '项目目录（cwd）', type: 'path', pick: 'dir', default: '', required: true, help: 'nyc 读取 .nyc_output 的项目根目录' },
    { key: 'reporter', label: '报告格式', type: 'select', default: 'html', options: ['html', 'text', 'lcov', 'cobertura', 'json-summary'], help: '--reporter' },
    { key: 'reportDir', label: '报告输出目录', type: 'path', pick: 'dir', default: 'coverage', help: '--report-dir（相对项目目录）' }
  ],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const projectDir = asStr(values.projectDir)
    const reportDir = path.resolve(projectDir || process.cwd(), asStr(values.reportDir, 'coverage'))
    const step: RunStep = {
      cmd: 'npx',
      args: buildReportArgs(values),
      cwd: projectDir || undefined,
      description: 'nyc report 生成 HTML 报告'
    }
    return { steps: [step], reportDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const projectDir = asStr(values.projectDir)
    const lines: string[] = []
    lines.push('# 生成报告（nyc report）')
    const args = buildReportArgs(values)
    lines.push(`cd ${projectDir || '<projectDir>'} && npx ${args.join(' ')}`)
    return lines.join('\n')
  },
  // ===== Istanbul / nyc 全部指令（参数 UI 化） =====
  commands: [
    {
      id: 'report',
      label: 'report · 生成报告',
      description: 'nyc report：由 .nyc_output 生成原生报告（--reporter/--report-dir）',
      params: [
        { key: 'reporter', label: '报告格式', type: 'select', options: ['html', 'lcov', 'lcovonly', 'text', 'json', 'json-summary', 'clover', 'cobertura'], default: 'html', help: '--reporter 可多次；此处选主格式' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        return [{ cmd: 'npx', args: buildReportArgs(values), cwd: projectDir, description: 'nyc 生成报告' }]
      }
    },
    {
      id: 'instrument',
      label: 'instrument · 转译插桩',
      description: 'nyc instrument：对源码做 Istanbul 插桩输出到指定目录',
      params: [
        { key: 'inputDir', label: '源码目录', type: 'path', pick: 'dir', default: '', help: '待插桩的源码/构建产物目录' },
        { key: 'outputDir', label: '插桩输出', type: 'text', default: 'instrumented', help: '插桩产物输出目录' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const input = asStr(values.inputDir) || '.'
        const output = asStr(values.outputDir) || 'instrumented'
        return [{ cmd: 'npx', args: ['nyc', 'instrument', input, output], cwd: projectDir, description: 'nyc 转译插桩' }]
      }
    },
    {
      id: 'check-coverage',
      label: 'check-coverage · 阈值检查',
      description: 'nyc check-coverage：行/分支/函数/语句覆盖率是否达标',
      params: [
        { key: 'lines', label: '行覆盖 %', type: 'number', default: '80', help: '--lines' },
        { key: 'branches', label: '分支覆盖 %', type: 'number', default: '0', help: '--branches（0=不检查）' },
        { key: 'functions', label: '函数覆盖 %', type: 'number', default: '0', help: '--functions（0=不检查）' },
        { key: 'statements', label: '语句覆盖 %', type: 'number', default: '0', help: '--statements（0=不检查）' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const args = ['nyc', 'check-coverage']
        for (const k of ['lines', 'branches', 'functions', 'statements']) {
          const v = asStr(values[k])
          if (v && v !== '0') args.push(`--${k}`, v)
        }
        return [{ cmd: 'npx', args, cwd: projectDir, description: 'nyc 阈值检查' }]
      }
    }
  ]
}
