import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import type { CoverageParamSpec } from '../../types.js'
import { asBool, asStr, hasCommand } from './util.js'

// coverage.py 各指令共享的参数 schema（参数必须挂在指令下，前端才渲染——
// 后端级 params 在 CoveragePanel 里并不渲染，这是 Python 此前「填不了项目目录」的根因，对齐 go-cov 的做法）。
function sharedParams(): CoverageParamSpec[] {
  return [
    { key: 'projectDir', label: '项目目录（cwd）', type: 'path', pick: 'dir', default: '', required: true, help: '运行 coverage 的项目根目录（所有命令都以它为 cwd）' },
    { key: 'dataFile', label: '数据文件', type: 'path', pick: 'file', default: '.coverage', help: 'coverage 数据文件（默认 .coverage；非默认时才带 --data-file）' },
    { key: 'outputDir', label: 'HTML 目录', type: 'path', pick: 'dir', default: 'coverage_html', help: 'HTML 报告输出目录（-d）' },
    { key: 'sourceDir', label: '源码目录', type: 'path', pick: 'dir', default: '', help: '--source 源码根（可选，限定统计范围）' }
  ]
}

function buildHtmlArgs(values: Record<string, string>): string[] {
  const outputDir = asStr(values.outputDir, 'coverage_html')
  const dataFile = asStr(values.dataFile, '.coverage')
  const sourceDir = asStr(values.sourceDir)
  const args = ['html', '-d', outputDir]
  if (dataFile && dataFile !== '.coverage') args.push('--data-file', dataFile)
  if (sourceDir) args.push('--source', sourceDir)
  return args
}

function buildXmlArgs(values: Record<string, string>, outputFile: string): string[] {
  const dataFile = asStr(values.dataFile, '.coverage')
  const args = ['xml', '-o', outputFile]
  if (dataFile && dataFile !== '.coverage') args.push('--data-file', dataFile)
  return args
}

export const coveragePyBackend: CoverageBackend = {
  id: 'coverage-py',
  name: 'Python',
  languages: ['Python'],
  description: 'coverage.py：采集在服务运行期由 coverage run 完成，oAT 负责生成 HTML/XML/JSON 原生报告（对标 JaCoCo 的 dump+report）',
  status: 'builtin',
  detect() {
    return hasCommand('coverage')
  },
  // 后端级 params 在 UI 不渲染，实际参数挂在各指令下（见 sharedParams）。
  params: [],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const projectDir = asStr(values.projectDir) || process.cwd()
    const outputDir = path.resolve(projectDir, asStr(values.outputDir, 'coverage_html'))
    const fmt = asStr(values.format, 'html')
    const steps: RunStep[] = []
    if (fmt === 'html' || fmt === 'both') {
      steps.push({ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir, description: 'coverage html 生成 HTML 报告' })
    }
    if (fmt === 'xml' || fmt === 'both') {
      steps.push({ cmd: 'coverage', args: buildXmlArgs(values, path.join(outputDir, 'coverage.xml')), cwd: projectDir, description: 'coverage xml 生成 XML 报告' })
    }
    if (steps.length === 0) steps.push({ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir, description: 'coverage html 生成 HTML 报告' })
    return { steps, reportDir: outputDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const projectDir = asStr(values.projectDir) || '<projectDir>'
    const fmt = asStr(values.format, 'html')
    const lines: string[] = ['# 生成报告（coverage.py）', `cd ${projectDir}`]
    if (fmt === 'html' || fmt === 'both') lines.push(`coverage ${buildHtmlArgs(values).join(' ')}`)
    if (fmt === 'xml' || fmt === 'both') lines.push(`coverage ${buildXmlArgs(values, path.join(asStr(values.outputDir, 'coverage_html'), 'coverage.xml')).join(' ')}`)
    if (lines.length === 1) lines.push(`coverage ${buildHtmlArgs(values).join(' ')}`)
    return lines.join('\n')
  },
  // ===== coverage.py 全部指令（参数 UI 化，每条指令自带参数 schema，对标 JaCoCo 的子命令） =====
  commands: [
    {
      // ① 采集：对标 JaCoCo 的 dump——把被测服务放到 coverage 下运行，产出 .coverage
      id: 'run',
      label: 'run · 采集（启动被测）',
      description: 'coverage run：把被测程序放到 coverage 下运行并采集数据（等价于 JaCoCo 的 dump 采集步骤；长进程 Ctrl-C 退出才落盘 .coverage）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'outputDir' && p.key !== 'sourceDir'),
        { key: 'mode', label: '运行方式', type: 'select', default: 'module', options: ['module', 'script'], help: 'module=coverage run -m <目标>；script=coverage run <脚本>' },
        { key: 'target', label: '运行目标', type: 'text', default: 'uvicorn app:app', placeholder: '如 uvicorn app:app 或 main.py demo.txt', help: '被测入口：模块用 -m（如 uvicorn app:app），脚本用路径（如 main.py demo.txt）' },
        { key: 'args', label: '额外参数', type: 'text', default: '', placeholder: '如 --host 0.0.0.0 --port 8000', help: '传给被测程序的其他参数（空格分隔）' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const dataFile = asStr(values.dataFile, '.coverage')
        const df = dataFile && dataFile !== '.coverage' ? ['--data-file', dataFile] : []
        const target = asStr(values.target, 'uvicorn app:app')
        const extra = (asStr(values.args) || '').split(/\s+/).filter(Boolean)
        const mode = asStr(values.mode, 'module')
        const head = mode === 'script' ? ['run', ...df, ...target.split(/\s+/).filter(Boolean)] : ['run', ...df, '-m', ...target.split(/\s+/).filter(Boolean)]
        return [{ cmd: 'coverage', args: [...head, ...extra], cwd: projectDir, description: 'coverage run 采集覆盖数据' }]
      }
    },
    {
      // ② 生成报告 + ③ 查看：对标 JaCoCo 的 report（id 同为 report，报告卡据此显示）
      id: 'report',
      label: 'report · 生成报告',
      description: 'coverage html（可选 xml）：生成原生 HTML 报告（-d 输出目录），报告卡直接预览；可一并出 Cobertura 风格 XML',
      params: [
        ...sharedParams(),
        { key: 'format', label: '报告格式', type: 'select', default: 'html', options: ['html', 'xml', 'both'], help: 'html=仅 HTML；xml=仅 XML；both=两者都出' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const outputDir = path.resolve(projectDir, asStr(values.outputDir, 'coverage_html'))
        const fmt = asStr(values.format, 'html')
        const steps: RunStep[] = []
        if (fmt === 'html' || fmt === 'both') {
          steps.push({ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir, description: 'coverage html 生成 HTML 报告' })
        }
        if (fmt === 'xml' || fmt === 'both') {
          steps.push({ cmd: 'coverage', args: buildXmlArgs(values, path.join(outputDir, 'coverage.xml')), cwd: projectDir, description: 'coverage xml 生成 XML 报告' })
        }
        if (steps.length === 0) steps.push({ cmd: 'coverage', args: buildHtmlArgs(values), cwd: projectDir, description: 'coverage html 生成 HTML 报告' })
        return steps
      }
    },
    {
      id: 'xml',
      label: 'xml · XML 报告',
      description: 'coverage xml：生成 Cobertura 风格 XML（-o）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'outputDir' && p.key !== 'sourceDir'),
        { key: 'xmlOut', label: 'XML 输出', type: 'text', default: 'coverage.xml', help: '-o 输出文件' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const out = asStr(values.xmlOut) || 'coverage.xml'
        return [{ cmd: 'coverage', args: buildXmlArgs(values, out), cwd: projectDir, description: 'coverage xml 生成报告' }]
      }
    },
    {
      id: 'json',
      label: 'json · JSON 报告',
      description: 'coverage json：机器可读报告（-o）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'outputDir' && p.key !== 'sourceDir'),
        { key: 'jsonOut', label: 'JSON 输出', type: 'text', default: 'coverage.json', help: '-o 输出文件' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const out = asStr(values.jsonOut) || 'coverage.json'
        const dataFile = asStr(values.dataFile, '.coverage')
        const df = dataFile && dataFile !== '.coverage' ? ['--data-file', dataFile] : []
        return [{ cmd: 'coverage', args: ['json', '-o', out, ...df], cwd: projectDir, description: 'coverage json 生成报告' }]
      }
    },
    {
      id: 'summary',
      label: 'summary · 终端摘要',
      description: 'coverage report：终端文本摘要（--show-missing 显示未覆盖行）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'outputDir' && p.key !== 'sourceDir'),
        { key: 'showMissing', label: '显示未覆盖行', type: 'boolean', default: 'true', help: '--show-missing' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const dataFile = asStr(values.dataFile, '.coverage')
        const df = dataFile && dataFile !== '.coverage' ? ['--data-file', dataFile] : []
        const args = asBool(values.showMissing) ? ['report', '--show-missing', ...df] : ['report', ...df]
        return [{ cmd: 'coverage', args, cwd: projectDir, description: 'coverage report 终端摘要' }]
      }
    },
    {
      id: 'combine',
      label: 'combine · 合并数据',
      description: 'coverage combine：合并并行进程产生的多个 .coverage.* 数据文件（合并后默认写到 .coverage）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'outputDir' && p.key !== 'sourceDir')
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const dataFile = asStr(values.dataFile, '.coverage')
        const df = dataFile && dataFile !== '.coverage' ? ['--data-file', dataFile] : []
        return [{ cmd: 'coverage', args: ['combine', ...df], cwd: projectDir, description: 'coverage combine 合并数据' }]
      }
    }
  ]
}
