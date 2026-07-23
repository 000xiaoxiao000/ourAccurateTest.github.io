import type { App, DirectiveBinding } from 'vue'

type TooltipTarget = HTMLElement | SVGElement

const DATA_TITLE = 'oatTooltipTitle'
const DATA_DIRECTIVE = 'oatTooltipDirective'
const OFFSET = 14
const MARGIN = 16

let tooltipEl: HTMLDivElement | null = null
let activeTarget: TooltipTarget | null = null
let activeText = ''
let installed = false

function ensureTooltip() {
  if (tooltipEl) return tooltipEl
  const el = document.createElement('div')
  el.className = 'oat-hover-tooltip'
  el.setAttribute('role', 'tooltip')
  document.body.appendChild(el)
  tooltipEl = el
  return el
}

function normalizeTooltipValue(value: unknown) {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

function setDirectiveTooltip(el: Element, value: unknown) {
  const text = normalizeTooltipValue(value)
  if (text) {
    ;(el as HTMLElement).dataset[DATA_DIRECTIVE] = text
    el.removeAttribute('title')
  } else {
    delete (el as HTMLElement).dataset[DATA_DIRECTIVE]
  }
}

function closestTooltipTarget(eventTarget: EventTarget | null): TooltipTarget | null {
  if (!(eventTarget instanceof Element)) return null
  const target = eventTarget.closest<HTMLElement | SVGElement>('[data-oat-tooltip-directive], [data-oat-tooltip-title], [title]')
  if (!target) return null
  const nativeTitle = target.getAttribute('title')
  if (nativeTitle) {
    target.dataset[DATA_TITLE] = nativeTitle
    target.removeAttribute('title')
  }
  return target
}

function tooltipText(target: TooltipTarget) {
  return target.dataset[DATA_DIRECTIVE] || target.dataset[DATA_TITLE] || ''
}

function showTooltip(target: TooltipTarget, event?: PointerEvent | FocusEvent | MouseEvent) {
  const text = tooltipText(target)
  if (!text) return
  activeTarget = target
  activeText = text
  const el = ensureTooltip()
  el.textContent = text
  el.classList.add('visible')
  positionTooltip(event)
}

function hideTooltip(target?: TooltipTarget | null) {
  if (target && activeTarget && target !== activeTarget) return
  if (tooltipEl) tooltipEl.classList.remove('visible')
  activeTarget = null
  activeText = ''
}

function positionTooltip(event?: PointerEvent | FocusEvent | MouseEvent) {
  if (!tooltipEl || !activeTarget || !activeText) return
  const viewportWidth = window.innerWidth
  const viewportHeight = window.innerHeight
  const maxWidth = Math.min(460, Math.max(240, viewportWidth - MARGIN * 2))
  const maxHeight = Math.min(360, Math.max(140, viewportHeight - MARGIN * 2))
  tooltipEl.style.maxWidth = `${maxWidth}px`
  tooltipEl.style.maxHeight = `${maxHeight}px`

  const anchor = event && 'clientX' in event && event.clientX
    ? { x: event.clientX, y: event.clientY }
    : (() => {
        const rect = activeTarget!.getBoundingClientRect()
        return { x: rect.left + rect.width / 2, y: rect.bottom }
      })()

  const rect = tooltipEl.getBoundingClientRect()
  const width = Math.min(rect.width || maxWidth, maxWidth)
  const height = Math.min(rect.height || maxHeight, maxHeight)
  const placeLeft = anchor.x + width + OFFSET > viewportWidth - MARGIN
  const placeAbove = anchor.y + height + OFFSET > viewportHeight - MARGIN
  const left = placeLeft
    ? Math.max(MARGIN, anchor.x - width - OFFSET)
    : Math.min(viewportWidth - width - MARGIN, anchor.x + OFFSET)
  const top = placeAbove
    ? Math.max(MARGIN, anchor.y - height - OFFSET)
    : Math.min(viewportHeight - height - MARGIN, anchor.y + OFFSET)

  tooltipEl.style.left = `${Math.round(left)}px`
  tooltipEl.style.top = `${Math.round(top)}px`
}

function handlePointerOver(event: PointerEvent) {
  const target = closestTooltipTarget(event.target)
  if (!target) return
  showTooltip(target, event)
}

function handlePointerMove(event: PointerEvent) {
  if (!activeTarget) return
  positionTooltip(event)
}

function handlePointerOut(event: PointerEvent) {
  const target = activeTarget
  if (!target) return
  const related = event.relatedTarget
  if (related instanceof Node && target.contains(related)) return
  hideTooltip(target)
}

function handleFocusIn(event: FocusEvent) {
  const target = closestTooltipTarget(event.target)
  if (!target) return
  showTooltip(target, event)
}

function handleFocusOut(event: FocusEvent) {
  hideTooltip(activeTarget)
}

function installDocumentListeners() {
  if (installed || typeof document === 'undefined') return
  installed = true
  document.addEventListener('pointerover', handlePointerOver, true)
  document.addEventListener('pointermove', handlePointerMove, true)
  document.addEventListener('pointerout', handlePointerOut, true)
  document.addEventListener('focusin', handleFocusIn, true)
  document.addEventListener('focusout', handleFocusOut, true)
  window.addEventListener('scroll', () => hideTooltip(activeTarget), true)
  window.addEventListener('resize', () => positionTooltip())
}

export function installOatTooltip(app: App) {
  app.directive('tooltip', {
    mounted(el: Element, binding: DirectiveBinding) {
      setDirectiveTooltip(el, binding.value)
    },
    updated(el: Element, binding: DirectiveBinding) {
      setDirectiveTooltip(el, binding.value)
    },
    unmounted(el: Element) {
      if (activeTarget === el) hideTooltip(el as TooltipTarget)
      delete (el as HTMLElement).dataset[DATA_DIRECTIVE]
    },
  })
  installDocumentListeners()
}
