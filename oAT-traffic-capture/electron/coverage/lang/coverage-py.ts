import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import { asBool, asStr, hasCommand } from './util.js'

function buildHtmlArgs(values: Record<string, string>): string[] {
  const outputDir = asStr(values.outputDir, 'coverage_html')
  const dataFile = asStr(values.dataFile, '.coverage')
  const sourceDir = asStr(values.sourceDir)
  const args = ['html', '-d', outputDir]
  if (dataFile && dataFile !== '.coverage') args.push('--data-file', dataFile)
  if (sourceDir) args.push('--source', sourceDir)
  return args
}

function buildXmlArgs(values: Record<string, string>, outputDir: string): string[] {
  const dataFile = asStr(values.dataFile, '.coverage')
  const args = ['xml', '-o', path.join(outputDir, 'coverage.xml')]
  if (dataFile && dataFile !== '.coverage') args.push('--data-file', dataFile)
  return args
}

export const coveragePyBackend: CoverageBackend = {
  id: 'coverage-py',
  name: 'Python',
  languages: ['Python'],
  description: 'coverage.py：采集在服务运行期由 coverage 完成，oAT 负责生成 HTML/XML 报告',
  status: 'builtin',
  detect() {
    return hasCommand('coverage')
  },
  params: [
    { key: 'projectDir', label: '项目目录（cwd）', type: 'path', pick: 'dir', default: '', required: true, help: '运行 coverage 的项目根目录' },
    { key: 'dataFile', label: '数据文件', type: 'path', pick: 'file', default: '.coverage', help: 'coverage 数据文件（默认 .coverage）' },
    { key: 'outputDir', label: 'HTML 目录', type: 'path', pick: 'dir', default: 'coverage_html', help: 'HTML 报告目录' },
    { key: 'format', label: '报告格式', type: 'select', default: 'html', options: ['html', 'xml', 'both'], help: 'html / xml / both' },
    { key: 'sourceDir', label: '源码目录', type: 'path', pick: 'dir', default: '', help: '--source 源码根（可选）' }
  ],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const projectDir = asStr(values.projectDir)
    const outputDir = path.resolve(projectDir || process.cwd(), asStr(values.outputDir, 'coverage_html'))
    const fmt = asStr(values.format, 'html')
    const steps: RunStep[] = []
    steps.push({ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir || undefined, description: 'coverage html 生成 HTML 报告' })
    if (fmt === 'xml' || fmt === 'both') {
      steps.push({ cmd: 'coverage', args: buildXmlArgs(values, outputDir), cwd: projectDir || undefined, description: 'coverage xml 生成 XML 报告' })
    }
    return { steps, reportDir: outputDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const projectDir = asStr(values.projectDir)
    const lines: string[] = []
    lines.push('# 生成报告（coverage.py）')
    lines.push(`cd ${projectDir || '<projectDir>'} && coverage ${buildHtmlArgs(values).join(' ')}`)
    const fmt = asStr(values.format, 'html')
    if (fmt === 'xml' || fmt === 'both') {
      lines.push(`coverage ${buildXmlArgs(values, asStr(values.outputDir, 'coverage_html')).join(' ')}`)
    }
    return lines.join('\n')
  },
  // ===== coverage.py 全部指令（参数 UI 化） =====
  commands: [
    {
      id: 'html',
      label: 'html · HTML 报告',
      description: 'coverage html：生成原生 HTML 报告（-d 输出目录）',
      params: [],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        return [{ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir, description: 'coverage html 生成报告' }]
      }
    },
    {
      id: 'xml',
      label: 'xml · XML 报告',
      description: 'coverage xml：生成 Cobertura 风格 XML（-o）',
      params: [
        { key: 'xmlOut', label: 'XML 输出', type: 'text', default: 'coverage.xml', help: '-o 输出文件' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const out = asStr(values.xmlOut) || 'coverage.xml'
        return [{ cmd: 'coverage', args: ['xml', '-o', out], cwd: projectDir, description: 'coverage xml 生成报告' }]
      }
    },
    {
      id: 'json',
      label: 'json · JSON 报告',
      description: 'coverage json：机器可读报告（-o）',
      params: [
        { key: 'jsonOut', label: 'JSON 输出', type: 'text', default: 'coverage.json', help: '-o 输出文件' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const out = asStr(values.jsonOut) || 'coverage.json'
        return [{ cmd: 'coverage', args: ['json', '-o', out], cwd: projectDir, description: 'coverage json 生成报告' }]
      }
    },
    {
      id: 'report',
      label: 'report · 终端摘要',
      description: 'coverage report：终端文本摘要（--show-missing 显示未覆盖行）',
      params: [
        { key: 'showMissing', label: '显示未覆盖行', type: 'boolean', default: 'true', help: '--show-missing' }
      ],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const args = asBool(values.showMissing) ? ['report', '--show-missing'] : ['report']
        return [{ cmd: 'coverage', args, cwd: projectDir, description: 'coverage report 终端摘要' }]
      }
    },
    {
      id: 'combine',
      label: 'combine · 合并数据',
      description: 'coverage combine：合并并行进程产生的多个 .coverage-* 数据文件',
      params: [],
      build(values, env, ctx) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        return [{ cmd: 'coverage', args: ['combine'], cwd: projectDir, description: 'coverage combine 合并数据' }]
      }
    }
  ]
}
