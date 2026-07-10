import { computed, nextTick, ref, type ComputedRef, type Ref } from 'vue'

import type {
  AiFloatingLayoutBounds,
  AiFloatingLayoutItemName,
  AiFloatingLayoutRect,
  AiFloatingLayoutResizeDirection,
  AiFloatingPanelSize,
  AiFloatingPosition,
  AiFloatingSectionName,
} from '@/features/ai/types'

const LAYOUT_ITEM_META: Record<AiFloatingLayoutItemName, { minWidth: number; minHeight: number; preferredHeight: number }> = {
  messages: { minWidth: 180, minHeight: 120, preferredHeight: 190 },
  context: { minWidth: 160, minHeight: 48, preferredHeight: 68 },
  links: { minWidth: 180, minHeight: 92, preferredHeight: 150 },
  starters: { minWidth: 180, minHeight: 86, preferredHeight: 114 },
  compose: { minWidth: 220, minHeight: 122, preferredHeight: 144 },
}

type DefaultLayoutMetrics = {
  contextCount: number
  quickLinkCount: number
  starterCount: number
}

export function useAiFloatingLayout(
  rootRef: Ref<HTMLElement | null>,
  panelRef: Ref<HTMLElement | null>,
  panelOpen: Ref<boolean>,
  storagePrefix: ComputedRef<string>,
  getDefaultLayoutMetrics: () => DefaultLayoutMetrics,
) {
  const position = ref<AiFloatingPosition | null>(null)
  const panelSize = ref<AiFloatingPanelSize | null>(null)
  const collapsedSections = ref<AiFloatingSectionName[]>([])
  const layoutLocked = ref(false)
  const panelLayout = ref<Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>>>({})
  const dragMoved = ref(false)
  const layoutDragMoved = ref(false)
  let layoutUndoState: {
    position: AiFloatingPosition | null
    panelSize: AiFloatingPanelSize | null
    collapsedSections: AiFloatingSectionName[]
    panelLayout: Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>>
  } | null = null
  let resizeObserver: ResizeObserver | null = null
  let panelResizeActive = false
  let lastPanelBounds: AiFloatingLayoutBounds | null = null
  let panelResizeStartLayout: Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>> | null = null

  const floatingStyle = computed(() => position.value ? { left: `${position.value.left}px`, top: `${position.value.top}px`, right: 'auto', bottom: 'auto' } : {})
  const panelStyle = computed(() => panelSize.value ? { width: `${panelSize.value.width}px`, height: `${panelSize.value.height}px` } : {})

  function restoreLayoutState(isCurrentLayout: boolean, defaults: AiFloatingSectionName[]) {
    position.value = null
    if (storagePrefix.value) localStorage.removeItem(`${storagePrefix.value}:position`)
    panelSize.value = isCurrentLayout ? normalizePanelSize(readJson<AiFloatingPanelSize | null>(`${storagePrefix.value}:panel-size`, null)) : null
    collapsedSections.value = normalizeCollapsedSections(
      isCurrentLayout ? readJson<AiFloatingSectionName[]>(`${storagePrefix.value}:sections`, defaults) : defaults,
    )
    panelLayout.value = isCurrentLayout ? readJson<Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>>>(`${storagePrefix.value}:panel-layout`, {}) : {}
    layoutLocked.value = localStorage.getItem(`${storagePrefix.value}:layout-locked`) === '1'
  }

  function clearStoredLayout() {
    if (!storagePrefix.value) return
    localStorage.removeItem(`${storagePrefix.value}:position`)
    localStorage.removeItem(`${storagePrefix.value}:panel-size`)
    localStorage.removeItem(`${storagePrefix.value}:sections`)
    localStorage.removeItem(`${storagePrefix.value}:panel-layout`)
  }

  function normalizeCollapsedSections(value: AiFloatingSectionName[]) {
    const allowed: AiFloatingSectionName[] = ['messages', 'links', 'starters']
    const next = value.filter((item): item is AiFloatingSectionName => allowed.includes(item))
    return next.length ? Array.from(new Set(next)) : []
  }

  function readJson<T>(key: string, fallback: T): T {
    try {
      const raw = localStorage.getItem(key)
      return raw ? JSON.parse(raw) as T : fallback
    } catch {
      return fallback
    }
  }

  function normalizePanelSize(size: AiFloatingPanelSize | null): AiFloatingPanelSize | null {
    if (!size) return null
    const bounds = getPanelBounds()
    return {
      width: Math.min(Math.max(size.width, bounds.minWidth), bounds.maxWidth),
      height: Math.min(Math.max(size.height, bounds.minHeight), bounds.maxHeight),
    }
  }

  function getPanelBounds() {
    const launcherWidth = 96
    const maxWidth = Math.max(420, window.innerWidth - launcherWidth - 32)
    const maxHeight = Math.max(460, window.innerHeight - 32)
    return {
      minWidth: Math.min(420, maxWidth),
      maxWidth,
      minHeight: Math.min(460, maxHeight),
      maxHeight,
    }
  }

  function setPanelOpenWithAnchor(value: boolean) {
    if (panelOpen.value === value) return
    const anchor = getFloatingAnchor()
    panelOpen.value = value
    preserveFloatingAnchor(anchor)
  }

  function preserveCurrentAnchor(update: () => void) {
    const anchor = getFloatingAnchor()
    update()
    preserveFloatingAnchor(anchor)
  }

  function getFloatingAnchor() {
    if (!position.value) return null
    const rect = rootRef.value?.getBoundingClientRect()
    return rect ? { right: rect.right, bottom: rect.bottom } : null
  }

  function preserveFloatingAnchor(anchor: { right: number; bottom: number } | null) {
    if (!anchor) return
    nextTick(() => {
      const root = rootRef.value
      if (!root) return
      position.value = clampPosition({
        left: anchor.right - root.offsetWidth,
        top: anchor.bottom - root.offsetHeight,
      })
      if (storagePrefix.value && position.value) localStorage.setItem(`${storagePrefix.value}:position`, JSON.stringify(position.value))
    })
  }

  function isSectionCollapsed(section: AiFloatingSectionName) {
    return collapsedSections.value.includes(section)
  }

  function toggleSection(section: AiFloatingSectionName) {
    if (layoutDragMoved.value) return
    captureLayout()
    const next = new Set(collapsedSections.value)
    if (next.has(section)) next.delete(section)
    else next.add(section)
    collapsedSections.value = Array.from(next)
    if (storagePrefix.value) localStorage.setItem(`${storagePrefix.value}:sections`, JSON.stringify(collapsedSections.value))
  }

  function resetLayout() {
    captureLayout()
    position.value = null
    panelSize.value = null
    collapsedSections.value = []
    panelLayout.value = {}
    if (!storagePrefix.value) return
    localStorage.removeItem(`${storagePrefix.value}:position`)
    localStorage.removeItem(`${storagePrefix.value}:panel-size`)
    localStorage.removeItem(`${storagePrefix.value}:sections`)
    localStorage.removeItem(`${storagePrefix.value}:panel-layout`)
    lastPanelBounds = null
    nextTick(applyDefaultPanelLayout)
  }

  function resetPanelSizeFromDoubleClick(event: MouseEvent) {
    const target = event.target as HTMLElement | null
    if (target?.closest('.panel-tools, textarea, button, a, input, .section-body, .message-list, .quick-links, .starters')) return
    captureLayout()
    panelSize.value = null
    panelLayout.value = {}
    if (!storagePrefix.value) return
    localStorage.removeItem(`${storagePrefix.value}:panel-size`)
    localStorage.removeItem(`${storagePrefix.value}:panel-layout`)
    lastPanelBounds = null
    nextTick(applyDefaultPanelLayout)
  }

  function captureLayout() {
    layoutUndoState = {
      position: position.value ? { ...position.value } : null,
      panelSize: panelSize.value ? { ...panelSize.value } : null,
      collapsedSections: [...collapsedSections.value],
      panelLayout: clonePanelLayout(panelLayout.value),
    }
  }

  function undoLayout() {
    if (!layoutUndoState) return
    position.value = layoutUndoState.position
    panelSize.value = layoutUndoState.panelSize
    collapsedSections.value = layoutUndoState.collapsedSections
    panelLayout.value = clonePanelLayout(layoutUndoState.panelLayout)
    if (!storagePrefix.value) return
    if (position.value) localStorage.setItem(`${storagePrefix.value}:position`, JSON.stringify(position.value))
    else localStorage.removeItem(`${storagePrefix.value}:position`)
    if (panelSize.value) localStorage.setItem(`${storagePrefix.value}:panel-size`, JSON.stringify(panelSize.value))
    else localStorage.removeItem(`${storagePrefix.value}:panel-size`)
    localStorage.setItem(`${storagePrefix.value}:sections`, JSON.stringify(collapsedSections.value))
    localStorage.setItem(`${storagePrefix.value}:panel-layout`, JSON.stringify(panelLayout.value))
  }

  function clonePanelLayout(layout: Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>>) {
    return Object.fromEntries(Object.entries(layout).map(([key, rect]) => [key, rect ? { ...rect } : rect])) as Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>>
  }

  function toggleLayoutLock() {
    layoutLocked.value = !layoutLocked.value
    if (storagePrefix.value) localStorage.setItem(`${storagePrefix.value}:layout-locked`, layoutLocked.value ? '1' : '0')
  }

  function startDrag(event: PointerEvent) {
    const target = event.target as HTMLElement | null
    if (target?.closest('button, a, textarea, input, select') && !target.closest('.launcher, .restore-button')) return
    const root = rootRef.value
    if (!root) return
    const rect = root.getBoundingClientRect()
    const startX = event.clientX
    const startY = event.clientY
    const startLeft = rect.left
    const startTop = rect.top
    captureLayout()
    dragMoved.value = false
    const move = (moveEvent: PointerEvent) => {
      const deltaX = moveEvent.clientX - startX
      const deltaY = moveEvent.clientY - startY
      if (Math.abs(deltaX) + Math.abs(deltaY) > 4) dragMoved.value = true
      position.value = clampPosition({ left: startLeft + deltaX, top: startTop + deltaY })
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      if (storagePrefix.value && position.value) localStorage.setItem(`${storagePrefix.value}:position`, JSON.stringify(position.value))
      window.setTimeout(() => { dragMoved.value = false }, 0)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  function clampPosition(value: AiFloatingPosition): AiFloatingPosition {
    const width = rootRef.value?.offsetWidth || (panelOpen.value ? 480 : 120)
    const height = rootRef.value?.offsetHeight || (panelOpen.value ? 640 : 120)
    const margin = 8
    return {
      left: Math.min(Math.max(value.left, margin), Math.max(margin, window.innerWidth - width - margin)),
      top: Math.min(Math.max(value.top, margin), Math.max(margin, window.innerHeight - height - margin)),
    }
  }

  function startResize(event: PointerEvent) {
    const panel = panelRef.value
    if (!panel) return
    const startX = event.clientX
    const startY = event.clientY
    const rect = panel.getBoundingClientRect()
    const startWidth = rect.width
    const startHeight = rect.height
    const startBounds = layoutBounds(panel)
    captureLayout()
    panelResizeActive = true
    panelResizeStartLayout = clonePanelLayout(panelLayout.value)
    const move = (moveEvent: PointerEvent) => {
      const bounds = getPanelBounds()
      const nextWidth = Math.min(Math.max(startWidth + moveEvent.clientX - startX, bounds.minWidth), bounds.maxWidth)
      const nextHeight = Math.min(Math.max(startHeight + moveEvent.clientY - startY, bounds.minHeight), bounds.maxHeight)
      panelSize.value = { width: Math.round(nextWidth), height: Math.round(nextHeight) }
      nextTick(() => scalePanelLayout(startBounds, layoutBounds(panel)))
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      panelResizeActive = false
      scalePanelLayout(startBounds, layoutBounds(panel))
      panelResizeStartLayout = null
      if (storagePrefix.value && panelSize.value) {
        localStorage.setItem(`${storagePrefix.value}:panel-size`, JSON.stringify(panelSize.value))
      }
      savePanelLayout()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  function installResizeObserver() {
    if (!panelRef.value || resizeObserver) return
    resizeObserver = new ResizeObserver((entries) => {
      const entry = entries[0]
      const panel = panelRef.value
      if (!entry || !panel || panelResizeActive) return
      lastPanelBounds = layoutBounds(panel)
    })
    resizeObserver.observe(panelRef.value)
  }

  function teardownResizeObserver() {
    resizeObserver?.disconnect()
    resizeObserver = null
    lastPanelBounds = null
    panelResizeStartLayout = null
    panelResizeActive = false
  }

  function layoutItemStyle(item: AiFloatingLayoutItemName) {
    const rect = panelLayout.value[item]
    return rect ? { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` } : {}
  }

  function startLayoutDrag(event: PointerEvent, item: AiFloatingLayoutItemName) {
    if (layoutLocked.value || !panelLayout.value[item]) return
    const target = event.target as HTMLElement | null
    const isSectionTitle = Boolean(target?.closest('.section-title'))
    if (target?.closest('button, a, textarea, input, select, .section-body, .message-list, .quick-links, .starters, .layout-resizer') && !isSectionTitle) return
    const panel = panelRef.value
    const rect = panelLayout.value[item]
    if (!panel || !rect) return
    if (!isSectionTitle) event.preventDefault()
    event.stopPropagation()
    captureLayout()
    const bounds = layoutBounds(panel)
    const startX = event.clientX
    const startY = event.clientY
    layoutDragMoved.value = false
    const move = (moveEvent: PointerEvent) => {
      moveEvent.preventDefault()
      if (Math.abs(moveEvent.clientX - startX) + Math.abs(moveEvent.clientY - startY) > 4) layoutDragMoved.value = true
      const next = {
        ...rect,
        left: rect.left + moveEvent.clientX - startX,
        top: rect.top + moveEvent.clientY - startY,
      }
      panelLayout.value = {
        ...panelLayout.value,
        [item]: clampLayoutRect(next, bounds),
      }
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      savePanelLayout()
      window.setTimeout(() => { layoutDragMoved.value = false }, 0)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  function startLayoutResize(event: PointerEvent, item: AiFloatingLayoutItemName, direction: AiFloatingLayoutResizeDirection) {
    if (layoutLocked.value || !panelLayout.value[item]) return
    const panel = panelRef.value
    const rect = panelLayout.value[item]
    if (!panel || !rect) return
    event.preventDefault()
    event.stopPropagation()
    captureLayout()
    const bounds = layoutBounds(panel)
    const startX = event.clientX
    const startY = event.clientY
    const move = (moveEvent: PointerEvent) => {
      const deltaX = moveEvent.clientX - startX
      const deltaY = moveEvent.clientY - startY
      const next = resizeLayoutRect(rect, direction, deltaX, deltaY)
      panelLayout.value = {
        ...panelLayout.value,
        [item]: clampLayoutRect(next, bounds, item),
      }
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      savePanelLayout()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  function resizeLayoutRect(rect: AiFloatingLayoutRect, direction: AiFloatingLayoutResizeDirection, deltaX: number, deltaY: number): AiFloatingLayoutRect {
    const next = { ...rect }
    if (direction.includes('e')) next.width = rect.width + deltaX
    if (direction.includes('s')) next.height = rect.height + deltaY
    if (direction.includes('w')) {
      next.width = rect.width - deltaX
      next.left = rect.left + deltaX
    }
    if (direction.includes('n')) {
      next.height = rect.height - deltaY
      next.top = rect.top + deltaY
    }
    return next
  }

  function layoutBounds(panel: HTMLElement) {
    const header = panel.querySelector('.panel-header') as HTMLElement | null
    const top = (header?.offsetHeight || 0) + 8
    return {
      left: 10,
      top,
      width: Math.max(0, panel.clientWidth - 20),
      height: Math.max(0, panel.clientHeight - top - 10),
    }
  }

  function clampLayoutRect(rect: AiFloatingLayoutRect, bounds: ReturnType<typeof layoutBounds>, item?: AiFloatingLayoutItemName) {
    const meta = item ? LAYOUT_ITEM_META[item] : undefined
    const minWidth = Math.min(meta?.minWidth || 160, bounds.width)
    const minHeight = Math.min(meta?.minHeight || 48, bounds.height)
    const width = Math.min(Math.max(rect.width, minWidth), bounds.width)
    const height = Math.min(Math.max(rect.height, minHeight), bounds.height)
    return {
      width,
      height,
      left: Math.min(Math.max(rect.left, bounds.left), bounds.left + bounds.width - width),
      top: Math.min(Math.max(rect.top, bounds.top), bounds.top + bounds.height - height),
    }
  }

  function scalePanelLayout(previousBounds: AiFloatingLayoutBounds | null, nextBounds: AiFloatingLayoutBounds | null) {
    if (!previousBounds || !nextBounds || !Object.keys(panelLayout.value).length) return
    const widthRatio = nextBounds.width / Math.max(1, previousBounds.width)
    const heightRatio = nextBounds.height / Math.max(1, previousBounds.height)
    const scaled: Partial<Record<AiFloatingLayoutItemName, AiFloatingLayoutRect>> = {}
    const sourceLayout = panelResizeStartLayout || panelLayout.value
    for (const item of Object.keys(sourceLayout) as AiFloatingLayoutItemName[]) {
      const rect = sourceLayout[item]
      if (!rect) continue
      scaled[item] = clampLayoutRect({
        left: nextBounds.left + (rect.left - previousBounds.left) * widthRatio,
        top: nextBounds.top + (rect.top - previousBounds.top) * heightRatio,
        width: rect.width * widthRatio,
        height: rect.height * heightRatio,
      }, nextBounds, item)
    }
    panelLayout.value = scaled
  }

  function applyDefaultPanelLayout() {
    const panel = panelRef.value
    if (!panel || Object.keys(panelLayout.value).length) return
    const bounds = layoutBounds(panel)
    const gap = 10
    const sideWidth = Math.max(180, Math.round(bounds.width * 0.34))
    const mainWidth = Math.max(200, bounds.width - sideWidth - gap)
    const composeHeight = 142
    const metrics = getDefaultLayoutMetrics()
    const contextHeight = metrics.contextCount ? 68 : 0
    const rightBottom = bounds.top + bounds.height - composeHeight - gap
    const quickRows = Math.max(1, Math.min(metrics.quickLinkCount || 2, 3))
    const starterRows = Math.max(1, Math.min(metrics.starterCount || 2, 4))
    const quickHeight = isSectionCollapsed('links') ? 38 : Math.min(210, 46 + quickRows * 52)
    const starterHeight = isSectionCollapsed('starters') ? 38 : Math.min(210, 42 + starterRows * 34)
    const messageHeight = Math.max(180, bounds.height - composeHeight - contextHeight - gap * (contextHeight ? 2 : 1))
    const contextTop = bounds.top + messageHeight + gap
    panelLayout.value = {
      messages: { left: bounds.left, top: bounds.top, width: mainWidth, height: messageHeight },
      ...(contextHeight ? { context: { left: bounds.left, top: contextTop, width: mainWidth, height: contextHeight } } : {}),
      links: { left: bounds.left + mainWidth + gap, top: bounds.top, width: sideWidth, height: quickHeight },
      starters: { left: bounds.left + mainWidth + gap, top: bounds.top + quickHeight + gap, width: sideWidth, height: starterHeight },
      compose: { left: bounds.left, top: rightBottom, width: bounds.width, height: composeHeight },
    }
    lastPanelBounds = bounds
    savePanelLayout()
  }

  function savePanelLayout() {
    if (storagePrefix.value) localStorage.setItem(`${storagePrefix.value}:panel-layout`, JSON.stringify(panelLayout.value))
  }

  return {
    applyDefaultPanelLayout,
    clearStoredLayout,
    collapsedSections,
    dragMoved,
    floatingStyle,
    installResizeObserver,
    isSectionCollapsed,
    layoutItemStyle,
    layoutLocked,
    layoutResizeDirections: ['n', 'e', 's', 'w', 'ne', 'nw', 'se', 'sw'] as AiFloatingLayoutResizeDirection[],
    panelLayout,
    panelSize,
    panelStyle,
    position,
    preserveCurrentAnchor,
    resetLayout,
    resetPanelSizeFromDoubleClick,
    restoreLayoutState,
    setPanelOpenWithAnchor,
    startDrag,
    startLayoutDrag,
    startLayoutResize,
    startResize,
    teardownResizeObserver,
    toggleLayoutLock,
    toggleSection,
    undoLayout,
  }
}
