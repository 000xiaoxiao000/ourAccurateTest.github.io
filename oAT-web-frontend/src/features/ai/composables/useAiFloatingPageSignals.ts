import { ref, type Ref } from 'vue'

export type AiFloatingLiveSignals = {
  filters: string[]
  tableHover: string
  tableSelection: string
}

export type AiFloatingSignalId = 'filter' | 'hover' | 'selection'

export function useAiFloatingPageSignals(rootRef: Ref<HTMLElement | null>) {
  const liveSignals = ref<AiFloatingLiveSignals>({ filters: [], tableHover: '', tableSelection: '' })
  let hoveredRowEl: HTMLElement | null = null
  let selectedRowEl: HTMLElement | null = null

  function collectLiveFilterState() {
    const filters: string[] = []
    const selector = 'input[type="text"], input[type="search"], input:not([type]), select, textarea'
    for (const element of Array.from(document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(selector))) {
      if (rootRef.value?.contains(element)) continue
      const value = String(element.value || '').trim()
      if (!value || value.length > 24) continue
      const placeholder = element.getAttribute('placeholder') || element.getAttribute('name') || element.getAttribute('aria-label') || ''
      const item = placeholder.trim() ? `${placeholder.trim()}=${value}` : value
      if (!filters.includes(item)) filters.push(item)
      if (filters.length >= 4) break
    }
    liveSignals.value = { ...liveSignals.value, filters }
  }

  function summarizeRow(row: HTMLElement) {
    const cells: string[] = []
    for (const cell of Array.from(row.querySelectorAll('td'))) {
      const text = (cell.textContent || '').replace(/\s+/g, ' ').trim()
      if (text && !cells.includes(text)) cells.push(text)
      if (cells.length >= 4) break
    }
    return cells.join(' | ')
  }

  function findTableRow(target: EventTarget | null) {
    const element = target instanceof HTMLElement ? target : null
    const row = element?.closest('table tbody tr, .ui.table tbody tr') as HTMLElement | null
    if (!row || rootRef.value?.contains(row)) return null
    return row
  }

  function onDocumentInput(event: Event) {
    if (rootRef.value?.contains(event.target as Node)) return
    collectLiveFilterState()
  }

  function onDocumentMouseOver(event: MouseEvent) {
    const row = findTableRow(event.target)
    if (!row || row === hoveredRowEl) return
    if (hoveredRowEl && hoveredRowEl !== selectedRowEl) hoveredRowEl.classList.remove('ai-floating-row-hover')
    hoveredRowEl = row
    if (hoveredRowEl !== selectedRowEl) hoveredRowEl.classList.add('ai-floating-row-hover')
    liveSignals.value = { ...liveSignals.value, tableHover: summarizeRow(row) }
  }

  function onDocumentMouseOut(event: MouseEvent) {
    const row = findTableRow(event.target)
    if (!row) return
    const related = event.relatedTarget instanceof Node ? event.relatedTarget : null
    if (related && row.contains(related)) return
    if (row !== selectedRowEl) row.classList.remove('ai-floating-row-hover')
    if (hoveredRowEl === row) {
      hoveredRowEl = null
      liveSignals.value = { ...liveSignals.value, tableHover: '' }
    }
  }

  function onDocumentClick(event: MouseEvent) {
    const row = findTableRow(event.target)
    if (!row) return
    selectedRowEl?.classList.remove('ai-floating-row-selected', 'ai-floating-row-hover')
    selectedRowEl = row
    selectedRowEl.classList.add('ai-floating-row-selected')
    liveSignals.value = { ...liveSignals.value, tableSelection: summarizeRow(row) }
  }

  function clearSignal(id: AiFloatingSignalId) {
    if (id === 'filter') {
      liveSignals.value = { ...liveSignals.value, filters: [] }
      return
    }
    if (id === 'hover') {
      hoveredRowEl?.classList.remove('ai-floating-row-hover')
      hoveredRowEl = null
      liveSignals.value = { ...liveSignals.value, tableHover: '' }
      return
    }
    selectedRowEl?.classList.remove('ai-floating-row-selected', 'ai-floating-row-hover')
    selectedRowEl = null
    liveSignals.value = { ...liveSignals.value, tableSelection: '' }
  }

  function clearLiveRowState() {
    hoveredRowEl?.classList.remove('ai-floating-row-hover')
    selectedRowEl?.classList.remove('ai-floating-row-selected', 'ai-floating-row-hover')
    hoveredRowEl = null
    selectedRowEl = null
    liveSignals.value = { ...liveSignals.value, tableHover: '', tableSelection: '' }
  }

  function bindLivePageSignals() {
    document.addEventListener('input', onDocumentInput, true)
    document.addEventListener('change', onDocumentInput, true)
    document.addEventListener('mouseover', onDocumentMouseOver, true)
    document.addEventListener('mouseout', onDocumentMouseOut, true)
    document.addEventListener('click', onDocumentClick, true)
    collectLiveFilterState()
  }

  function unbindLivePageSignals() {
    document.removeEventListener('input', onDocumentInput, true)
    document.removeEventListener('change', onDocumentInput, true)
    document.removeEventListener('mouseover', onDocumentMouseOver, true)
    document.removeEventListener('mouseout', onDocumentMouseOut, true)
    document.removeEventListener('click', onDocumentClick, true)
  }

  function buildPageContext(currentQuestion: string, routeFullPath: string) {
    const parts = [`route=${routeFullPath}`]
    const pageTitle = document.title.trim()
    if (pageTitle) parts.push(`页面标题:${pageTitle}`)
    const headers = collectTexts('th, .ui.table thead th', 6)
    if (headers.length) parts.push(`表格字段:${headers.join('、')}`)
    const filterLabels = collectTexts('input[placeholder], textarea[placeholder], select, .search input[placeholder]', 6)
    if (filterLabels.length) parts.push(`筛选线索:${filterLabels.join('、')}`)
    const actionTexts = collectTexts('button, a, .menu .item', 8)
    if (actionTexts.length) parts.push(`可操作项:${actionTexts.join('、')}`)
    if (liveSignals.value.filters.length) parts.push(`当前筛选:${liveSignals.value.filters.join('、')}`)
    if (liveSignals.value.tableSelection) parts.push(`当前选中行:${liveSignals.value.tableSelection}`)
    if (liveSignals.value.tableHover) parts.push(`当前悬停行:${liveSignals.value.tableHover}`)
    if (currentQuestion) parts.push(`当前问题:${currentQuestion}`)
    return parts.join('；')
  }

  function collectTexts(selector: string, limit: number) {
    const values: string[] = []
    for (const element of Array.from(document.querySelectorAll(selector))) {
      if (rootRef.value?.contains(element)) continue
      const text = ((element.getAttribute('placeholder') || element.textContent || '') as string).replace(/\s+/g, ' ').trim()
      if (text && !values.includes(text)) values.push(text.slice(0, 48))
      if (values.length >= limit) break
    }
    return values
  }

  return {
    liveSignals,
    bindLivePageSignals,
    buildPageContext,
    clearLiveRowState,
    clearSignal,
    collectLiveFilterState,
    unbindLivePageSignals,
  }
}
