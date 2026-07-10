import type { TrafficFilterRule, TrafficRecord } from './types.js'

function getTargetValue(record: TrafficRecord, rule: TrafficFilterRule): string {
  if (rule.target === 'url') return record.url || ''
  if (rule.target === 'method') return record.method || ''
  if (rule.target === 'protocol') return record.protocol || ''
  if (rule.target === 'statusCode') return String(record.statusCode ?? '')
  if (rule.target === 'header') {
    return [
      ...Object.entries(record.requestHeaders ?? {}),
      ...Object.entries(record.responseHeaders ?? {})
    ].map(([key, value]) => `${key}: ${value}`).join('\n')
  }
  return [record.requestBody, record.responseBody].filter(Boolean).join('\n')
}

function matchesOperator(source: string, operator: TrafficFilterRule['operator'], expected: string): boolean {
  const value = source.toLowerCase()
  const needle = expected.toLowerCase()
  if (operator === 'contains') return value.includes(needle)
  if (operator === 'equals') return value === needle
  if (operator === 'startsWith') return value.startsWith(needle)
  if (operator === 'endsWith') return value.endsWith(needle)
  if (operator === 'regex') {
    try {
      return new RegExp(expected, 'i').test(source)
    } catch {
      return false
    }
  }
  return false
}

export function applyCaptureRules(record: TrafficRecord, rules: TrafficFilterRule[]): TrafficRecord | null {
  const enabledRules = rules.filter(rule => rule.enabled && rule.value.trim())
  let hasIncludeRules = false
  let includeMatched = false
  const tags = new Set(record.tags ?? [])

  for (const rule of enabledRules) {
    const matched = matchesOperator(getTargetValue(record, rule), rule.operator, rule.value)
    if (rule.action === 'include') {
      hasIncludeRules = true
      includeMatched = includeMatched || matched
    }
    if (matched && rule.action === 'exclude') {
      return null
    }
    if (matched && rule.action === 'mark') {
      tags.add(rule.name)
    }
  }

  if (hasIncludeRules && !includeMatched) {
    return null
  }

  return { ...record, tags: [...tags] }
}

export function createDefaultRule(): TrafficFilterRule {
  return {
    id: `rule-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name: '新规则',
    enabled: true,
    target: 'url',
    operator: 'contains',
    value: '',
    action: 'mark'
  }
}
