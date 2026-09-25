/**
 * 在线探针：发现 + 探活 + 识别（xiaoxiao-jacoco-agent 的 tcpserver）。
 *
 * ⚠️ 本文件故意 **不 import electron**，以便纯 Node 直接跑（esbuild 打包成 cjs 后可 require 单测）。
 *
 * ## 为什么必须自己实现协议
 * agent 的 tcpserver 是 JaCoCo **自定义二进制 RemoteControl 协议（非 HTTP）**，没有健康检查接口，
 * 更没有 REST。所谓「在线」只能自己定义，三层判定：
 *   L1 传输层：TCP 能连上 → 端口在监听（可能是 Redis，也可能是 IDE）
 *   L2 协议层：回 exec 头 `0x01 C0C0 <version>` → 确定是 JaCoCo 系
 *   L3 能力层：回私有 `0x22` stats 块 → 是 xiaoxiao 探针（有全套自检计数）；
 *              只回 `0x10` sessioninfo → 官方 JaCoCo（可 dump，无诊断能力，降级展示）
 *
 * ## 两个必须遵守的坑（实测踩出来的）
 * 1. **握手必须客户端先写 exec 头**（0x01 + 0xC0C0 + version 0x1007），再写命令块。
 *    漏了头服务端会报 "Invalid execution data file"，并按「连上不发命令」回退成默认 dump。
 * 2. **禁止裸 TCP 探活**：TcpServerOutput 单线程 accept + 串行 handle、READ_TIMEOUT=5s，
 *    裸连要占满 5 秒才释放，期间后续连接全排队 → 探针会被误判成「静默端口」。
 *    正确做法：connect 成功后立刻写头+命令，一次连接完成判定，拿到数据即刻 destroy。
 */
import net from 'net'
import { spawn } from 'child_process'

// ===== 协议常量 =====
/** exec 文件头：0x01 + 0xC0C0(2B) + FORMAT_VERSION 0x1007(2B) */
const EXEC_HEADER = Buffer.from([0x01, 0xc0, 0xc0, 0x10, 0x07])
const BLOCK_HEADER = 0x01
const BLOCK_SESSIONINFO = 0x10
const BLOCK_CMDOK = 0x20
const BLOCK_KEYS = 0x21
const BLOCK_STATS = 0x22
/** peruser.PerKeyProtocol：客户端请求运行期概况，payload = int limit */
const CMD_STATS = 0x43

// ===== 类型 =====

/** 已登记的探针（持久化在 capture-config.json 的 coverage.agents） */
export interface ProbeAgent {
  id: string
  host: string
  port: number
  label?: string
}

/** 本机扫描候选（来自 lsof，未登记，每次扫都可能变） */
export interface LocalCandidate {
  pid: string
  command: string
  host: string
  port: number
}

/** 探针自检计数（PerKeyProtocol.sendKeys / sendStats 里的 Diagnostics） */
export interface ProbeDiagnostics {
  /** 探针见过的类总数 */
  classesSeen: number
  /** 实际插桩的类数 */
  classesInstrumented: number
  /** HTTP 入口钩子被触发的次数 */
  requestsHooked: number
  /** 成功读到归属 key 的请求次数 */
  requestsTagged: number
  /** 因无 source location 被跳过的类数 */
  classesNoLocation: number
  /** 按类名前缀推荐给用户的 includes */
  suggestedIncludes: string[]
  /** 已插桩类名样例（受 limit 限制） */
  instrumentedSample: string[]
  /** 被跳过的类名样例 */
  skippedSample: string[]
}

/** 每个 key 的规模（ThreadProbeStore.KeyStat） */
export interface ProbeKeyStat {
  key: string
  classes: number
  probes: number
  covered: number
}

/** 四态色语义：绿 / 黄 / 蓝 / 灰 */
export type ProbeStatus = 'online' | 'warning' | 'vanilla' | 'offline'

export interface ProbeResult {
  id: string
  host: string
  port: number
  agentAddress: string
  /** 展示名：登记标签 > 本机进程名 > host:port */
  label: string
  source: 'config' | 'registered' | 'discovered'
  status: ProbeStatus
  /** 握手往返耗时（ms） */
  ms: number
  pingedAt: number
  /** 人话归因，尤其是离线时区分 拒连 / 超时 / 非探针端口 */
  note: string
  diag?: ProbeDiagnostics
  keyStats?: ProbeKeyStat[]
  keys?: string[]
  /** 本机扫描到的进程 PID（远端来源为空） */
  pid?: string
  /** 是否 xiaoxiao 探针（带自检计数）；false = 官方 JaCoCo 降级 */
  hasDiagnostics?: boolean
}

export interface ProbeSummary {
  total: number
  online: number
  warning: number
  vanilla: number
  offline: number
  checkedAt: number
}

/** 一次握手的原始结果 */
type HandshakeOutcome =
  | { kind: 'stats'; ms: number; diag: ProbeDiagnostics; keyStats: ProbeKeyStat[] }
  | { kind: 'vanilla'; ms: number }
  | { kind: 'notjacoco'; ms: number; note: string }
  | { kind: 'silent'; ms: number }
  | { kind: 'refused'; ms: number }
  | { kind: 'timeout'; ms: number }
  | { kind: 'error'; ms: number; note: string }

// ===== 二进制读取（tolerant：读到一半就 EOF 时按旧版处理，不抛异常） =====

/** 带边界检查的顺序读取器：越界返回 0/空串并置 exhausted 标志 */
class Reader {
  private b: Buffer
  private p: number
  exhausted = false
  constructor(b: Buffer, p: number) {
    this.b = b
    this.p = p
  }
  get pos(): number {
    return this.p
  }
  remaining(): number {
    return this.b.length - this.p
  }
  private need(n: number): boolean {
    if (this.p + n <= this.b.length) return true
    this.exhausted = true
    return false
  }
  byte(): number {
    if (!this.need(1)) return 0
    return this.b[this.p++]
  }
  int(): number {
    if (!this.need(4)) return 0
    const v = this.b.readInt32BE(this.p)
    this.p += 4
    return v
  }
  long(): number {
    if (!this.need(8)) return 0
    const v = this.b.readBigInt64BE(this.p)
    this.p += 8
    return Number(v)
  }
  /** Java DataOutput.writeUTF：2 字节长度 + modified UTF-8 */
  utf(): string {
    if (!this.need(2)) return ''
    const len = this.b.readUInt16BE(this.p)
    this.p += 2
    if (len === 0) return ''
    if (!this.need(len)) return ''
    const s = this.b.subarray(this.p, this.p + len).toString('utf8')
    this.p += len
    return s
  }
  utfList(n: number): string[] {
    const out: string[] = []
    for (let i = 0; i < n; i++) {
      const s = this.utf()
      if (this.exhausted) break
      out.push(s)
    }
    return out
  }
}

// ===== 握手 =====

/**
 * 与 agent 完成一次「写头 + 写命令 + 读响应」，并在读到数据后立刻销毁连接。
 * 注意：不能先做裸 TCP 探活再握手——agent 是串行 handle，裸连会占满 5s 读超时。
 */
function handshake(host: string, port: number, timeoutMs: number): Promise<HandshakeOutcome> {
  const started = Date.now()
  const elapsed = () => Date.now() - started
  return new Promise((resolve) => {
    const socket = new net.Socket()
    const chunks: Buffer[] = []
    let settled = false
    let closed = false

    const done = (r: HandshakeOutcome) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolve(r)
    }

    socket.setTimeout(timeoutMs)
    socket.on('connect', () => {
      // ⚠️ 必须一次写完「exec 头 + stats 命令 + limit」，分多次写风险更高（且没必要）
      socket.write(Buffer.concat([EXEC_HEADER, Buffer.from([CMD_STATS]), int32(50)]))
    })
    socket.on('data', (d) => chunks.push(d as Buffer))
    socket.on('timeout', () => {
      // ⚠️ 区分「能连上但对方一字节都没回」与「连不上」：前者几乎肯定不是探针（官方网站 sans HTTP 的
      // 各类服务会等客户端先发请求），后者才是网络问题。两者都判离线，但归因完全不同。
      if (chunks.length === 0) done({ kind: 'silent', ms: elapsed() })
      else done({ kind: 'timeout', ms: elapsed() })
    })
    socket.on('error', (e: NodeJS.ErrnoException) => {
      if (e.code === 'ECONNREFUSED') done({ kind: 'refused', ms: elapsed() })
      else if (e.code === 'ETIMEDOUT' || e.code === 'EHOSTUNREACH' || e.code === 'ENETUNREACH') done({ kind: 'timeout', ms: elapsed() })
      else done({ kind: 'error', ms: elapsed(), note: e.code ? `${e.code}: ${e.message}` : e.message })
    })
    // 服务端写完就直接 close socket（ConnectionHandler.finally），所以 end 是正常终点
    socket.on('end', () => {
      closed = true
      done(parseResponse(Buffer.concat(chunks), elapsed()))
    })
    socket.on('close', () => {
      if (settled) return
      if (closed) return
      // 读完了但没 end（少见）：仍尝试解析
      done(parseResponse(Buffer.concat(chunks), elapsed()))
    })
    socket.connect({ host, port })
  })
}

function int32(n: number): Buffer {
  const b = Buffer.alloc(4)
  b.writeInt32BE(n, 0)
  return b
}

function parseResponse(buf: Buffer, ms: number): HandshakeOutcome {
  if (buf.length < 5) {
    return { kind: 'notjacoco', ms, note: '有服务在监听，但返回的不是 JaCoCo exec 协议（响应不足 5 字节）' }
  }
  // exec 头：0x01 + 0xC0C0(2B) + version(2B)
  if (buf[0] !== BLOCK_HEADER || buf[1] !== 0xc0 || buf[2] !== 0xc0) {
    return { kind: 'notjacoco', ms, note: '端口在监听，但不是 JaCoCo 探针（未返回 exec 文件头）' }
  }

  const r = new Reader(buf, 5)
  let diag: ProbeDiagnostics | undefined
  let keyStats: ProbeKeyStat[] | undefined

  while (r.remaining() > 0 && !r.exhausted) {
    const type = r.byte()
    if (r.exhausted) break
    if (type === BLOCK_STATS) {
      diag = {
        classesSeen: r.long(),
        classesInstrumented: r.long(),
        classesNoLocation: r.long(),
        requestsHooked: r.long(),
        requestsTagged: r.long(),
        instrumentedSample: r.utfList(r.int()),
        skippedSample: r.utfList(r.int()),
        suggestedIncludes: r.utfList(r.int())
      }
      const n = r.int()
      keyStats = []
      for (let i = 0; i < n; i++) {
        const key = r.utf()
        if (r.exhausted) break
        keyStats.push({ key, classes: r.int(), probes: r.int(), covered: r.int() })
      }
      break
    }
    if (type === BLOCK_KEYS) {
      // 兼容：若将来改用 0x42，这里也能解（不含 perKey 覆盖数）
      const keys = r.utfList(r.int())
      diag = {
        classesSeen: r.long(),
        classesInstrumented: r.long(),
        requestsHooked: r.long(),
        requestsTagged: r.long(),
        classesNoLocation: r.long(),
        instrumentedSample: [],
        skippedSample: [],
        suggestedIncludes: r.utfList(r.int())
      }
      keyStats = keys.map((k) => ({ key: k, classes: 0, probes: 0, covered: 0 }))
      break
    }
    if (type === BLOCK_SESSIONINFO) {
      // 只回 sessioninfo = 服务端没认出 0x43（官方 JaCoCo），按默认 dump 处理
      return { kind: 'vanilla', ms }
    }
    if (type === BLOCK_CMDOK) break
    // 其它块（如 ExecutionData）无法在无 schema 的情况下顺序跳过，读到即收尾
    break
  }

  if (!diag) {
    return { kind: 'notjacoco', ms, note: '回的是 JaCoCo exec 流，但没有任何可识别的响应块' }
  }
  return { kind: 'stats', ms, diag, keyStats: keyStats ?? [] }
}

// ===== 单个探针的完整判定 =====

export interface ProbeOptions {
  timeoutMs?: number
  hostHint?: string
}

/** 把握手结果翻译成 UI 的四态 + 人话归因 */
function toResult(
  base: Omit<ProbeResult, 'status' | 'note' | 'ms' | 'pingedAt' | 'diag' | 'keyStats' | 'keys' | 'hasDiagnostics'>,
  outcome: HandshakeOutcome
): ProbeResult {
  const pingedAt = Date.now()
  const common = { ...base, ms: outcome.ms, pingedAt }
  switch (outcome.kind) {
    case 'stats': {
      const diag = outcome.diag
      const keys = (outcome.keyStats ?? []).map((k) => k.key)
      let status: ProbeStatus = 'online'
      let note = ''
      if (keys.length === 0) {
        status = 'warning'
        if (diag.requestsHooked > 0) {
          note =
            `探针在线，已插桩 ${diag.classesInstrumented} 个类；有 ${diag.requestsHooked} 次请求进来，但都没读到归属 Key。` +
            '最常见的原因：「采集工作台」的覆盖率采集开关没开，或请求没走 oAT 代理。开关打开、发一次请求后这里就会开始计数'
        } else {
          note =
            `探针在线，已插桩 ${diag.classesInstrumented} 个类，但还没有请求进来。` +
            '向该服务发一次请求，这里就会开始出现归属 Key 和覆盖计数'
        }
      } else {
        note = `探针在线，已采集 ${keys.length} 个 Key`
      }
      return { ...common, status, note, diag, keyStats: outcome.keyStats, keys, hasDiagnostics: true }
    }
    case 'vanilla':
      return {
        ...common,
        status: 'vanilla',
        hasDiagnostics: false,
        note: '官方 JaCoCo agent：可以 dump，但不认识 xiaoxiao 的扩展块，拿不到自检计数与 key 列表'
      }
    case 'notjacoco':
      return { ...common, status: 'offline', note: outcome.note }
    case 'silent':
      return {
        ...common,
        status: 'offline',
        note: '能连上但对方一字节都没回 —— 大概率不是 JaCoCo 探针（探针会在连接建立后立即回 exec 头）；也可能该端口被别的服务占用'
      }
    case 'refused':
      return { ...common, status: 'offline', note: '连接被拒绝 —— 进程已退出，或没有监听这个端口（跨机的话检查被测机是否 publish 了该端口）' }
    case 'timeout':
      return { ...common, status: 'offline', note: '连接超时 —— 网络不通、防火墙拦截，或 tcpserver 正被上一次连接占着（5s 读超时）' }
    case 'error':
      return { ...common, status: 'offline', note: '连接失败：' + outcome.note }
  }
}

export async function probeOne(host: string, port: number, meta: Partial<ProbeResult> & { timeoutMs?: number } = {}): Promise<ProbeResult> {
  const { timeoutMs = 2000, ...rest } = meta
  const base = {
    id: rest.id ?? `${host}:${port}`,
    host,
    port,
    agentAddress: rest.agentAddress ?? `${host}:${port}`,
    label: rest.label ?? `${host}:${port}`,
    source: rest.source ?? 'registered',
    ...(rest.pid ? { pid: rest.pid } : {})
  } as Omit<ProbeResult, 'status' | 'note' | 'ms' | 'pingedAt' | 'diag' | 'keyStats' | 'keys' | 'hasDiagnostics'>
  // ⚠️ 同一 agent 不能并发连（串行 handle），跨 agent 才可以并发
  const outcome = await handshake(host, port, timeoutMs)
  return toResult(base, outcome)
}

/** 并发探测多个探针（不同地址之间并行，同地址只会出现一次） */
export async function probeMany(
  list: Array<ProbeAgent & { source?: ProbeResult['source']; pid?: string; label?: string }>,
  opts: { timeoutMs?: number; concurrency?: number; onProgress?: (done: number, total: number) => void } = {}
): Promise<ProbeResult[]> {
  const { timeoutMs = 1500, concurrency = 6 } = opts
  const out: ProbeResult[] = []
  let idx = 0
  const worker = async () => {
    while (idx < list.length) {
      const item = list[idx++]
      const res = await probeOne(item.host, item.port, {
        id: item.id || `${item.host}:${item.port}`,
        label: item.label,
        source: item.source ?? 'registered',
        timeoutMs,
        ...(item.pid ? { pid: item.pid } : {})
      })
      out.push(res)
      opts.onProgress?.(out.length, list.length)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, list.length)) }, () => worker()))
  return out
}

// ===== 本机发现 =====

const LSOF_CANDIDATES = ['/usr/sbin/lsof', '/usr/bin/lsof', '/usr/local/bin/lsof', 'lsof']

function runCmd(cmd: string, args: string[], timeoutMs: number): Promise<{ code: number; stdout: string; stderr: string; notFound?: boolean }> {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { windowsHide: true })
    let so = ''
    let se = ''
    const timer = setTimeout(() => p.kill('SIGKILL'), timeoutMs)
    p.stdout.on('data', (d) => (so += d.toString()))
    p.stderr.on('data', (d) => (se += d.toString()))
    p.on('error', () => {
      clearTimeout(timer)
      resolve({ code: -1, stdout: '', stderr: '', notFound: true })
    })
    p.on('close', (code) => {
      clearTimeout(timer)
      resolve({ code: code ?? -1, stdout: so, stderr: se })
    })
  })
}

/**
 * 本机发现：列出所有 TCP LISTEN 端口，只保留 java 进程作为候选。
 * ⚠️ GUI 下 PATH 受限，所以要按绝对路径候选探测 lsof。
 * ⚠️ 容器内 / 远端机器的端口本机看不到（用户环境 docker/k8s 且无 root），所以本机发现只能作辅助，
 *    真正的多环境还得靠「手工登记」。
 */
export async function discoverLocal(): Promise<{ ok: boolean; candidates: LocalCandidate[]; error?: string }> {
  let lastErr = ''
  for (const bin of LSOF_CANDIDATES) {
    const r = await runCmd(bin, ['-nP', '-iTCP', '-sTCP:LISTEN'], 15000)
    if (r.notFound) continue
    if (r.code !== 0 && !r.stdout.trim()) {
      lastErr = r.stderr.trim() || `lsof 退出码 ${r.code}`
      continue
    }
    const candidates: LocalCandidate[] = []
    const seen = new Set<string>()
    for (const line of r.stdout.split('\n')) {
      const cols = line.trim().split(/\s+/)
      if (cols.length < 9) continue
      const [command, pid, , , , , , , name] = cols
      if (!command || command === 'COMMAND') continue
      // 只要 Java 进程：探针必定挂在 JVM 上
      if (!command.toLowerCase().includes('java')) continue
      // NAME 形如 127.0.0.1:16300 / *:16300 / [::1]:16300
      const portStr = name.includes(':') ? name.slice(name.lastIndexOf(':') + 1) : ''
      const port = Number(portStr)
      if (!Number.isInteger(port) || port <= 0 || port > 65535) continue
      if (name.startsWith('[') || name.startsWith('*')) continue // IPv6 / 通配监听：本机自连优先 IPv4 显式地址
      const host = name.slice(0, name.lastIndexOf(':'))
      const hostClean = host.replace(/[[\]]/g, '')
      const key = `${pid}:${port}:${hostClean}`
      if (seen.has(key)) continue
      seen.add(key)
      candidates.push({ pid, command, host: hostClean || '127.0.0.1', port })
    }
    return { ok: true, candidates }
  }
  return { ok: false, candidates: [], error: lastErr || '系统没有 lsof，无法扫描本机端口；可手工登记探针地址' }
}

// ===== 远端 / 网段发现 =====

/** 端口表达式：支持「6300-6310」区间与「6300,8899」列表，可混写 */
export function parsePorts(expr: string): number[] {
  const out = new Set<number>()
  for (const part of String(expr ?? '').split(/[,，\s]+/)) {
    const t = part.trim()
    if (!t) continue
    const m = t.match(/^(\d+)\s*[-~]\s*(\d+)$/)
    if (m) {
      let a = Number(m[1])
      let b = Number(m[2])
      if (a > b) [a, b] = [b, a]
      if (b - a > 5000) b = a + 5000 // 防止手滑填 1-65535 打满全网段
      for (let p = a; p <= b; p++) if (p > 0 && p < 65536) out.add(p)
      continue
    }
    const n = Number(t)
    if (Number.isInteger(n) && n > 0 && n < 65536) out.add(n)
  }
  return [...out].sort((x, y) => x - y)
}

function ipToInt(ip: string): number | null {
  const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
  if (!m) return null
  const parts = m.slice(1).map(Number)
  if (parts.some((n) => n > 255)) return null
  return ((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3]
}

function intToIp(n: number): string {
  return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.')
}

/** 单个主机 token 展开：IP / 域名原样；`192.0.2.0/24` 这类 CIDR 展开成逐个 IP（只支持 /8~/30） */
export function expandHost(token: string): { hosts: string[]; error?: string } {
  const t = String(token ?? '').trim()
  if (!t) return { hosts: [] }
  if (!t.includes('/')) return { hosts: [t] }
  const [ip, bitsRaw] = t.split('/')
  const base = ipToInt(ip.trim())
  const bits = Number(bitsRaw)
  if (base == null || !Number.isInteger(bits) || bits < 8 || bits > 30) {
    return { hosts: [], error: `无法识别的网段：${t}（只支持 IPv4 CIDR，掩码 8~30，如 192.0.2.0/24）` }
  }
  const size = 2 ** (32 - bits)
  const start = (base & (size === 1 ? 0xffffffff : (-size >>> 0))) >>> 0
  const hosts: string[] = []
  for (let i = 0; i < size; i++) hosts.push(intToIp((start + i) >>> 0))
  // .0 网络号与 .255 广播通常不是主机，但测试环境也可能真用，保留（扫描成本很低）
  return { hosts }
}

export interface RangeScanTarget {
  host: string
  port: number
}

/**
 * 把「主机列表 × 端口表达式」展开成探测目标。
 * ⚠️ 必须设上限：扫爆整个 B 类网段会把用户机器和被测网络都拖垮。
 */
export function expandTargets(hostExpr: string, portExpr: string, cap = 1024): { targets: RangeScanTarget[]; truncated: boolean; error?: string } {
  const hostTokens = String(hostExpr ?? '').split(/[,，;\s]+/).filter(Boolean)
  const ports = parsePorts(portExpr)
  const targets: RangeScanTarget[] = []
  const seen = new Set<string>()
  let error: string | undefined
  for (const token of hostTokens) {
    const { hosts, error: e } = expandHost(token)
    if (e) { error = e; continue }
    for (const host of hosts) {
      for (const port of ports) {
        const k = `${host}:${port}`
        if (seen.has(k)) continue
        seen.add(k)
        targets.push({ host, port })
        if (targets.length >= cap) return { targets, truncated: true, error }
      }
    }
  }
  if (!ports.length) error = error || '端口表达式没有解析出任何端口（示例：6300-6310 或 8899,6300）'
  if (!hostTokens.length) error = error || '请填写被测机 IP / 域名，或网段（示例：192.0.2.0/24）'
  return { targets, truncated: false, error }
}

export interface RangeScanResult {
  probes: ProbeResult[]
  scanned: number
  hits: number
  truncated: boolean
  error?: string
}

/**
 * 批量探测一个网段 / 一组主机上的端口，只返回「握手确认是探针」的（在线/未采集/官方 JaCoCo）。
 * 判定靠探针协议本身（连上 → 回 exec 头），所以不需要知道对方跑的是什么服务：
 * 普通 HTTP/MySQL/redis 端口会被自然淘汰，只有 JaCoCo tcpserver 会被留下。
 */
export async function scanRange(opts: {
  hostExpr: string
  portExpr: string
  timeoutMs?: number
  concurrency?: number
  cap?: number
  onProgress?: (done: number, total: number) => void
}): Promise<RangeScanResult> {
  const { targets, truncated, error } = expandTargets(opts.hostExpr, opts.portExpr, opts.cap ?? 1024)
  if (!targets.length) return { probes: [], scanned: 0, hits: 0, truncated, error }
  const all = await probeMany(
    targets.map((t) => ({ id: `scan-${t.host}:${t.port}`, host: t.host, port: t.port, source: 'discovered' as const })),
    { timeoutMs: opts.timeoutMs ?? 1200, concurrency: opts.concurrency ?? 24, onProgress: opts.onProgress }
  )
  const hits = all.filter((r) => r.status !== 'offline')
  return { probes: sortProbes(hits), scanned: targets.length, hits: hits.length, truncated, error }
}

// ===== 摘要 =====

export function summarize(results: ProbeResult[]): ProbeSummary {
  const s: ProbeSummary = { total: results.length, online: 0, warning: 0, vanilla: 0, offline: 0, checkedAt: Date.now() }
  for (const r of results) s[r.status]++
  return s
}

/** 「几个在线」的排序：绿 > 黄 > 蓝 > 灰，便于列表稳定展示 */
const STATUS_RANK: Record<ProbeStatus, number> = { online: 0, warning: 1, vanilla: 2, offline: 3 }
export function sortProbes(list: ProbeResult[]): ProbeResult[] {
  return [...list].sort((a, b) => STATUS_RANK[a.status] - STATUS_RANK[b.status] || a.port - b.port)
}
