export interface CodeLikeItem {
  id?: string
  kind?: string
  label?: string
  displayName?: string
  symbol?: string
  stableSymbolId?: string
  logicalSymbolId?: string
  locator?: string
  path?: string
  description?: string
}

export interface CodeTreeLike extends CodeLikeItem {
  children?: CodeTreeLike[]
}

const EXTERNAL_CODE_PREFIXES = [
  'java.',
  'javax.',
  'jakarta.',
  'jdk.',
  'sun.',
  'com.sun.',
  'org.springframework.',
  'org.springdoc.',
  'org.junit.',
  'org.mockito.',
  'org.apache.',
  'org.slf4j.',
  'ch.qos.',
  'com.fasterxml.',
  'com.google.',
  'io.netty.',
  'io.micrometer.',
  'reactor.',
  'lombok.',
  'kotlin.',
  'scala.',
  'groovy.',
]

// These short names are valid source-directory names (for example
// src/main/java), so only package-like prefixes should be checked as paths.
const EXTERNAL_PATH_PREFIXES = EXTERNAL_CODE_PREFIXES
  .filter((prefix) => prefix.includes('.') && !['java.', 'javax.', 'jakarta.', 'jdk.', 'sun.'].includes(prefix))
  .map((prefix) => prefix.replace(/\.$/, '').replace(/\./g, '/'))

export function isBusinessCodeItem(item: CodeLikeItem) {
  const values = [
    item.id,
    item.label,
    item.displayName,
    item.symbol,
    item.stableSymbolId,
    item.logicalSymbolId,
    item.locator,
    item.path,
    item.description,
  ].map((value) => normalizeCodeText(value)).filter(Boolean)
  if (!values.length) return true
  return !values.some(isExternalCodeText)
}

export function filterBusinessCodeTree(nodes: CodeTreeLike[]): CodeTreeLike[] {
  const result: CodeTreeLike[] = []
  for (const node of nodes) {
    const children = filterBusinessCodeTree(node.children || [])
    if (children.length || isBusinessCodeItem(node)) {
      result.push({ ...node, children })
    }
  }
  return result
}

export function isGraphCodeKind(kind?: string) {
  return [
    'SOURCE_FILE',
    'TYPE',
    'METHOD',
    'FIELD',
    'ENDPOINT',
    'CONFIG',
    'SQL_STATEMENT',
    'BASIC_BLOCK',
    'DECISION',
    'BRANCH',
    'RUNTIME_SPAN',
    'COVERAGE_UNIT',
  ].includes(String(kind || ''))
}

function isExternalCodeText(value: string) {
  const hasPathSeparators = value.includes('/') || value.includes('\\')
  const normalized = value.replace(/\\/g, '/').replace(/^\/+/, '')
  const dotted = normalized.replace(/\//g, '.')
  const externalSymbol = !hasPathSeparators && EXTERNAL_CODE_PREFIXES.some((prefix) => dotted.startsWith(prefix) || dotted.includes('.' + prefix))
  const externalPath = EXTERNAL_PATH_PREFIXES.some((prefix) => normalized.startsWith(prefix) || normalized.includes('/' + prefix + '/') || normalized.includes(prefix + '/'))
  return externalSymbol || externalPath
}

function normalizeCodeText(value: unknown) {
  return String(value ?? '').trim().toLowerCase()
}
