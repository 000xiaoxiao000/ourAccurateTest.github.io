import type { CoverageParamSpec } from '../../types.js'

/** 运行时环境：主进程算好后传给各后端插件（含随包 JRE 优先的 java、jar 路径、classfiles 本地路径） */
export interface RuntimeEnv {
  java: string
  cliJar: string
  agentJar: string
  resourcesRoot: string
  classfilesPath: string
}

/** 一个可执行步骤（spawn），既用于真实执行，也用于命令预览 */
export interface RunStep {
  cmd: string
  args: string[]
  cwd?: string
  description: string
}

export interface CollectResult {
  steps: RunStep[]
  outputs: string[]   // 采集产出的文件（如 .exec）
}

export interface ReportResult {
  steps: RunStep[]
  reportDir: string
}

/** 调用上下文：当前 key、工作目录、已抓取的 exec 列表 */
export interface CoverageContext {
  key: string
  workdir: string
  execs: string[]
  /** xiaoxiao-jacoco agent 的 tcpserver 地址 host:port（来自 config.agentAddress，单一来源） */
  agentAddress: string
}

/** 一条可由 UI 执行的 CLI 指令定义（插件把工具链的全部指令+参数暴露给 UI） */
export interface CoverageCommandDef {
  id: string
  label: string
  description: string
  params: CoverageParamSpec[]
  /** 该指令依赖的共享配置：agent=Agent 地址/归属 Key；classfiles=classfiles 本地路径（UI 据此按需显示配置条） */
  needs?: ('agent' | 'classfiles')[]
  /** 由参数值构造真实命令步骤（供执行与预览） */
  build(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): RunStep[]
}

/** 覆盖率后端插件接口：每种语言一个插件，命令与参数全部由 UI 暴露 */
export interface CoverageBackend {
  id: string
  name: string
  languages: string[]
  description: string
  status: 'builtin'
  /** 探测工具链是否就绪 */
  detect(env: RuntimeEnv): boolean
  /** 参数 schema（驱动表单渲染 + 命令预览） */
  params: CoverageParamSpec[]
  /** 采集步骤（Java 有；其余语言采集在服务运行期完成，可不实现） */
  collect?(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): CollectResult
  /** 报告生成步骤（所有语言都有） */
  report(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): ReportResult
  /** 命令预览（UI 展示真实指令与参数） */
  preview(values: Record<string, string>, env: RuntimeEnv, ctx: CoverageContext): string
  /** 该插件工具链的全部指令（每条带自己的参数 schema 与 build） */
  commands: CoverageCommandDef[]
}

/** 把一个步骤格式化成可读命令（供预览展示） */
export function fmtStep(step: RunStep): string {
  return `${step.cmd} ${step.args.map(a => (/[\s"'<>]/.test(a) ? `"${a}"` : a)).join(' ')}`.trim()
}
