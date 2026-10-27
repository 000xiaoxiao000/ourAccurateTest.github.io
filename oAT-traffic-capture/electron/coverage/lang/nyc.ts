import path from 'path'
import type { CoverageBackend, CoverageCommandDef, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import type { CoverageParamSpec } from '../../types.js'
import { asStr, hasCommand } from './util.js'

/**
 * nyc 全部指令共享的参数 schema（参数必须挂在指令下，前端才渲染——
 * 后端级 params 在 CoveragePanel 里并不渲染，这是此前「填不了项目目录」的根因，对齐 go-cov / coverage-py 的做法）。
 */
function sharedParams(): CoverageParamSpec[] {
  return [
    { key: 'projectDir', label: '项目目录（cwd）', type: 'path', pick: 'dir', default: '', required: true, help: 'nyc 读取 .nyc_output 的项目根目录，也是各指令的 cwd；不选则退到进程 cwd，通常找不到 .nyc_output' },
    { key: 'reportDir', label: '报告输出目录', type: 'path', pick: 'dir', default: 'coverage', help: '--report-dir（相对项目目录；默认 coverage，HTML 入口为 index.html）' }
  ]
}

function buildReportArgs(values: Record<string, string>): string[] {
  const reporter = asStr(values.reporter, 'html')
  const reportDir = asStr(values.reportDir, 'coverage')
  const args = ['nyc', 'report', '--reporter', reporter, '--report-dir', reportDir]
  // 对标 JaCoCo report 的 --execdir：指定 raw 覆盖数据目录（默认 .nyc_output），合并后的数据放这里再 report
  const temp = asStr(values.tempDirectory)
  if (temp) args.push('--temp-directory', temp)
  return args
}

export const nycBackend: CoverageBackend = {
  id: 'nyc',
  name: 'JavaScript / TypeScript (Istanbul)',
  languages: ['JavaScript', 'TypeScript'],
  description: 'nyc / istanbul：采集在服务运行期由 nyc 插桩完成，oAT 负责生成原生 HTML 报告（对标 JaCoCo 的 dump+report+merge）',
  status: 'builtin',
  detect() {
    return hasCommand('npx') || hasCommand('nyc')
  },
  // 后端级 params 在 UI 不渲染，实际参数挂在各指令下（见 sharedParams）。
  params: [],
  report(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): ReportResult {
    const projectDir = asStr(values.projectDir) || process.cwd()
    const reportDir = path.resolve(projectDir, asStr(values.reportDir, 'coverage'))
    const step: RunStep = {
      cmd: 'npx',
      args: buildReportArgs(values),
      cwd: projectDir,
      description: 'nyc report 生成 HTML 报告'
    }
    return { steps: [step], reportDir }
  },
  preview(values: Record<string, string>, _env: RuntimeEnv, _ctx: CoverageContext): string {
    const projectDir = asStr(values.projectDir) || '<projectDir>'
    const lines: string[] = ['# 生成报告（nyc report）', `cd ${projectDir}`, `npx ${buildReportArgs(values).join(' ')}`]
    return lines.join('\n')
  },
  // ===== Istanbul / nyc 全部指令（参数 UI 化，每条指令自带参数 schema，对标 JaCoCo 的子命令） =====
  commands: [
    {
      // ① 采集：对标 JaCoCo 的 dump——把被测服务放到 nyc 下运行，退出时落盘 .nyc_output
      id: 'run',
      label: 'run · 采集（启动被测）',
      description: 'nyc 运行：把被测程序放到 nyc 下运行并采集数据（等价于 JaCoCo 的 dump 采集步骤；进程正常退出才落盘 .nyc_output）；--include/--exclude 限定插桩范围',
      params: [
        ...sharedParams().filter((p) => p.key !== 'reportDir'),
        { key: 'target', label: '运行目标', type: 'text', default: 'node server.js', placeholder: '如 node dist/server.js 或 npm start', help: '被测入口：nyc 在其外包裹插桩；脚本用 node <入口>，也可直接传 npm start 等' },
        { key: 'args', label: '额外参数', type: 'text', default: '', placeholder: '如 --port 3000', help: '传给被测程序的其他参数（空格分隔）' },
        { key: 'include', label: '仅包含（可选）', type: 'text', default: '', placeholder: '如 src/**/*.js', help: 'nyc --include：限定插桩/统计范围（glob），对标 JaCoCo 的 sourcefiles 范围' },
        { key: 'exclude', label: '排除（可选）', type: 'text', default: '', placeholder: '如 **/*.test.js', help: 'nyc --exclude：排除范围（glob）' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const target = asStr(values.target, 'node server.js')
        const extra = (asStr(values.args) || '').split(/\s+/).filter(Boolean)
        const args: string[] = ['nyc']
        const include = asStr(values.include)
        if (include) args.push('--include', include)
        const exclude = asStr(values.exclude)
        if (exclude) args.push('--exclude', exclude)
        args.push(...target.split(/\s+/).filter(Boolean), ...extra)
        return [{ cmd: 'npx', args, cwd: projectDir, description: 'nyc 运行并采集覆盖数据' }]
      }
    },
    {
      // ② 生成报告 + ③ 查看：对标 JaCoCo 的 report（id 同为 report，报告卡据此显示）
      id: 'report',
      label: 'report · 生成报告',
      description: 'nyc report：由 .nyc_output（或 --temp-directory 指定目录）生成原生 HTML 报告（--reporter/--report-dir），报告卡直接预览',
      params: [
        ...sharedParams(),
        { key: 'reporter', label: '报告格式', type: 'select', options: ['html', 'lcov', 'lcovonly', 'text', 'text-summary', 'json', 'json-summary', 'clover', 'cobertura'], default: 'html', help: '--reporter 主格式（报告卡按 index.html 加载，选非 html 时不会有网页预览）' },
        { key: 'tempDirectory', label: '数据目录（可选）', type: 'path', pick: 'dir', default: '', help: 'nyc --temp-directory：指定 raw 覆盖数据目录（默认 .nyc_output）；merge 合并后的数据放这里再 report，对标 JaCoCo 的 --execdir' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        return [{ cmd: 'npx', args: buildReportArgs(values), cwd: projectDir, description: 'nyc 生成报告' }]
      }
    },
    {
      id: 'merge',
      label: 'merge · 合并覆盖率',
      description: 'nyc merge：把多个 .nyc_output / 覆盖数据合并为一个（对标 JaCoCo 的 merge；解决多进程/多次运行分散的覆盖数据）；合并结果交 report --temp-directory 出 HTML',
      params: [
        ...sharedParams().filter((p) => p.key !== 'reportDir'),
        { key: 'inputs', label: '输入目录/文件', type: 'text', default: '.nyc_output', placeholder: '如 .nyc_output 或 .nyc_output;coverage-2', help: '待合并的覆盖数据：目录（含 raw .json）或 .json 文件，多个用 ; 分隔；nyc merge 逐个读取并最终落到 output' },
        { key: 'output', label: '合并输出', type: 'text', default: 'coverage-final.json', placeholder: 'coverage-final.json 或 merged/', help: '合并结果：.json 写单文件（再交 nyc report --temp-directory 出 HTML）；目录则落该目录' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const inputs = asStr(values.inputs, '.nyc_output').split(';').map((s) => s.trim()).filter(Boolean)
        const output = asStr(values.output) || 'coverage-final.json'
        return [{ cmd: 'npx', args: ['nyc', 'merge', ...inputs, output], cwd: projectDir, description: 'nyc 合并覆盖率数据' }]
      }
    },
    {
      id: 'instrument',
      label: 'instrument · 转译插桩',
      description: 'nyc instrument：对源码做 Istanbul 离线插桩输出到指定目录',
      params: [
        ...sharedParams().filter((p) => p.key !== 'reportDir'),
        { key: 'inputDir', label: '源码目录', type: 'path', pick: 'dir', default: '', help: '待插桩的源码/构建产物目录' },
        { key: 'outputDir', label: '插桩输出目录', type: 'path', pick: 'dir', default: '', help: '插桩产物输出目录（绝对目录原样输出；相对名基于项目目录）' }
      ],
      build(values) {
        const projectDir = asStr(values.projectDir) || process.cwd()
        const input = asStr(values.inputDir) || '.'
        const output = asStr(values.outputDir) || 'instrumented'
        return [{ cmd: 'npx', args: ['nyc', 'instrument', input, output], cwd: projectDir, description: 'nyc 转译插桩' }]
      }
    },
    {
      id: 'check-coverage',
      label: 'check-coverage · 阈值检查',
      description: 'nyc check-coverage：行/分支/函数/语句覆盖率是否达标（终端输出，可设阈值）',
      params: [
        ...sharedParams().filter((p) => p.key !== 'reportDir'),
        { key: 'lines', label: '行覆盖 %', type: 'number', default: '80', help: '--lines' },
        { key: 'branches', label: '分支覆盖 %', type: 'number', default: '0', help: '--branches（0=不检查）' },
        { key: 'functions', label: '函数覆盖 %', type: 'number', default: '0', help: '--functions（0=不检查）' },
        { key: 'statements', label: '语句覆盖 %', type: 'number', default: '0', help: '--statements（0=不检查）' }
      ],
      build(values) {
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
