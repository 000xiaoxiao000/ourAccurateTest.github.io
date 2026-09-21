import fs from 'fs'
import path from 'path'
import type { CoverageBackend, CollectResult, CoverageContext, ReportResult, RunStep, RuntimeEnv } from './types.js'
import { asBool, asStr } from './util.js'

/** 多个路径（多模块）用 ';' 分隔；路径本身含 ';' 的概率极低，且 CLI 支持重复传参 */
export function splitPaths(v: string): string[] {
  return (v || '').split(';').map((s) => s.trim()).filter(Boolean)
}

function agentHostPort(agentAddress: string): { host: string; port: string } {
  const [h, p] = (agentAddress || '127.0.0.1:8899').split(':')
  return { host: h || '127.0.0.1', port: p || '8899' }
}

/** dump 输出目录对应指令：显式选了目录就落到该目录，否则用应用 execs 工作目录 */
function dumpDest(values: Record<string, string>, ctx: CoverageContext): string {
  const dir = asStr(values.dumpDir)
  const base = `coverage-${ctx.key || 'default'}.exec`
  return dir ? path.join(dir, base) : path.join(ctx.workdir || '.', base)
}

function buildCollectArgs(values: Record<string, string>, ctx: CoverageContext): string[] {
  const { host, port } = agentHostPort(ctx.agentAddress)
  const dest = dumpDest(values, ctx)
  const args = ['dump', '--address', host, '--port', port, '--destfile', dest]
  // key 为空时不传 --key（否则会带出空值参数），此时抓取 agent 当前全部数据
  if (ctx.key) args.push('--key', ctx.key)
  if (asBool(values.reset)) args.push('--reset')
  return args
}

/** 增量基线：入参可以是 JSON 文件，也可以是目录（自动解析目录下的 baseline JSON，优先顶层） */
function resolveBaseline(p: string): string | undefined {
  if (!p) return undefined
  try {
    const st = fs.statSync(p)
    if (st.isFile()) return p
    if (st.isDirectory()) {
      const top = fs.readdirSync(p).filter((n) => n.endsWith('.json')).sort()
      if (top.length) return path.join(p, top[0])
      const stack: string[] = [p]
      while (stack.length) {
        const dir = stack.shift() as string
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
          if (e.isDirectory()) stack.push(path.join(dir, e.name))
          else if (e.name.endsWith('.json')) return path.join(dir, e.name)
        }
      }
    }
  } catch { /* 路径无效则忽略基线 */ }
  return undefined
}

function buildReportArgs(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): string[] {
  // 报告输出目录统一转绝对路径（相对 cli 进程 cwd 解析会漂移，锚定到 workdir 才能和 inferReportDir 对齐）
  const raw = asStr(values.reportOutDir) || path.join(ctx.workdir || '.', 'report')
  const out = path.isAbsolute(raw) ? raw : path.resolve(ctx.workdir || process.cwd(), raw)
  const args = ['report']
  // .exec 输入：目录（--execdir 读该目录全部 .exec）或已抓取的 exec 列表，二者可同/异目录
  const execDir = asStr(values.execDir)
  if (execDir) args.push('--execdir', execDir)
  else args.push(...ctx.execs)
  // 多模块/多路径：以 ';' 分隔，CLI 支持 --classfiles / --sourcefiles 重复传
  for (const c of splitPaths(env.classfilesPath)) args.push('--classfiles', c)
  for (const s of splitPaths(asStr(values.sourcefilesPath))) args.push('--sourcefiles', s)
  if (asBool(values.html)) args.push('--html', out)
  if (asBool(values.xml)) args.push('--xml', path.join(out, 'jacoco.xml'))
  if (asBool(values.csv)) args.push('--csv', path.join(out, 'jacoco.csv'))
  // --perkey 可带 key：填了 key 就只出该 key 的报告（--perkey 1）；不填则是「每个 key 一份」
  const perKeyFilter = asStr(values.perKeyFilter)
  if (perKeyFilter) args.push('--perkey', perKeyFilter)
  else if (asBool(values.perKey)) args.push('--perkey')
  const baseline = resolveBaseline(asStr(values.baseline))
  if (baseline) args.push('--baseline', baseline)
  return args
}

function cliStep(env: RuntimeEnv, args: string[], description: string): RunStep {
  return { cmd: env.java, args: ['-jar', env.cliJar, ...args], description }
}

export const jacocoBackend: CoverageBackend = {
  id: 'jacoco',
  name: 'Java / Kotlin',
  languages: ['Java', 'Kotlin'],
  description: 'xiaoxiao-jacoco-cli：探针侧 headerkey 归因，零改业务代码；经 tcpserver 远程 dump .exec，生成 JaCoCo 原生报告',
  status: 'builtin',
  detect(env: RuntimeEnv) {
    return !!env.cliJar
  },
  params: [
    { key: 'reset', label: 'dump 后重置', type: 'boolean', default: 'true', help: '清空 agent 计数，便于时间窗口式 A/B 分离' },
    { key: 'html', label: 'HTML 报告', type: 'boolean', default: 'true', help: '--html 原生 HTML 报告' },
    { key: 'xml', label: 'XML 报告', type: 'boolean', default: 'false', help: '--xml（jacoco.xml）' },
    { key: 'csv', label: 'CSV 报告', type: 'boolean', default: 'false', help: '--csv（jacoco.csv）' },
    { key: 'perKey', label: '按 Key 拆分', type: 'boolean', default: 'false', help: '--perkey 每个 key 一份报告（可再用下方「仅生成该 Key」指定单个 key）' },
    { key: 'perKeyFilter', label: '仅生成该 Key（可选）', type: 'text', default: '', showWhen: { key: 'perKey', value: 'true' }, placeholder: '如 1', help: '--perkey <key> 只生成该 key 的报告；留空则不筛选（填了就隐含开启 perkey）' },
    { key: 'execDir', label: 'exec 目录', type: 'path', pick: 'dir', default: '', help: '--execdir 读该目录全部 .exec（可与基线同/异目录）' },
    { key: 'baseline', label: '基线目录', type: 'path', pick: 'dir', default: '', help: '--baseline 增量基线 JSON 所在目录，自动解析（可选）' },
    { key: 'sourcefilesPath', label: '源码目录', type: 'path', pick: 'dir', default: '', help: '--sourcefiles 需精确到包结构的父层（通常 src/main/java）；可用「自动推导」自动定位，多模块用 ; 分隔（可选）' }
  ],
  collect(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): CollectResult {
    const dest = dumpDest(values, ctx)
    return { steps: [cliStep(env, buildCollectArgs(values, ctx), '远程 dump .exec')], outputs: [dest] }
  },
  report(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): ReportResult {
    const out = asStr(values.reportOutDir) || path.join(ctx.workdir, 'report')
    return { steps: [cliStep(env, buildReportArgs(values, env, ctx), '生成 JaCoCo 原生报告')], reportDir: out }
  },
  preview(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): string {
    const cli = `${env.java} -jar ${(env.cliJar || 'xiaoxiao-jacoco-cli.jar').split('/').pop()}`
    const { host, port } = agentHostPort(ctx.agentAddress)
    const lines: string[] = []
    const collectArgs = buildCollectArgs(values, ctx)
    lines.push(`# ① 抓取 .exec（远程 dump，agent ${host}:${port}）`)
    lines.push(`${cli} ${collectArgs.join(' ')}`)
    const reportArgs = buildReportArgs(values, env, ctx)
    const execDir = asStr(values.execDir)
    const execPart = (!execDir && ctx.execs.length === 0) ? '<exec...>' : ''
    lines.push('')
    lines.push('# ② 生成报告（report）')
    lines.push([`${cli} report`, execPart, reportArgs.slice(1).join(' ')].filter(Boolean).join(' '))
    return lines.join('\n')
  },
  // ===== xiaoxiao-jacoco-cli 全部 12 条指令（与 cli 子命令一一对应，参数 UI 化） =====
  commands: [
    {
      id: 'dump',
      needs: ['agent'],
      label: 'dump · 抓取 .exec',
      description: '经 tcpserver 远程 dump agent 覆盖数据到 .exec（--address/--port/--destfile/--key/--reset）',
      params: [
        { key: 'reset', label: 'dump 后重置', type: 'boolean', default: 'true', help: '--reset 清空 agent 计数，便于时间窗口式 A/B 分离' },
        { key: 'dumpDir', label: 'exec 输出目录', type: 'path', pick: 'dir', default: '', help: '--destfile 输出目录（目录对应指令）；缺省 = <应用数据>/oat-coverage/execs/，选了目录则落到所选目录' }
      ],
      build(values, env, ctx) { return [cliStep(env, buildCollectArgs(values, ctx), '远程 dump .exec')] }
    },
    {
      id: 'report',
      needs: ['classfiles'],
      label: 'report · 生成报告',
      description: '由 .exec + classfiles 生成 JaCoCo 原生报告（--html/--xml/--csv/--perkey/--baseline）',
      params: [
        { key: 'html', label: 'HTML 报告', type: 'boolean', default: 'true', help: '--html 原生 HTML 报告' },
        { key: 'xml', label: 'XML 报告', type: 'boolean', default: 'false', help: '--xml（jacoco.xml）' },
        { key: 'csv', label: 'CSV 报告', type: 'boolean', default: 'false', help: '--csv（jacoco.csv）' },
        { key: 'perKey', label: '按 Key 拆分', type: 'boolean', default: 'false', help: '--perkey 每个 key 一份报告（可再用下方「仅生成该 Key」指定单个 key）' },
        { key: 'perKeyFilter', label: '仅生成该 Key（可选）', type: 'text', default: '', showWhen: { key: 'perKey', value: 'true' }, placeholder: '如 1', help: '--perkey <key> 只生成该 key 的报告；留空则不筛选（填了就隐含开启 perkey）' },
        { key: 'reportOutDir', label: '报告输出目录', type: 'path', pick: 'dir', default: '', required: true, help: '--html/--xml/--csv 输出目录；缺省 = <应用数据>/oat-coverage/execs/report' },
        { key: 'execDir', label: 'exec 目录', type: 'path', pick: 'dir', default: '', required: true, help: '--execdir 读该目录全部 .exec（不选则用已抓取的 exec）' },
        { key: 'baseline', label: '基线目录（可选）', type: 'path', pick: 'dir', default: '', help: '--baseline 增量基线 JSON 所在目录，自动解析' },
        { key: 'sourcefilesPath', label: '源码目录（可选）', type: 'path', pick: 'dir', default: '', help: '--sourcefiles 需精确到包结构的父层（通常 src/main/java）；多模块用 ; 分隔' }
      ],
      build(values, env, ctx) {
        return [cliStep(env, buildReportArgs(values, env, ctx), '生成 JaCoCo 原生报告')]
      }
    },
    {
      id: 'merge',
      label: 'merge · 合并 exec',
      description: '把多个 .exec 合并为一个（--destfile）',
      params: [
        { key: 'mergeDir', label: '输出目录', type: 'path', pick: 'dir', default: '', help: '选目录则合并结果落到 <该目录>/<输出文件名>' },
        { key: 'destfile', label: '输出文件名', type: 'text', default: 'jacoco-merged.exec', help: '--destfile 文件名或绝对路径；仅填文件名且未选目录时落到 <应用数据>/oat-coverage/execs/' }
      ],
      build(values, env, ctx) {
        const dest = asStr(values.destfile) || 'jacoco-merged.exec'
        const mergeDir = asStr(values.mergeDir)
        const destAbs = mergeDir ? path.join(mergeDir, path.basename(dest)) : (path.isAbsolute(dest) ? dest : path.join(ctx.workdir, dest))
        return [cliStep(env, ['merge', ...ctx.execs, '--destfile', destAbs], '合并 exec')]
      }
    },
    {
      id: 'keys',
      needs: ['agent'],
      label: 'keys · key 列表',
      description: '列出 agent 已采集的归因 key（--address/--port）',
      params: [],
      build(values, env, ctx) {
        const { host, port } = agentHostPort(ctx.agentAddress)
        return [cliStep(env, ['keys', '--address', host, '--port', port], '查询 agent key 列表')]
      }
    },
    {
      id: 'stats',
      needs: ['agent'],
      label: 'stats · 插桩统计',
      description: '查看 agent 插桩类/探针统计（--limit 前 N 类）',
      params: [
        { key: 'limit', label: '显示条数', type: 'number', default: '10', help: '--limit 前 N 个类的统计' }
      ],
      build(values, env, ctx) {
        const { host, port } = agentHostPort(ctx.agentAddress)
        const args = ['stats', '--address', host, '--port', port]
        const limit = asStr(values.limit)
        if (limit) args.push('--limit', limit)
        return [cliStep(env, args, '查询插桩统计')]
      }
    },
    {
      id: 'dumpclasses',
      needs: ['agent'],
      label: 'dumpclasses · 拉字节码',
      description: '从运行 agent 拉取被插桩类原始字节码 zip（classfiles 缺失/不一致时用）',
      params: [
        { key: 'zipDir', label: '输出目录', type: 'path', pick: 'dir', default: '', help: '选目录则 zip 落到 <该目录>/<输出 zip 文件名>' },
        { key: 'zip', label: '输出 zip 文件名', type: 'text', default: 'classfiles.zip', placeholder: 'classfiles.zip', help: '文件名或绝对路径；仅填文件名且未选目录时输出到 <应用数据>/oat-coverage/execs/' }
      ],
      build(values, env, ctx) {
        const { host, port } = agentHostPort(ctx.agentAddress)
        const zip = asStr(values.zip) || 'classfiles.zip'
        const zipDir = asStr(values.zipDir)
        const zipAbs = zipDir ? path.join(zipDir, path.basename(zip)) : (path.isAbsolute(zip) ? zip : path.join(ctx.workdir, zip))
        return [cliStep(env, ['dumpclasses', '--address', host, '--port', port, '--zip', zipAbs], '拉取被插桩类原始字节码')]
      }
    },
    {
      id: 'setkey',
      needs: ['agent'],
      label: 'setkey · 远程设 key',
      description: '进程外设定全局 CURRENT_KEY，零改业务代码驱动分离（--key/--clear）',
      params: [
        { key: 'setKeyValue', label: 'Key', type: 'text', default: '', placeholder: '留空则使用采集工作台的 Key', help: '--key 设定归属' },
        { key: 'clear', label: '清除 key', type: 'boolean', default: 'false', help: '--clear 清空当前 key' }
      ],
      build(values, env, ctx) {
        const { host, port } = agentHostPort(ctx.agentAddress)
        const args = ['setkey', '--address', host, '--port', port]
        if (asBool(values.clear)) args.push('--clear')
        else { const k = asStr(values.setKeyValue) || ctx.key; if (k) args.push('--key', k) }
        return [cliStep(env, args, '远程设定 key')]
      }
    },
    {
      id: 'instrument',
      needs: ['classfiles'],
      label: 'instrument · 离线插桩',
      description: '离线插桩 class 目录到指定输出（--destfile）',
      params: [
        { key: 'classesDir', label: 'class 目录', type: 'path', pick: 'dir', default: '', help: '待插桩的 classes 目录' },
        { key: 'destDir', label: '插桩输出目录', type: 'path', pick: 'dir', default: '', help: '--destfile 插桩后输出目录（选绝对目录则原样输出；缺省 = <应用数据>/oat-coverage/execs/instrumented）' }
      ],
      build(values, env, ctx) {
        const classes = asStr(values.classesDir) || env.classfilesPath
        const destDir = asStr(values.destDir)
        const dest = destDir ? (path.isAbsolute(destDir) ? destDir : path.join(ctx.workdir, destDir)) : path.join(ctx.workdir, 'instrumented')
        return [cliStep(env, ['instrument', classes, '--destfile', dest], '离线插桩')]
      }
    },
    {
      id: 'classinfo',
      needs: ['classfiles'],
      label: 'classinfo · 类信息',
      description: '列出 classfiles 中的类与方法签名（--classfiles）',
      params: [],
      build(values, env, ctx) {
        return [cliStep(env, ['classinfo', '--classfiles', env.classfilesPath], '查询类信息')]
      }
    },
    {
      id: 'execinfo',
      label: 'execinfo · exec 解析',
      description: '解析 .exec 文件的类/探针统计',
      params: [
        { key: 'execPath', label: 'exec 文件', type: 'path', pick: 'file', default: '', help: '不选则使用已抓取的第一个 exec' }
      ],
      build(values, env, ctx) {
        const exec = asStr(values.execPath) || ctx.execs[0] || ''
        return [cliStep(env, ['execinfo', exec], '解析 exec 统计')]
      }
    },
    {
      id: 'version',
      label: 'version · 版本',
      description: '显示 xiaoxiao-jacoco-cli 版本与兼容信息',
      params: [],
      build(values, env, ctx) { return [cliStep(env, ['version'], '查看版本')] }
    },
    {
      id: 'help',
      label: 'help · 帮助',
      description: '显示 cli 用法（可指定子命令）',
      params: [
        { key: 'cmd', label: '子命令', type: 'text', default: '', placeholder: '留空显示总览', help: '如 dump / report' }
      ],
      build(values, env, ctx) {
        const c = asStr(values.cmd)
        return [cliStep(env, c ? ['help', c] : ['help'], '查看帮助')]
      }
    }
  ]
}
