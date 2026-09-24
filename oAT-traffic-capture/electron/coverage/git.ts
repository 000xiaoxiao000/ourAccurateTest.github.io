import fs from 'fs'
import os from 'os'
import path from 'path'
import crypto from 'crypto'
import { spawn, spawnSync } from 'child_process'
import { detectSourceRoot } from './projectDetect.js'

/**
 * Git 源码供给（主进程专用，renderer 没有 child_process）。
 *
 * 定位：为增量覆盖率报告（incremental）准备「新版 / 旧版源码」，
 * 不负责 classfiles（那是构建产物，git 仓库里没有，必须从 CI 制品或本地构建目录拿）。
 *
 * 三档能力：
 *   FULL    —— 有系统 git：本地仓库 worktree 检出 / 远程稀疏克隆 / 按 ref 切版本
 *   LOCAL   —— 无 git 但能指本地目录：直接当源码根，不切版本（由 UI 走本地路径选择）
 *   OFFLINE —— 导入 zip / tar.gz 解压当源码根
 *
 * 本模块刻意不 import electron（baseDir 由调用方注入），这样核心逻辑可在纯 Node 下测试。
 */

export type GitAuthMode = 'system' | 'password' | 'token' | 'ssh'
export type GitErrorKind = 'auth' | 'network' | 'notfound' | 'nogit' | 'io'

export interface GitCredentials {
  mode: GitAuthMode
  /** password / token 模式必填：git 先问 username，令牌只是 password */
  username?: string
  /** 密码或令牌，统一字段名；仅内存传递，用完即弃 */
  secret?: string
  sshKeyPath?: string
  allowInsecureSsl?: boolean
  caFilePath?: string
}

export interface GitCapability {
  available: boolean
  bin?: string
  version?: string
  /** >= 2.25 支持 cone 模式 sparse-checkout */
  sparseOk: boolean
  /** >= 2.5 支持 worktree */
  worktreeOk: boolean
  reason?: string
  installHint?: string
}

export interface GitRefItem {
  name: string
  sha?: string
  remote?: boolean
}

export interface GitCommit {
  sha: string
  shortSha: string
  author: string
  date: string
  message: string
  refs?: string[]
}

export interface GitRefQuery {
  source: 'local' | 'remote'
  /** local：本地仓库路径（会自动向上找 .git） */
  repoPath?: string
  /** remote：仓库 URL（https 或 ssh） */
  url?: string
  credentials?: GitCredentials
}

export interface GitPrepareRequest {
  mode: 'local' | 'remote' | 'archive'
  repoPath?: string
  url?: string
  newRef?: string
  oldRef?: string
  credentials?: GitCredentials
  /** archive：压缩包绝对路径 */
  archivePath?: string
  label?: string
  /**
   * 检出到哪个目录。留空 = 落到应用缓存目录（…/oat-coverage/git/…）；
   * 填了就把源码检到用户自己的目录里（必须为空目录或上一次检出的 worktree，绝不删已有文件）。
   */
  outDir?: string
}

export interface GitPrepareResult {
  success: boolean
  newSourceRoot?: string
  oldSourceRoot?: string
  resolvedNewRef?: string
  resolvedOldRef?: string
  /** 复用了已有 worktree（未重新检出） */
  reused?: boolean
  warnings: string[]
  error?: string
  errorKind?: GitErrorKind
}

export type GitLogFn = (payload: { phase: string; text: string; percent?: number }) => void

const LOG_FORMAT = '%H%x1f%an%x1f%ad%x1f%s%x1f%D'
const DEFAULT_COMMIT_LIMIT = 200
const CLONE_TIMEOUT = 30 * 60 * 1000
const NORMAL_TIMEOUT = 60 * 1000

class GitError extends Error {
  kind: GitErrorKind
  constructor(kind: GitErrorKind, message: string) {
    super(message)
    this.kind = kind
  }
}

// ===== 能力探测 =====

let capCache: GitCapability | undefined

export function detectGit(force = false): GitCapability {
  if (capCache && !force) return capCache
  capCache = probeGit()
  return capCache
}

function gitCandidates(): string[] {
  if (process.platform === 'darwin') {
    const list = ['/opt/homebrew/bin/git', '/usr/local/bin/git']
    // /usr/bin/git 是 Xcode CLT 的 shim：未装 CLT 时执行它会弹系统安装对话框并阻塞进程
    let hasClt = false
    try {
      const r = spawnSync('xcode-select', ['-p'], { encoding: 'utf8', timeout: 2000, windowsHide: true })
      hasClt = r.status === 0
    } catch { /* 视为未安装 */ }
    if (hasClt) list.push('/usr/bin/git')
    return list
  }
  if (process.platform === 'win32') {
    const pf = process.env.ProgramFiles || 'C:\\Program Files'
    const la = process.env.LOCALAPPDATA || ''
    return [
      path.join(pf, 'Git', 'cmd', 'git.exe'),
      path.join(pf, 'Git', 'bin', 'git.exe'),
      path.join(la, 'Programs', 'Git', 'cmd', 'git.exe'),
      'git.exe'
    ]
  }
  return ['/usr/bin/git', '/usr/local/bin/git', '/bin/git', 'git']
}

function installHint(): string {
  if (process.platform === 'darwin') return 'xcode-select --install（或 brew install git）'
  if (process.platform === 'win32') return 'winget install Git.Git'
  return 'sudo apt install git（或系统包管理器安装 git）'
}

function probeGit(): GitCapability {
  const reasonNone: GitCapability = {
    available: false,
    sparseOk: false,
    worktreeOk: false,
    reason: '未在本机检测到 git，无法按 ref 切版本（可直接指定本地目录或导入源码压缩包）',
    installHint: installHint()
  }
  let best: { bin: string; version: string; major: number; minor: number } | undefined
  for (const bin of gitCandidates()) {
    try {
      const r = spawnSync(bin, ['--version'], { encoding: 'utf8', timeout: 3000, windowsHide: true })
      const m = /git version (\d+)\.(\d+)/.exec(`${r.stdout || ''}${r.stderr || ''}`)
      if (!m) continue
      const cur = { bin, version: `${m[1]}.${m[2]}`, major: Number(m[1]), minor: Number(m[2]) }
      if (!best || cur.major > best.major || (cur.major === best.major && cur.minor > best.minor)) best = cur
    } catch { /* 候选不可用，继续 */ }
  }
  if (!best) return reasonNone
  const v = best.major * 100 + best.minor
  return {
    available: true,
    bin: best.bin,
    version: best.version,
    sparseOk: v >= 225,
    worktreeOk: v >= 205,
    reason: undefined
  }
}

// ===== 进程与环境变量 =====

/** 清掉可能干扰的继承变量；GUI 启动时常带着外层 shell 的 GIT_* */
function cleanEnv(extra?: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env }
  for (const k of Object.keys(env)) {
    if (/^GIT_(DIR|WORK_TREE|INDEX_FILE|OBJECT_DIRECTORY|ALTERNATE_OBJECT_DIRECTORIES|CONFIG|CONFIG_GLOBAL|CONFIG_COUNT|CONFIG_KEY_0|CONFIG_VALUE_0|ASKPASS|SSH_COMMAND|SSL_NO_VERIFY|SSL_CAINFO|TERMINAL_PROMPT)$/.test(k)) {
      delete env[k]
    }
  }
  env.GIT_TERMINAL_PROMPT = '0'
  if (extra) Object.assign(env, extra)
  return env
}

function runGit(bin: string, args: string[], opts: { cwd?: string; env?: NodeJS.ProcessEnv; timeout?: number; onLine?: (line: string) => void } = {}): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    let so = ''
    let se = ''
    let lineBuf = ''
    let done = false
    const finish = (code: number) => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve({ code, stdout: so, stderr: se })
    }
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL') } catch { /* ignore */ }
      se += '\n[timeout]'
      finish(-1)
    }, opts.timeout ?? NORMAL_TIMEOUT)
    const child = spawn(bin, args, { cwd: opts.cwd, env: opts.env ?? cleanEnv(), windowsHide: true })
    child.stdout?.on('data', (d) => { so += d.toString() })
    child.stderr?.on('data', (d) => {
      const s = d.toString()
      se += s
      if (!opts.onLine) return
      // git 进度行用 \r 刷新（不带 \n），按 \r/\n 都切开
      lineBuf += s
      const parts = lineBuf.split(/\r\n|\r|\n/)
      lineBuf = parts.pop() ?? ''
      for (const line of parts) if (line.trim()) opts.onLine(line.trim())
    })
    child.on('error', (e) => { se += String(e?.message ?? e); finish(-1) })
    child.on('close', (code) => {
      if (lineBuf.trim() && opts.onLine) opts.onLine(lineBuf.trim())
      finish(code ?? -1)
    })
  })
}

function shQuote(s: string): string {
  return `'${s.replace(/'/g, "'\\''")}'`
}

/**
 * 凭据传送：临时 askpass 脚本。实测（git 2.49）git 会调用脚本两次，
 * prompt 分别为 `Username for 'https://host':` 与 `Password for 'https://user@host':`，
 * 脚本按关键字分流即可同时供出用户名与密码/令牌。绝不拼进 URL。
 */
async function withCredentials<T>(creds: GitCredentials | undefined, fn: (env: NodeJS.ProcessEnv) => Promise<T>): Promise<T> {
  const mode = creds?.mode ?? 'system'
  const env: NodeJS.ProcessEnv = {}
  if (creds?.caFilePath) env.GIT_SSL_CAINFO = creds.caFilePath
  if (creds?.allowInsecureSsl) env.GIT_SSL_NO_VERIFY = '1'
  if (mode === 'ssh') {
    if (creds?.sshKeyPath) env.GIT_SSH_COMMAND = `ssh -i "${creds.sshKeyPath}" -o IdentitiesOnly=yes`
    return fn(env)
  }
  const secret = creds?.secret ?? ''
  if (mode !== 'password' && mode !== 'token') return fn(env)
  if (!secret) return fn(env)

  const isWin = process.platform === 'win32'
  const file = path.join(os.tmpdir(), `oat-git-askpass-${crypto.randomBytes(6).toString('hex')}${isWin ? '.bat' : '.sh'}`)
  const user = creds?.username ?? ''
  // Windows 的 <nul set /p= 输出不带换行；特殊字符（& < > ^）需用户避免，令牌通常无此问题
  const content = isWin
    ? `@echo off\r\nsetlocal\r\nset "p=%*"\r\necho %p%|findstr /i "Username" >nul\r\nif errorlevel 1 goto pwd\r\n<nul set /p="${user}"\r\nexit /b 0\r\n:pwd\r\n<nul set /p="${secret}"\r\nexit /b 0\r\n`
    : `#!/bin/sh\ncase "$*" in\n  *[Uu]sername*) printf '%s' ${shQuote(user)} ;;\n  *) printf '%s' ${shQuote(secret)} ;;\nesac\n`
  fs.writeFileSync(file, content, { mode: 0o700 })
  env.GIT_ASKPASS = file
  env.GIT_TERMINAL_PROMPT = '0'
  try {
    return await fn(env)
  } finally {
    try { fs.unlinkSync(file) } catch { /* ignore */ }
  }
}

function classifyError(stderr: string): GitErrorKind {
  const s = (stderr || '').toLowerCase()
  if (/authentication failed|could not read username|permission denied \(publickey\)|403|401|invalid username or password/.test(s)) return 'auth'
  if (/could not resolve host|timed out|connection refused|unable to access|ssl|proxy|no route to host/.test(s)) return 'network'
  if (/repository not found|does not exist|couldn't find remote ref|not found/.test(s)) return 'notfound'
  return 'io'
}

function wrapError(e: any): { error: string; errorKind: GitErrorKind } {
  if (e instanceof GitError) return { error: e.message, errorKind: e.kind }
  const msg = String(e?.message ?? e)
  return { error: msg, errorKind: classifyError(msg) }
}

// ===== 仓库定位与缓存目录 =====

/** 从目录向上找 .git（目录或 worktree 的 .git 文件） */
function findRepoRoot(dir?: string): string | undefined {
  if (!dir) return undefined
  let cur = path.resolve(dir)
  for (let i = 0; i < 12; i++) {
    const g = path.join(cur, '.git')
    try {
      if (fs.existsSync(g)) return cur
    } catch { /* ignore */ }
    const parent = path.dirname(cur)
    if (parent === cur) break
    cur = parent
  }
  return undefined
}

function gitRoot(baseDir: string): string {
  return path.join(baseDir, 'git')
}

function hashKey(s: string): string {
  return crypto.createHash('sha256').update(s).digest('hex').slice(0, 12)
}

function slugify(s: string): string {
  return (s || 'HEAD').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 60)
}

// ===== 分支 / Tag / 提交列表 =====

export async function listGitRefs(_baseDir: string, q: GitRefQuery): Promise<{ success: boolean; branches: GitRefItem[]; tags: GitRefItem[]; error?: string; errorKind?: GitErrorKind }> {
  const cap = detectGit()
  if (!cap.available || !cap.bin) return { success: false, branches: [], tags: [], error: cap.reason ?? '本机无 git', errorKind: 'nogit' }
  const bin = cap.bin
  try {
    if (q.source === 'local') {
      const repo = findRepoRoot(q.repoPath)
      if (!repo) return { success: false, branches: [], tags: [], error: '该目录及其上层没有 .git，不是 git 仓库', errorKind: 'notfound' }
      const br = await runGit(bin, ['-C', repo, 'branch', '--format', '%(refname:short)', '-a'])
      const tg = await runGit(bin, ['-C', repo, 'tag', '--sort=-creatordate'])
      if (br.code !== 0) return { success: false, branches: [], tags: [], error: br.stderr.trim() || '读取分支失败', errorKind: classifyError(br.stderr) }
      const branches = br.stdout.split('\n').map((s) => s.trim()).filter(Boolean)
        .filter((n) => n !== 'origin' && !n.endsWith('/HEAD'))
        .map((n) => ({ name: n, remote: n.startsWith('remotes/') || n.startsWith('origin/') }))
      const tags = tg.stdout.split('\n').map((s) => s.trim()).filter(Boolean).map((n) => ({ name: n }))
      return { success: true, branches, tags }
    }
    if (!q.url) return { success: false, branches: [], tags: [], error: '请填写仓库地址', errorKind: 'io' }
    return await withCredentials(q.credentials, async (env) => {
      const r = await runGit(bin, ['ls-remote', '--heads', '--tags', normalizeRemoteUrl(q.url as string)], { env, timeout: NORMAL_TIMEOUT })
      if (r.code !== 0) return { success: false, branches: [], tags: [], error: '读取远程引用失败：' + (r.stderr.trim() || r.stdout.trim()), errorKind: classifyError(r.stderr) }
      const branches: GitRefItem[] = []
      const tags: GitRefItem[] = []
      for (const line of r.stdout.split('\n')) {
        const m = /^([0-9a-f]+)\s+refs\/(heads|tags)\/(.+)$/.exec(line.trim())
        if (!m) continue
        const item = { name: m[3], sha: m[1], remote: true }
        if (m[2] === 'heads') branches.push(item)
        else tags.push(item)
      }
      // 默认分支优先排前面（main → master → 其余字典序），UI 默认选中会更准
      const rank = (n: string) => (n === 'main' ? 0 : n === 'master' ? 1 : 2)
      branches.sort((a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name))
      tags.sort((a, b) => b.name.localeCompare(a.name))
      return { success: true, branches, tags }
    })
  } catch (e) {
    const w = wrapError(e)
    return { success: false, branches: [], tags: [], error: w.error, errorKind: w.errorKind }
  }
}

export async function listGitCommits(baseDir: string, q: GitRefQuery & { ref?: string; limit?: number; keyword?: string }, onLog?: GitLogFn): Promise<{ success: boolean; commits: GitCommit[]; truncated: boolean; error?: string; errorKind?: GitErrorKind }> {
  const cap = detectGit()
  if (!cap.available || !cap.bin) return { success: false, commits: [], truncated: false, error: cap.reason ?? '本机无 git', errorKind: 'nogit' }
  const bin = cap.bin
  const limit = Math.min(Math.max(q.limit ?? DEFAULT_COMMIT_LIMIT, 1), 1000)
  const args = ['log', '--pretty=format:' + LOG_FORMAT, '--date=iso', '-n', String(limit)]
  if (q.keyword) args.push('--grep', q.keyword, '-i')
  try {
    let cwd: string | undefined
    let env: NodeJS.ProcessEnv | undefined
    let refArgs: string[] = []
    if (q.source === 'local') {
      const repo = findRepoRoot(q.repoPath)
      if (!repo) return { success: false, commits: [], truncated: false, error: '该目录及其上层没有 .git，不是 git 仓库', errorKind: 'notfound' }
      cwd = repo
      refArgs = q.ref && q.ref !== 'HEAD' ? [q.ref] : ['HEAD']
    } else {
      if (!q.url) return { success: false, commits: [], truncated: false, error: '请填写仓库地址', errorKind: 'io' }
      // 不 clone：本地空仓 + 只 fetch 这一个 ref 的最近 N 条元数据
      const repo = await ensureRepo(baseDir, q.url, q.credentials, cap, onLog)
      cwd = repo.repoDir
      env = repo.env
      const ref = q.ref && q.ref !== 'HEAD' ? q.ref : 'HEAD'
      const got = await resolveRemoteRef(bin, repo.repoDir, ref, cap, env, { depth: limit + 1, onLog })
      if (!got.sha) return { success: false, commits: [], truncated: false, error: got.error ?? `ref 不存在：${ref}`, errorKind: /不存在|not found/.test(got.error ?? '') ? 'notfound' : 'io' }
      refArgs = [got.sha]
    }
    args.push(...refArgs)
    const r = await runGit(bin, ['-C', cwd as string, ...args], { env })
    if (r.code !== 0) return { success: false, commits: [], truncated: false, error: '读取提交历史失败：' + (r.stderr.trim() || r.stdout.trim()), errorKind: classifyError(r.stderr) }
    const rows = r.stdout.split('\n').filter(Boolean)
    const commits = rows.map((line) => {
      const [sha, author, date, message, refs] = line.split('\x1f')
      return {
        sha: sha ?? '',
        shortSha: (sha ?? '').slice(0, 7),
        author: author ?? '',
        date: (date ?? '').replace(' +0800', ''),
        message: message ?? '',
        refs: (refs ?? '').split(',').map((s) => s.trim()).filter(Boolean)
      }
    })
    return { success: true, commits, truncated: commits.length >= limit }
  } catch (e) {
    const w = wrapError(e)
    return { success: false, commits: [], truncated: false, error: w.error, errorKind: w.errorKind }
  }
}

// ===== 远程仓库元数据：不 clone，按需 fetch 单个 ref =====
//
// 借鉴 oAT-service 的 GitRemoteSupportService（JGit 实现）：
//   分支/Tag  → ls-remote（不下任何东西）
//   提交历史  → 本地空仓 init + remote add + fetch 单个 refspec（可带 --depth）
//   真正要源码 → 才把该 commit 检出到 worktree
// 相比整仓 clone，浏览提交只需要「一条分支最近 N 条元数据」，体量差一个数量级。

const FETCH_TIMEOUT = 30 * 60 * 1000

/** 支持直接粘贴 GitLab / GitHub 网页地址（借鉴 oAT-service 的 normalizeRemoteUrl） */
export function normalizeRemoteUrl(url: string): string {
  let s = (url ?? '').trim()
  if (!s) return s
  const hash = s.indexOf('#')
  if (hash > 0) s = s.substring(0, hash)
  const q = s.indexOf('?')
  if (q > 0) s = s.substring(0, q)
  while (s.endsWith('/')) s = s.slice(0, -1)
  const markers = ['/-/commits/', '/-/commit/', '/-/tree/', '/-/blob/', '/-/branches/', '/commits/', '/commit/', '/tree/', '/blob/', '/branches/']
  for (const m of markers) {
    const i = s.indexOf(m)
    if (i > 0) { s = s.substring(0, i); break }
  }
  while (s.endsWith('/')) s = s.slice(0, -1)
  return s
}

/** refs/heads/x、refs/remotes/origin/x、refs/tags/x、origin/x 统一成短名 */
function normalizeRefName(ref: string): string {
  let v = (ref ?? '').trim()
  for (const p of ['refs/heads/', 'refs/remotes/origin/', 'refs/tags/', 'origin/']) {
    if (v.startsWith(p)) { v = v.substring(p.length); break }
  }
  return v
}

function isShaLike(s: string): boolean { return /^[0-9a-f]{7,40}$/i.test(s) }

function streamProgress(line: string, onLog?: GitLogFn): void {
  const m = /(Enumerating|Counting|Compressing|Receiving|Resolving)[a-z ]*:\s*(\d+)%/.exec(line)
  if (m) onLog?.({ phase: 'fetch', text: `正在获取元数据 · ${m[1]} ${m[2]}%`, percent: Math.min(99, Number(m[2])) })
}

interface RepoHandle {
  repoDir: string
  env: NodeJS.ProcessEnv
}

const repoLocks = new Map<string, Promise<RepoHandle>>()

async function ensureRepo(baseDir: string, rawUrl: string, creds: GitCredentials | undefined, cap: GitCapability, onLog?: GitLogFn): Promise<RepoHandle> {
  const url = normalizeRemoteUrl(rawUrl)
  const key = hashKey(url)
  const cacheDir = path.join(gitRoot(baseDir), 'cache', key)
  const repoDir = path.join(cacheDir, 'repo')
  const lockKey = `${baseDir}|${key}`
  const exist = repoLocks.get(lockKey)
  if (exist) return exist
  const p = doInitRepo(url, cacheDir, repoDir, creds, cap, onLog)
  repoLocks.set(lockKey, p)
  try {
    return await p
  } catch (e) {
    repoLocks.delete(lockKey)
    throw e
  }
}

async function doInitRepo(url: string, cacheDir: string, repoDir: string, creds: GitCredentials | undefined, cap: GitCapability, onLog?: GitLogFn): Promise<RepoHandle> {
  // 只有 .git/ 才算可用缓存（metadata-only，没有工作区文件）
  if (fs.existsSync(path.join(repoDir, '.git'))) {
    onLog?.({ phase: 'repo', text: '复用已缓存的元数据仓库', percent: 100 })
    return { repoDir, env: cleanEnv() }
  }
  // 上次操作被强杀会留下残缺目录 ⇒ 清掉重建；本地 own init 很轻，不会有大下载
  if (fs.existsSync(repoDir)) {
    try { fs.rmSync(repoDir, { recursive: true, force: true }) } catch { /* ignore */ }
  }
  fs.mkdirSync(repoDir, { recursive: true })
  return await withCredentials(creds, async (credEnv) => {
    const env = cleanEnv(credEnv)
    const bin = cap.bin as string
    const init = await runGit(bin, ['init'], { cwd: repoDir, env })
    if (init.code !== 0) throw new GitError('io', '初始化本地缓存仓库失败：' + (init.stderr.trim() || init.stdout.trim()))
    let r = await runGit(bin, ['remote', 'add', 'origin', url], { cwd: repoDir, env })
    if (r.code !== 0) r = await runGit(bin, ['remote', 'set-url', 'origin', url], { cwd: repoDir, env })
    if (r.code !== 0) throw new GitError('io', '设置远程地址失败：' + (r.stderr.trim() || r.stdout.trim()))
    onLog?.({ phase: 'repo', text: '已建立本地元数据仓库（尚未下载提交）', percent: 100 })
    return { repoDir, env }
  })
}

async function revParseAny(bin: string, repo: string, candidates: string[], env?: NodeJS.ProcessEnv): Promise<string | undefined> {
  for (const c of candidates) {
    const r = await runGit(bin, ['-C', repo, 'rev-parse', '--verify', `${c}^{commit}`], { env })
    if (r.code === 0) return r.stdout.trim()
  }
  return undefined
}

/**
 * fetch 单个 refspec，三级降级：
 *   ① --filter=blob:none + --depth（只下元数据，最省）
 *   ② 只 --depth（服务器不支持 filter）
 *   ③ 不带任何优化（老服务器 / SHA 不在浅层边界内）
 */
async function fetchSpec(bin: string, repo: string, cap: GitCapability, spec: string | undefined, env: NodeJS.ProcessEnv | undefined, opts: { depth?: number; onLog?: GitLogFn } = {}): Promise<{ code: number; msg: string }> {
  const depth = opts.depth && opts.depth > 0 ? ['--depth', String(opts.depth)] : []
  const tail = spec ? ['origin', spec] : ['origin']
  const attempts: string[][] = []
  if (cap.sparseOk) attempts.push(['fetch', '--progress', '--filter=blob:none', ...depth, ...tail])
  if (depth.length) attempts.push(['fetch', '--progress', ...depth, ...tail])
  attempts.push(['fetch', '--progress', ...tail])
  let msg = ''
  for (const args of attempts) {
    const r = await runGit(bin, ['-C', repo, ...args], { env, timeout: FETCH_TIMEOUT, onLine: (line) => streamProgress(line, opts.onLog) })
    if (r.code === 0) return { code: 0, msg: '' }
    msg = r.stderr.trim() || r.stdout.trim()
  }
  return { code: -1, msg }
}

/** 远程 ref → 完整 SHA：本地已有就直接用，没有才按 ref 类型去拉取单个 refspec */
async function resolveRemoteRef(bin: string, repo: string, ref: string, cap: GitCapability, env: NodeJS.ProcessEnv | undefined, opts: { depth?: number; onLog?: GitLogFn } = {}): Promise<{ sha?: string; error?: string }> {
  const raw = normalizeRefName(ref ?? '')
  if (!raw || raw === 'HEAD') {
    const h = await revParseAny(bin, repo, ['refs/remotes/origin/HEAD', 'FETCH_HEAD'], env)
    if (h) return { sha: h }
    const r = await fetchSpec(bin, repo, cap, undefined, env, opts)
    if (r.code !== 0) return { error: '拉取默认分支失败：' + r.msg }
    const h2 = await revParseAny(bin, repo, ['refs/remotes/origin/HEAD', 'FETCH_HEAD'], env)
    return { sha: h2, error: h2 ? undefined : '远程虽有内容但找不到默认分支的提交' }
  }
  const cached = await revParseAny(bin, repo, [`refs/remotes/origin/${raw}`, `refs/tags/${raw}`, `refs/heads/${raw}`, raw], env)
  if (cached) return { sha: cached }
  opts.onLog?.({ phase: 'fetch', text: `正在获取 ${raw}（只拉这一个 ref）…`, percent: 5 })
  const specs = isShaLike(raw) ? [raw] : [`refs/heads/${raw}:refs/remotes/origin/${raw}`, `refs/tags/${raw}:refs/tags/${raw}`]
  let msg = ''
  for (const spec of specs) {
    const r = await fetchSpec(bin, repo, cap, spec, env, opts)
    if (r.code === 0) {
      const sha = await revParseAny(bin, repo, [`refs/remotes/origin/${raw}`, `refs/tags/${raw}`, raw], env)
      if (sha) return { sha }
    } else {
      msg = r.msg
    }
  }
  // 手填 SHA 且服务器不允许按 SHA 取 → 退到拉全部分支再找（兜底，成本高但只此一次）
  if (isShaLike(raw)) {
    const r = await fetchSpec(bin, repo, cap, '+refs/heads/*:refs/remotes/origin/*', env, opts)
    if (r.code === 0) {
      const sha = await revParseAny(bin, repo, [raw], env)
      if (sha) return { sha }
    }
  }
  return { error: msg ? `获取 ${raw} 失败：${msg}` : `ref 不存在：${raw}` }
}

// ===== 检出 worktree =====

async function headSha(bin: string, dir: string): Promise<string> {
  const r = await runGit(bin, ['-C', dir, 'rev-parse', 'HEAD'])
  return r.code === 0 ? r.stdout.trim() : ''
}

/**
 * 决定检出落点：
 *  - outDir 留空 → 默认缓存目录（不碰用户任何目录）
 *  - outDir 填了 → 检到用户自己的目录；要求为空目录或上一次检出留下的 worktree，
 *    非空且非 worktree 一律报错中止，绝不删用户已有文件
 */
function resolveOutDir(outDir: string | undefined, fallback: string, suffix = ''): { dir: string } | { error: string } {
  const raw = (outDir ?? '').trim()
  if (!raw) return { dir: fallback }
  const p = path.resolve(raw + suffix)
  if (fs.existsSync(p)) {
    if (!fs.statSync(p).isDirectory()) return { error: `目标路径已存在且不是目录：${p}` }
    const isWorktree = fs.existsSync(path.join(p, '.git'))
    if (!isWorktree && fs.readdirSync(p).length > 0) {
      return { error: `目标目录非空且不是 git 工作区，已中止（不会删除你的文件）：${p}\n请改用空目录，或先手动清空` }
    }
  }
  return { dir: p }
}

async function ensureWorktree(bin: string, repo: string, sha: string, label: string, wtDir: string, env: NodeJS.ProcessEnv | undefined, onLog?: GitLogFn): Promise<{ dir: string; sha: string; reused: boolean }> {
  /** 工作区完整性：porcelain 输出里第二列是 D 即"文件在磁盘上缺失"（partial clone 懒下载失败/检出被打断的典型残留） */
  const missingFiles = async (): Promise<boolean> => {
    const st = await runGit(bin, ['-C', wtDir, 'status', '--porcelain'], { env })
    if (st.code !== 0) return true // 连 status 都跑不动 ⇒ 工作区不可信
    return st.stdout.split('\n').some((l) => l.length > 1 && l[1] === 'D')
  }
  if (fs.existsSync(wtDir)) {
    if ((await headSha(bin, wtDir)) === sha) {
      if (!(await missingFiles())) {
        onLog?.({ phase: 'checkout', text: `复用已检出的 ${label}`, percent: 100 })
        return { dir: wtDir, sha, reused: true }
      }
      // HEAD 对但文件缺失 ⇒ 先尝试原地补齐（触发按需 blob 下载）；补不齐再推倒重检
      onLog?.({ phase: 'checkout', text: `检出目录不完整，正在补齐源码文件…`, percent: 20 })
      const rep = await runGit(bin, ['-C', wtDir, 'checkout', '--', '.'], { env, timeout: CLONE_TIMEOUT })
      if (rep.code === 0 && !(await missingFiles())) {
        onLog?.({ phase: 'checkout', text: `已补齐 ${label} 的源码文件`, percent: 100 })
        return { dir: wtDir, sha, reused: true }
      }
      onLog?.({ phase: 'checkout', text: `补齐失败，重新检出 ${label} …`, percent: 30 })
    }
    // 目录可能被用户改名/移动过（注册里的 gitdir 指向旧路径 ⇒ 悬空 prunable）——先 repair 修正注册再 remove
    await runGit(bin, ['-C', repo, 'worktree', 'repair', wtDir], { env })
    await runGit(bin, ['-C', repo, 'worktree', 'remove', '--force', wtDir], { env })
    // 用户指定的空目录：git 要求目标路径不存在，remove 未生效时清掉空目录再 add
    if (fs.existsSync(wtDir)) {
      const st = fs.statSync(wtDir)
      const empty = st.isDirectory() && fs.readdirSync(wtDir).length === 0
      if (!empty) throw new GitError('io', `检出目录已存在且不为空，已中止（不会删除你的文件）：${wtDir}`)
      fs.rmSync(wtDir, { recursive: true, force: true })
    }
  }
  fs.mkdirSync(path.dirname(wtDir), { recursive: true })
  onLog?.({ phase: 'checkout', text: `正在检出 ${label}（按需下载源码文件）…`, percent: 40 })
  // 目录被手工删掉 / 清过缓存后，主仓库 .git/worktrees 里仍留着注册信息，
  // git 会报 "is a missing but already registered worktree" —— 先 prune 再 add
  await runGit(bin, ['-C', repo, 'worktree', 'prune'], { env })
  let r = await runGit(bin, ['-C', repo, 'worktree', 'add', '--detach', wtDir, sha], { env, timeout: CLONE_TIMEOUT, onLine: (line) => streamProgress(line, onLog) })
  if (r.code !== 0 && /already registered|missing/i.test(r.stderr)) {
    await runGit(bin, ['-C', repo, 'worktree', 'prune'], { env })
    r = await runGit(bin, ['-C', repo, 'worktree', 'add', '--detach', wtDir, sha], { env, timeout: CLONE_TIMEOUT, onLine: (line) => streamProgress(line, onLog) })
  }
  if (r.code !== 0) throw new GitError(classifyError(r.stderr), `检出 ${label} 失败：` + (r.stderr.trim() || r.stdout.trim()))
  onLog?.({ phase: 'checkout', text: `已检出 ${label}`, percent: 100 })
  return { dir: wtDir, sha, reused: false }
}

// ===== 准备源码（主入口） =====

export async function prepareGitSource(baseDir: string, req: GitPrepareRequest, onLog?: GitLogFn): Promise<GitPrepareResult> {
  const warnings: string[] = []
  const cap = detectGit()
  try {
    if (req.mode === 'archive') {
      if (!cap.available) {
        // 解压不需要 git：zip 用系统 unzip，tar.gz 用 tar
      }
      const p = req.archivePath
      if (!p || !fs.existsSync(p)) return { success: false, warnings, error: '压缩包不存在：' + (p ?? ''), errorKind: 'io' }
      const dest = path.join(gitRoot(baseDir), 'import', `${slugify(path.basename(p))}-${Date.now()}`)
      fs.mkdirSync(dest, { recursive: true })
      onLog?.({ phase: 'archive', text: '正在解压…', percent: 30 })
      const ok = extractArchive(p, dest)
      if (!ok) return { success: false, warnings, error: '解压失败（需要系统 unzip 或 tar）', errorKind: 'io' }
      const root = detectSourceRoot(dest)
      if (!root) warnings.push('解压后未自动定位到源码根，已回填解压目录，请手动指到 src/main/java 这一层')
      onLog?.({ phase: 'archive', text: '解压完成', percent: 100 })
      return { success: true, newSourceRoot: root || dest, warnings }
    }

    if (!cap.available || !cap.bin) {
      return { success: false, warnings, error: cap.reason ?? '本机无 git', errorKind: 'nogit' }
    }
    const bin = cap.bin

    if (req.mode === 'local') {
      const repo = findRepoRoot(req.repoPath)
      if (!repo) {
        // LOCAL 档：没有 git 仓库就把它当源码根，不切版本
        const root = detectSourceRoot(req.repoPath ?? '')
        if (!root) return { success: false, warnings, error: '该目录下没有 .git，也没找到源码目录（src/main/java 等）', errorKind: 'notfound' }
        warnings.push('未找到 git 仓库，已直接把该目录当源码根（不能按 ref 切版本）')
        return { success: true, newSourceRoot: root, warnings }
      }
      const out: GitPrepareResult = { success: true, warnings, reused: true }
      if (req.newRef) {
        const sha = await revParseAny(bin, repo, [req.newRef, `refs/heads/${req.newRef}`, `refs/remotes/origin/${req.newRef}`, `refs/tags/${req.newRef}`])
        if (!sha) return { success: false, warnings, error: `本地仓库里找不到 ref：${req.newRef}`, errorKind: 'notfound' }
        const t = resolveOutDir(req.outDir, path.join(gitRoot(baseDir), 'wt', hashKey(repo), slugify(req.newRef)))
        if ('error' in t) return { success: false, warnings, error: t.error, errorKind: 'io' }
        const r = await ensureWorktree(bin, repo, sha, req.newRef, t.dir, undefined, onLog)
        if (!r.reused) {
          warnings.push(req.outDir
            ? `已按 ${req.newRef} 检出到你指定的目录：${r.dir}`
            : `已按 ${req.newRef} 检出到独立缓存目录（不改动你的工作区）：${r.dir}`)
        }
        out.newSourceRoot = detectSourceRoot(r.dir) || r.dir
        out.resolvedNewRef = r.sha
        out.reused = r.reused
      } else {
        out.newSourceRoot = detectSourceRoot(repo) || repo
        warnings.push('未指定 ref，直接用当前工作区状态')
      }
      if (req.oldRef) {
        const sha = await revParseAny(bin, repo, [req.oldRef, `refs/heads/${req.oldRef}`, `refs/remotes/origin/${req.oldRef}`, `refs/tags/${req.oldRef}`])
        if (!sha) return { success: false, warnings, error: `本地仓库里找不到 ref：${req.oldRef}`, errorKind: 'notfound' }
        const t = resolveOutDir(req.outDir, path.join(gitRoot(baseDir), 'wt', hashKey(repo), slugify(req.oldRef)), '-old')
        if ('error' in t) return { success: false, warnings, error: t.error, errorKind: 'io' }
        const r = await ensureWorktree(bin, repo, sha, req.oldRef, t.dir, undefined, onLog)
        out.oldSourceRoot = detectSourceRoot(r.dir) || r.dir
        out.resolvedOldRef = r.sha
        out.reused = (out.reused ?? true) && r.reused
      }
      if (!out.newSourceRoot?.includes('src')) warnings.push('未自动定位到 src/main/java，已回填仓库/工作区根目录，建议手动指到源码根')
      return out
    }

    // remote
    if (!req.url) return { success: false, warnings, error: '请填写仓库地址', errorKind: 'io' }
    if (!req.newRef) return { success: false, warnings, error: '请选择或填写新版 ref（分支 / Tag / Commit）', errorKind: 'io' }
    const repo = await ensureRepo(baseDir, req.url, req.credentials, cap, onLog)
    // ⚠️ 整条网络链路（fetch 按需补提交、worktree add 懒下载 blob）都必须带凭据，
    // 且 askpass 临时脚本在 withCredentials 回调结束即被删除 ⇒ 必须把整个流程包进回调，
    // 不能先"领一个 env"出来用。缓存仓库复用路径返回的干净 env 没有凭据，
    // 会让 checkout 的按需 blob 下载失败，产出"HEAD 有了、文件全没有"的空壳工作区。
    const url = req.url as string
    const newRef = req.newRef as string
    return await withCredentials(req.credentials, async (credEnv) => {
      const env = cleanEnv(credEnv)
      const base = path.join(gitRoot(baseDir), 'cache', hashKey(normalizeRemoteUrl(url)), 'wt')
      const n = await resolveRemoteRef(bin, repo.repoDir, newRef, cap, env, { onLog })
      if (!n.sha) return { success: false, warnings, error: n.error ?? `ref 不存在：${newRef}`, errorKind: 'notfound' }
      const nt = resolveOutDir(req.outDir, path.join(base, slugify(newRef)))
      if ('error' in nt) return { success: false, warnings, error: nt.error, errorKind: 'io' }
      const nw = await ensureWorktree(bin, repo.repoDir, n.sha, newRef, nt.dir, env, onLog)
      if (!nw.reused) {
        warnings.push(req.outDir
          ? `已按 ${newRef} 检出到你指定的目录：${nw.dir}`
          : `已按 ${newRef} 检出到独立缓存目录：${nw.dir}`)
      }
      const out: GitPrepareResult = {
        success: true,
        warnings,
        newSourceRoot: detectSourceRoot(nw.dir) || nw.dir,
        resolvedNewRef: nw.sha,
        reused: nw.reused
      }
      if (req.oldRef) {
        const o = await resolveRemoteRef(bin, repo.repoDir, req.oldRef, cap, env, { onLog })
        if (!o.sha) return { success: false, warnings, error: o.error ?? `ref 不存在：${req.oldRef}`, errorKind: 'notfound' }
        const ot = resolveOutDir(req.outDir, path.join(base, slugify(req.oldRef)), '-old')
        if ('error' in ot) return { success: false, warnings, error: ot.error, errorKind: 'io' }
        const ow = await ensureWorktree(bin, repo.repoDir, o.sha, req.oldRef, ot.dir, env, onLog)
        out.oldSourceRoot = detectSourceRoot(ow.dir) || ow.dir
        out.resolvedOldRef = ow.sha
        out.reused = (out.reused ?? true) && ow.reused
      }
      if (!out.newSourceRoot?.includes('src')) warnings.push('未自动定位到 src/main/java，已回填检出目录，建议手动指到源码根')
      return out
    })
  } catch (e) {
    const w = wrapError(e)
    return { success: false, warnings, error: w.error, errorKind: w.errorKind }
  }
}

function extractArchive(file: string, dest: string): boolean {
  const lower = file.toLowerCase()
  const tryRun = (cmd: string, args: string[]): boolean => {
    try {
      const r = spawnSync(cmd, args, { encoding: 'utf8', timeout: 120000, windowsHide: true })
      return r.status === 0
    } catch {
      return false
    }
  }
  if (lower.endsWith('.zip')) {
    for (const bin of ['/usr/bin/unzip', '/usr/local/bin/unzip', 'unzip']) {
      try { if (fs.existsSync(bin)) { if (tryRun(bin, ['-q', '-o', file, '-d', dest])) return true } } catch { /* ignore */ }
    }
    // Windows 10+ 自带 bsdtar，支持 zip
    if (tryRun('tar', ['-xf', file, '-C', dest])) return true
    return false
  }
  return tryRun('tar', ['-xzf', file, '-C', dest])
}

// ===== 清理 =====

function dirSize(dir: string): number {
  let total = 0
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop() as string
    let entries: fs.Dirent[] = []
    try { entries = fs.readdirSync(d, { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      const full = path.join(d, e.name)
      if (e.isDirectory()) stack.push(full)
      else {
        try { total += fs.statSync(full).size } catch { /* ignore */ }
      }
    }
  }
  return total
}

export async function cleanupGitSource(baseDir: string, opts?: { all?: boolean }): Promise<{ success: boolean; freed?: number; error?: string }> {
  try {
    const root = gitRoot(baseDir)
    if (!fs.existsSync(root)) return { success: true, freed: 0 }
    const target = opts?.all === false ? path.join(root, 'wt') : root
    const freed = dirSize(target)
    // worktree 需要先注销，否则主仓库的 .git/worktrees 会残留
    const cap = detectGit()
    if (cap.available && cap.bin) {
      const cacheRoot = path.join(root, 'cache')
      if (fs.existsSync(cacheRoot)) {
        for (const d of fs.readdirSync(cacheRoot)) {
          const repo = path.join(cacheRoot, d, 'repo')
          if (fs.existsSync(path.join(repo, 'HEAD'))) {
            await runGit(cap.bin, ['-C', repo, 'worktree', 'prune'])
          }
        }
      }
      const wtRoot = path.join(root, 'wt')
      if (fs.existsSync(wtRoot)) {
        for (const d of fs.readdirSync(wtRoot)) {
          for (const r of fs.readdirSync(path.join(wtRoot, d))) {
            await runGit(cap.bin, ['-C', path.join(wtRoot, d, r), 'worktree', 'prune']).catch(() => undefined)
          }
        }
      }
    }
    fs.rmSync(target, { recursive: true, force: true })
    repoLocks.clear()
    return { success: true, freed }
  } catch (e: any) {
    return { success: false, error: String(e?.message ?? e) }
  }
}
