import { spawn } from 'child_process'
import fs from 'fs'
import path from 'path'

/**
 * xiaoxiao-jacoco-cli / agent 的本地封装。
 * JRE 随 oAT 包分发：打包时 resources/jre 由 extraResources 随包，
 * 运行时落在 process.resourcesPath/resources/jre；没有则退回系统 java。
 */

function resourcesRoot(): string {
  // 打包：electron-builder extraResources 把 resources/ 复制到 <resourcesPath>/resources/；
  // dev：jar 在 <项目根>/resources/。按能否找到 cli jar 依次回退，避免 dev 下拿到 Electron.app 内不存在的路径
  const candidates = [
    path.join(process.resourcesPath || process.cwd(), 'resources'),
    path.join(process.cwd(), 'resources')
  ]
  for (const c of candidates) {
    try {
      if (fs.existsSync(path.join(c, 'xiaoxiao-jacoco-cli.jar'))) return c
    } catch { /* ignore */ }
  }
  return candidates[0]
}

export function getCliJarPath(): string {
  return path.join(resourcesRoot(), 'xiaoxiao-jacoco-cli.jar')
}

export function getAgentJarPath(): string {
  return path.join(resourcesRoot(), 'xiaoxiao-jacoco-agent.jar')
}

export function getBundledJre(): string | null {
  const jreBin = path.join(resourcesRoot(), 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  return fs.existsSync(jreBin) ? jreBin : null
}

export function getJavaExecutable(): string {
  return getBundledJre() ?? 'java'
}

export interface CliResult {
  code: number
  stdout: string
  stderr: string
}

/** 运行 xiaoxiao-jacoco-cli，args 为子命令及参数（如 ['report', 'a.exec', '--classfiles', ...]） */
export function runJacocoCli(args: string[]): Promise<CliResult> {
  const jar = getCliJarPath()
  if (!fs.existsSync(jar)) {
    return Promise.reject(new Error(`未找到 xiaoxiao-jacoco-cli.jar：${jar}（请放入 resources/ 并重新打包）`))
  }
  return new Promise((resolve, reject) => {
    console.info('[覆盖率] $ java -jar %s %s', path.basename(jar), args.join(' '))
    const proc = spawn(getJavaExecutable(), ['-jar', jar, ...args], { windowsHide: true })
    let stdout = ''
    let stderr = ''
    proc.stdout.on('data', (d) => (stdout += d.toString()))
    proc.stderr.on('data', (d) => (stderr += d.toString()))
    proc.on('error', (err) => {
      console.error('[覆盖率] cli 启动失败:', err.message)
      reject(err)
    })
    proc.on('close', (code) => {
      if (code !== 0) {
        console.error('[覆盖率] cli 退出码 %d: %s', code ?? -1, (stderr || stdout).slice(0, 500))
      }
      resolve({ code: code ?? -1, stdout, stderr })
    })
  })
}

export function fileSize(filePath: string): number {
  try {
    return fs.statSync(filePath).size
  } catch {
    return 0
  }
}
