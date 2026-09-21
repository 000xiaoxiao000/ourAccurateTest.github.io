import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import { asStr, hasCommand } from './util.js'

function buildArgs(values: Record<string, string>): { textfmt: string[]; cover: string[]; outHtml: string } {
  const covDir = asStr(values.covDir)
  const outputFile = path.resolve(asStr(values.outputDir, process.cwd()), asStr(values.outputFile, 'cover.out'))
  const textfmt = ['tool', 'covdata', 'textfmt', `-i=${covDir}`, `-o=${outputFile}`]
  // 原生 HTML 必须叫 index.html，CoveragePanel 的 iframe 按 reportDir/index.html 加载
  const outHtml = path.join(path.dirname(outputFile), 'index.html')
  const cover = ['tool', 'cover', '-html', outputFile, `-o=${outHtml}`]
  return { textfmt, cover, outHtml }
}

export const goCovBackend: CoverageBackend = {
  id: 'go-cov',
  name: 'Go',
  languages: ['Go'],
  description: 'Go runtime/coverage：GOCOVERDIR 汇总覆盖数据，oAT 用 go tool covdata / go tool cover 生成原生 HTML',
  status: 'builtin',
  detect() {
    return hasCommand('go')
  },
  params: [
    { key: 'covDir', label: 'covdata 输入目录', type: 'path', pick: 'dir', default: '', required: true, help: 'GOCOVERDIR 或 covdata -i 输入目录' },
    { key: 'outputFile', label: '中间 cover.out', type: 'path', pick: 'file', default: 'cover.out', help: 'covdata textfmt 输出路径' },
    { key: 'outputDir', label: '工作目录', type: 'path', pick: 'dir', default: '', help: 'cover.out 所在目录（默认当前目录）' }
  ],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const { textfmt, cover, outHtml } = buildArgs(values)
    const steps: RunStep[] = [
      { cmd: 'go', args: textfmt, description: 'go tool covdata textfmt 汇聚覆盖数据' },
      { cmd: 'go', args: cover, description: 'go tool cover 生成 HTML 报告' }
    ]
    return { steps, reportDir: path.dirname(outHtml) }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const { textfmt, cover } = buildArgs(values)
    const lines: string[] = []
    lines.push('# 生成报告（go tool covdata / cover）')
    lines.push(`go ${textfmt.join(' ')}`)
    lines.push(`go ${cover.join(' ')}`)
    return lines.join('\n')
  },
  // ===== Go 覆盖率全部指令（参数 UI 化） =====
  commands: [
    {
      id: 'textfmt',
      label: 'covdata textfmt · 转文本',
      description: 'go tool covdata textfmt：把 GOCOVERDIR 二进制覆盖数据转成 profile 文本（-i/-o）',
      params: [],
      build(values, env, ctx) {
        const { textfmt } = buildArgs(values)
        return [{ cmd: 'go', args: textfmt, description: 'covdata 转文本 profile' }]
      }
    },
    {
      id: 'cover',
      label: 'cover · HTML 报告',
      description: 'go tool cover -html：由 profile 生成原生 HTML 报告',
      params: [],
      build(values, env, ctx) {
        const { cover } = buildArgs(values)
        return [{ cmd: 'go', args: cover, description: 'cover 生成 HTML 报告' }]
      }
    },
    {
      id: 'percent',
      label: 'cover -func · 函数覆盖',
      description: 'go tool cover -func：按函数列出覆盖率（含 total）',
      params: [],
      build(values, env, ctx) {
        const { textfmt } = buildArgs(values)
        const profile = textfmt[textfmt.indexOf('-o') + 1] || 'cover.out'
        return [{ cmd: 'go', args: ['tool', 'cover', '-func', profile], description: '按函数列出覆盖率' }]
      }
    }
  ]
}
