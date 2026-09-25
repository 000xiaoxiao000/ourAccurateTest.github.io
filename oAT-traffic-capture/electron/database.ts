import * as Database from 'better-sqlite3'
import * as path from 'node:path'
import { app } from 'electron'
import type { CaptureSession, TrafficFilterRule, TrafficRecord } from './types.js'

let db: Database.Database | null = null

function getDb(): Database.Database {
  if (!db) {
    throw new Error('Database has not been initialized')
  }
  return db
}

export function initDatabase(): void {
  const dbPath = path.join(app.getPath('userData'), 'traffic.db')
  db = new Database(dbPath)
  // language=SQLite
  db.exec(`
    CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      case_name TEXT NOT NULL,
      start_time INTEGER NOT NULL,
      end_time INTEGER
    );

    CREATE TABLE IF NOT EXISTS records (
      id TEXT PRIMARY KEY,
      session_id TEXT,
      method TEXT,
      url TEXT,
      protocol TEXT,
      status_code TEXT,
      duration INTEGER,
      timestamp INTEGER,
      request_headers TEXT,
      request_body TEXT,
      response_headers TEXT,
      response_body TEXT,
      error TEXT,
      source TEXT,
      replay_of TEXT,
      replay_status TEXT,
      replay_time INTEGER,
      tags TEXT,
      websocket_messages TEXT,
      FOREIGN KEY(session_id) REFERENCES sessions(id)
    );

    CREATE TABLE IF NOT EXISTS filter_rules (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      enabled INTEGER NOT NULL,
      target TEXT NOT NULL,
      operator TEXT NOT NULL,
      value TEXT NOT NULL,
      action TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_records_url ON records(url);
    CREATE INDEX IF NOT EXISTS idx_records_timestamp ON records(timestamp);
    CREATE INDEX IF NOT EXISTS idx_records_session ON records(session_id);
  `)
  ensureColumn('records', 'source', 'TEXT')
  ensureColumn('records', 'replay_of', 'TEXT')
  ensureColumn('records', 'replay_status', 'TEXT')
  ensureColumn('records', 'replay_time', 'INTEGER')
  ensureColumn('records', 'tags', 'TEXT')
  ensureColumn('records', 'websocket_messages', 'TEXT')
  // 影响分析：key → 接口 / 用例 反查的桥梁（探针 headerkey 注入的那个值）
  ensureColumn('records', 'coverage_key', 'TEXT')
}

/** 覆盖率 key → 该 key 期间抓到的接口与所属用例（影响分析反查用） */
export interface CoverageKeyTraffic {
  key: string
  method: string
  url: string
  protocol: string
  caseName: string
  timestamp: number
  sessionId: string | null
}

/**
 * 拉取带 coverage_key 的流量记录。
 * 用例名来自 sessions.case_name —— 抓包时 record.caseName 是空的，只有会话上有。
 */
export function loadCoverageKeyTraffic(opts: { since?: number } = {}): CoverageKeyTraffic[] {
  const rows = opts.since
    ? getDb()
        .prepare(
          // language=SQLite
          `SELECT r.coverage_key AS k, r.method, r.url, r.protocol, r.timestamp, r.session_id,
                  COALESCE(s.case_name, '') AS case_name
           FROM records r LEFT JOIN sessions s ON s.id = r.session_id
           WHERE r.coverage_key IS NOT NULL AND r.coverage_key <> '' AND r.timestamp >= ?
           ORDER BY r.timestamp DESC`
        )
        .all(opts.since) as Array<Record<string, any>>
    : getDb()
        .prepare(
          // language=SQLite
          `SELECT r.coverage_key AS k, r.method, r.url, r.protocol, r.timestamp, r.session_id,
                  COALESCE(s.case_name, '') AS case_name
           FROM records r LEFT JOIN sessions s ON s.id = r.session_id
           WHERE r.coverage_key IS NOT NULL AND r.coverage_key <> ''
           ORDER BY r.timestamp DESC`
        )
        .all() as Array<Record<string, any>>
  return rows.map((r) => ({
    key: String(r.k),
    method: r.method ?? '',
    url: r.url ?? '',
    protocol: r.protocol ?? '',
    caseName: r.case_name ?? '',
    timestamp: Number(r.timestamp) || 0,
    sessionId: r.session_id ?? null
  }))
}

/** 当前库里出现过的全部 coverage key（去重，按最近使用排序） */

/** 时间窗内的抓包记录总数（影响分析自检：区分「没抓包」和「抓了但没注入 key」） */
export function countRecords(opts: { since?: number } = {}): number {
  const row = opts.since
    ? (getDb().prepare(/* language=SQLite */ 'SELECT COUNT(*) AS c FROM records WHERE timestamp >= ?').get(opts.since) as Record<string, any>)
    : (getDb().prepare(/* language=SQLite */ 'SELECT COUNT(*) AS c FROM records').get() as Record<string, any>)
  return Number(row?.c) || 0
}
function ensureColumn(table: string, column: string, definition: string): void {
  const exists = getDb()
    .prepare(`PRAGMA table_info(${table})`)
    .all()
    .some((row: any) => row.name === column)
  if (!exists) {
    getDb().exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`)
  }
}

export function saveSession(session: Omit<CaptureSession, 'records'>): void {
  getDb()
    .prepare(/* language=SQLite */ `
      INSERT INTO sessions (id, case_name, start_time, end_time)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        case_name = excluded.case_name,
        start_time = excluded.start_time,
        end_time = excluded.end_time
    `)
    .run(session.id, session.caseName, session.startTime, session.endTime ?? null)
}

export function updateSessionEndTime(sessionId: string, endTime: number): void {
  getDb().prepare(/* language=SQLite */ 'UPDATE sessions SET end_time = ? WHERE id = ?').run(endTime, sessionId)
}

function existingSessionId(sessionId?: string): string | null {
  if (!sessionId) return null
  const row = getDb().prepare(/* language=SQLite */ 'SELECT id FROM sessions WHERE id = ?').get(sessionId) as { id: string } | undefined
  return row?.id ?? null
}

export function saveRecord(record: TrafficRecord, sessionId?: string): void {
  const persistedSessionId = existingSessionId(sessionId)
  getDb()
    .prepare(/* language=SQLite */ `
      INSERT INTO records
      (id, session_id, method, url, protocol, status_code, duration, timestamp,
       request_headers, request_body, response_headers, response_body, error,
       source, replay_of, replay_status, replay_time, tags, websocket_messages, coverage_key)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        session_id = excluded.session_id,
        method = excluded.method,
        url = excluded.url,
        protocol = excluded.protocol,
        status_code = excluded.status_code,
        duration = excluded.duration,
        timestamp = excluded.timestamp,
        request_headers = excluded.request_headers,
        request_body = excluded.request_body,
        response_headers = excluded.response_headers,
        response_body = excluded.response_body,
        error = excluded.error,
        source = excluded.source,
        replay_of = excluded.replay_of,
        replay_status = excluded.replay_status,
        replay_time = excluded.replay_time,
        tags = excluded.tags,
        websocket_messages = excluded.websocket_messages,
        coverage_key = excluded.coverage_key
    `)
    .run(
      record.id,
      persistedSessionId,
      record.method,
      record.url,
      record.protocol,
      String(record.statusCode),
      record.duration,
      record.timestamp,
      JSON.stringify(record.requestHeaders ?? {}),
      record.requestBody ?? '',
      JSON.stringify(record.responseHeaders ?? {}),
      record.responseBody ?? '',
      record.error ?? null,
      record.source ?? 'capture',
      record.replayOf ?? null,
      record.replayStatus ?? null,
      record.replayTime ?? null,
      JSON.stringify(record.tags ?? []),
      JSON.stringify(record.websocketMessages ?? []),
      record.coverageKey ?? null
    )
}

export function listSessions(): Array<{
  id: string
  case_name: string
  start_time: number
  end_time: number | null
  record_count: number
}> {
  return getDb()
    .prepare(/* language=SQLite */ `
      SELECT s.id, s.case_name, s.start_time, s.end_time, COUNT(r.id) AS record_count
      FROM sessions s
      LEFT JOIN records r ON r.session_id = s.id
      GROUP BY s.id
      ORDER BY s.start_time DESC
      LIMIT 100
    `)
    .all() as Array<{
      id: string
      case_name: string
      start_time: number
      end_time: number | null
      record_count: number
    }>
}

export function loadSessionRecords(sessionId: string): TrafficRecord[] {
  const rows = getDb()
    .prepare(/* language=SQLite */ 'SELECT * FROM records WHERE session_id = ? ORDER BY timestamp')
    .all(sessionId) as Array<Record<string, any>>

  return rows.map((row) => ({
    id: row.id,
    caseName: '',
    method: row.method,
    url: row.url,
    protocol: row.protocol,
    statusCode: row.status_code,
    duration: row.duration,
    timestamp: row.timestamp,
    requestHeaders: JSON.parse(row.request_headers || '{}'),
    requestBody: row.request_body,
    responseHeaders: JSON.parse(row.response_headers || '{}'),
    responseBody: row.response_body,
    error: row.error ?? undefined,
    source: row.source ?? 'capture',
    replayOf: row.replay_of ?? undefined,
    replayStatus: row.replay_status ?? undefined,
    replayTime: row.replay_time ?? undefined,
    tags: JSON.parse(row.tags || '[]'),
    websocketMessages: JSON.parse(row.websocket_messages || '[]'),
    coverageKey: row.coverage_key ?? undefined
  }))
}

export function deleteSession(sessionId: string): void {
  const database = getDb()
  database.prepare(/* language=SQLite */ 'DELETE FROM records WHERE session_id = ?').run(sessionId)
  database.prepare(/* language=SQLite */ 'DELETE FROM sessions WHERE id = ?').run(sessionId)
}

export function listFilterRules(): TrafficFilterRule[] {
  const rows = getDb().prepare(/* language=SQLite */ 'SELECT * FROM filter_rules ORDER BY rowid').all() as Array<Record<string, any>>
  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    enabled: Boolean(row.enabled),
    target: row.target,
    operator: row.operator,
    value: row.value,
    action: row.action
  }))
}

export function saveFilterRules(rules: TrafficFilterRule[]): void {
  const database = getDb()
  const insert = database.prepare(/* language=SQLite */ `
    INSERT INTO filter_rules (id, name, enabled, target, operator, value, action)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      name = excluded.name,
      enabled = excluded.enabled,
      target = excluded.target,
      operator = excluded.operator,
      value = excluded.value,
      action = excluded.action
  `)
  const transaction = database.transaction((items: TrafficFilterRule[]) => {
    // 有意的全表替换：先把规则清空再批量重插（前端整份提交），所以这里没有 WHERE
    database.prepare(/* language=SQLite */ 'DELETE FROM filter_rules').run()
    for (const rule of items) {
      insert.run(rule.id, rule.name, rule.enabled ? 1 : 0, rule.target, rule.operator, rule.value, rule.action)
    }
  })
  transaction(rules)
}
