import path from 'path'
import type { CoverageBackendInfo } from '../types.js'
import { getAgentJarPath, getCliJarPath, getJavaExecutable } from './cliRunner.js'
import type { CoverageBackend, RuntimeEnv } from './lang/types.js'
import { jacocoBackend } from './lang/jacoco.js'
import { nycBackend } from './lang/nyc.js'
import { coveragePyBackend } from './lang/coverage-py.js'
import { goCovBackend } from './lang/go-cov.js'
import { gcovLcovBackend } from './lang/gcov-lcov.js'

/** 每个支持的语言是一个独立插件（electron/coverage/lang/*.ts） */
export const builtinBackends: CoverageBackend[] = [
  jacocoBackend,
  nycBackend,
  coveragePyBackend,
  goCovBackend,
  gcovLcovBackend
]

export function getBackend(id: string): CoverageBackend | undefined {
  return builtinBackends.find((b) => b.id === id)
}

/** 构造运行时环境：随包 JRE 优先的 java、jar 路径、classfiles 本地路径 */
export function buildRuntimeEnv(classfilesPath: string): RuntimeEnv {
  return {
    java: getJavaExecutable(),
    cliJar: getCliJarPath(),
    agentJar: getAgentJarPath(),
    resourcesRoot: path.join(process.resourcesPath || process.cwd(), 'resources'),
    classfilesPath
  }
}

/** 给渲染端列出后端清单（含参数 schema 与就绪状态），供 UI 渲染插件卡 + 参数表单 */
export function listBackends(): CoverageBackendInfo[] {
  const env = buildRuntimeEnv('')
  return builtinBackends.map((b) => ({
    id: b.id,
    name: b.name,
    languages: b.languages,
    status: b.detect(env) ? 'builtin' : 'pending',
    description: b.description,
    collect: !!b.collect,
    params: b.params,
    commands: b.commands.map((c) => ({ id: c.id, label: c.label, description: c.description, params: c.params, needs: c.needs }))
  }))
}
