import { spawnSync } from 'child_process'

/** 同步探测命令是否可用（status 0 视为就绪） */
export function hasCommand(cmd: string): boolean {
  try {
    const r = spawnSync(cmd, ['--version'], { windowsHide: true, timeout: 5000 })
    return r.status === 0
  } catch {
    return false
  }
}

/** 把参数值取布尔（'true'/true → true） */
export function asBool(v: string | undefined): boolean {
  return v === 'true' || v === true as unknown as string
}

/** 把参数值取字符串（空串/undefined → 默认） */
export function asStr(v: string | undefined, d = ''): string {
  return v === undefined || v === '' ? d : v
}
