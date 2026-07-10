<template>
  <canvas ref="canvasRef" class="mascot-canvas" :width="size" :height="size" aria-hidden="true"></canvas>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  size?: number
  color?: string
  seed?: string
  mood?: 'happy' | 'error' | 'thinking'
  interactive?: boolean
  float?: boolean
  lookAway?: boolean
}>(), {
  size: 88,
  color: '#0f766e',
  seed: 'oat-mascot',
  mood: 'happy',
  interactive: true,
  float: true,
  lookAway: false,
})

const canvasRef = ref<HTMLCanvasElement | null>(null)

const state = {
  animationId: 0,
  mouseX: 0,
  mouseY: 0,
  pupilX: 0,
  pupilY: 0,
  bodyAngle: 0,
}

function hash(value: string) {
  let h = 2166136261
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

function random(seed: number) {
  let state = seed || 1
  return () => {
    state = (state + 0x6D2B79F5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t ^= t + Math.imul(t ^ (t >>> 7), 61 | t)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function hexToRgb(hex: string) {
  const normalized = hex.replace('#', '')
  const full = normalized.length === 3
    ? normalized.split('').map((item) => item + item).join('')
    : normalized
  return {
    r: parseInt(full.slice(0, 2), 16) || 15,
    g: parseInt(full.slice(2, 4), 16) || 118,
    b: parseInt(full.slice(4, 6), 16) || 110,
  }
}

function getLookVector(canvas: HTMLCanvasElement) {
  if (!props.interactive) {
    return { dx: 24, dy: 10 }
  }

  const rect = canvas.getBoundingClientRect()
  const dx = state.mouseX - (rect.left + rect.width / 2)
  const dy = state.mouseY - (rect.top + rect.height / 2)
  
  if (props.lookAway) {
    return { dx: -dx, dy: -dy }
  }
  
  return { dx, dy }
}

function draw() {
  const canvas = canvasRef.value
  const ctx = canvas?.getContext('2d')
  if (!canvas || !ctx) return

  const dpr = window.devicePixelRatio || 1
  const cssSize = props.size
  if (canvas.width !== cssSize * dpr) {
    canvas.width = cssSize * dpr
    canvas.height = cssSize * dpr
    canvas.style.width = `${cssSize}px`
    canvas.style.height = `${cssSize}px`
  }

  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, cssSize, cssSize)

  const seeded = random(hash(props.seed))
  const accessoryKind = Math.floor(seeded() * 4)
  const radius = cssSize * 0.34
  const centerX = cssSize / 2
  const centerY = cssSize / 2 + (props.float ? Math.sin(Date.now() / 720) * 3 : 0)
  const rgb = hexToRgb(props.color)
  const { dx, dy } = getLookVector(canvas)
  const localDistance = Math.hypot(dx, dy) || 1
  const eyeSizeForTarget = radius * 0.25
  const pupilMaxDist = eyeSizeForTarget * 0.48
  const targetX = (dx / localDistance) * pupilMaxDist
  const targetY = (dy / localDistance) * pupilMaxDist
  state.pupilX += (targetX - state.pupilX) * 0.18
  state.pupilY += (targetY - state.pupilY) * 0.18
  state.bodyAngle += (0 - state.bodyAngle) * 0.08

  ctx.save()
  ctx.translate(centerX, centerY)
  ctx.rotate(Math.sin(Date.now() / 1100 + seeded()) * 0.012)

  const halo = ctx.createRadialGradient(0, 0, radius * 0.15, 0, 0, radius * 1.55)
  halo.addColorStop(0, `rgba(${rgb.r},${rgb.g},${rgb.b},0.18)`)
  halo.addColorStop(1, `rgba(${rgb.r},${rgb.g},${rgb.b},0)`)
  ctx.fillStyle = halo
  ctx.beginPath()
  ctx.arc(0, 0, radius * 1.55, 0, Math.PI * 2)
  ctx.fill()

  ctx.fillStyle = props.color
  ctx.beginPath()
  ctx.ellipse(0, 0, radius, radius * 0.90, 0, 0, Math.PI * 2)
  ctx.fill()

  ctx.fillStyle = `rgba(${rgb.r},${rgb.g},${rgb.b},0.30)`
  ctx.beginPath()
  ctx.arc(-radius * 0.2, -radius * 0.18, radius * 0.82, 0, Math.PI * 2)
  ctx.fill()

  drawAccessory(ctx, accessoryKind, radius)

  const eyeOffsetX = radius * 0.34
  const eyeOffsetY = -radius * 0.12
  const eyeSize = radius * 0.25
  ctx.fillStyle = '#fff'
  ctx.beginPath()
  ctx.arc(-eyeOffsetX, eyeOffsetY, eyeSize, 0, Math.PI * 2)
  ctx.arc(eyeOffsetX, eyeOffsetY, eyeSize, 0, Math.PI * 2)
  ctx.fill()

  ctx.fillStyle = '#0f172a'
  const pupilSize = props.mood === 'error' ? eyeSize * 0.24 : eyeSize * 0.42
  ctx.beginPath()
  ctx.arc(-eyeOffsetX + state.pupilX, eyeOffsetY + state.pupilY, pupilSize, 0, Math.PI * 2)
  ctx.arc(eyeOffsetX + state.pupilX, eyeOffsetY + state.pupilY, pupilSize, 0, Math.PI * 2)
  ctx.fill()

  ctx.fillStyle = 'rgba(255, 255, 255, 0.82)'
  ctx.beginPath()
  ctx.arc(-eyeOffsetX + state.pupilX - pupilSize * 0.25, eyeOffsetY + state.pupilY - pupilSize * 0.25, pupilSize * 0.18, 0, Math.PI * 2)
  ctx.arc(eyeOffsetX + state.pupilX - pupilSize * 0.25, eyeOffsetY + state.pupilY - pupilSize * 0.25, pupilSize * 0.18, 0, Math.PI * 2)
  ctx.fill()

  ctx.strokeStyle = 'rgba(15, 23, 42, 0.55)'
  ctx.lineWidth = 2.4
  ctx.lineCap = 'round'
  ctx.beginPath()
  if (props.mood === 'error') {
    ctx.moveTo(-radius * 0.22, radius * 0.30)
    ctx.quadraticCurveTo(0, radius * 0.15, radius * 0.22, radius * 0.30)
  } else if (props.mood === 'thinking') {
    ctx.moveTo(-radius * 0.22, radius * 0.28)
    ctx.lineTo(radius * 0.22, radius * 0.28)
  } else {
    ctx.arc(0, radius * 0.18, radius * 0.25, 0.15, Math.PI - 0.15)
  }
  ctx.stroke()
  ctx.restore()

  state.animationId = window.requestAnimationFrame(draw)
}

function drawAccessory(ctx: CanvasRenderingContext2D, kind: number, radius: number) {
  ctx.save()
  ctx.fillStyle = '#64748b'
  ctx.strokeStyle = '#475569'
  ctx.lineWidth = Math.max(2, radius * 0.07)
  if (kind === 1) {
    ctx.translate(0, -radius * 0.92)
    ctx.rotate(-0.08)
    ctx.fillRect(-radius * 0.36, -radius * 0.16, radius * 0.72, radius * 0.22)
    ctx.beginPath()
    ctx.moveTo(-radius * 0.48, radius * 0.08)
    ctx.lineTo(radius * 0.48, radius * 0.08)
    ctx.stroke()
  } else if (kind === 2) {
    ctx.translate(0, radius * 0.88)
    ctx.beginPath()
    ctx.moveTo(0, 0)
    ctx.lineTo(-radius * 0.34, -radius * 0.18)
    ctx.lineTo(-radius * 0.34, radius * 0.18)
    ctx.closePath()
    ctx.moveTo(0, 0)
    ctx.lineTo(radius * 0.34, -radius * 0.18)
    ctx.lineTo(radius * 0.34, radius * 0.18)
    ctx.closePath()
    ctx.fill()
  } else if (kind === 3) {
    const eyeOffsetX = radius * 0.34
    const eyeOffsetY = -radius * 0.12
    ctx.strokeRect(-eyeOffsetX - radius * 0.20, eyeOffsetY - radius * 0.14, radius * 0.40, radius * 0.28)
    ctx.strokeRect(eyeOffsetX - radius * 0.20, eyeOffsetY - radius * 0.14, radius * 0.40, radius * 0.28)
    ctx.beginPath()
    ctx.moveTo(-eyeOffsetX + radius * 0.20, eyeOffsetY)
    ctx.lineTo(eyeOffsetX - radius * 0.20, eyeOffsetY)
    ctx.stroke()
  }
  ctx.restore()
}

function handleMouse(event: MouseEvent) {
  state.mouseX = event.clientX
  state.mouseY = event.clientY
}

onMounted(() => {
  const rect = canvasRef.value?.getBoundingClientRect()
  state.mouseX = rect ? rect.left + rect.width / 2 : props.size / 2
  state.mouseY = rect ? rect.top + rect.height / 2 : props.size / 2
  if (props.interactive) {
    window.addEventListener('mousemove', handleMouse)
  }
  draw()
})

onBeforeUnmount(() => {
  if (state.animationId) window.cancelAnimationFrame(state.animationId)
  if (props.interactive) {
    window.removeEventListener('mousemove', handleMouse)
  }
})

watch(() => [props.color, props.seed, props.mood, props.size], () => {
  if (!state.animationId) draw()
})
</script>

<style scoped>
.mascot-canvas {
  display: block;
}
</style>
