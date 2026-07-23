<template>
  <button
    type="button"
    class="app-refresh-button"
    :disabled="disabled || loading"
    :aria-label="loading ? `正在${label}` : label"
  >
    <svg class="app-refresh-icon" :class="{ spinning: loading }" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M20 11a8 8 0 0 0-14.9-4L3 9m0-5v5h5M4 13a8 8 0 0 0 14.9 4L21 15m0 5v-5h-5" />
    </svg>
    <span>{{ loading ? loadingText : label }}</span>
  </button>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  disabled?: boolean
  label?: string
  loading?: boolean
  loadingText?: string
}>(), {
  disabled: false,
  label: '刷新',
  loading: false,
  loadingText: '刷新中...',
})
</script>

<style scoped>
.app-refresh-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: var(--oat-min-target);
  padding: 0 16px;
  border: 1px solid rgba(var(--oat-primary-rgb), .22);
  border-radius: 999px;
  background: rgba(255, 255, 255, .78);
  color: var(--oat-primary-dark);
  box-shadow: var(--oat-shadow-xs);
  font-weight: 800;
  line-height: 1;
  transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease, background .16s ease, color .16s ease;
}
.app-refresh-button:hover:not(:disabled) {
  border-color: rgba(var(--oat-primary-rgb), .42);
  background: var(--oat-primary-container);
  box-shadow: 0 8px 18px rgba(var(--oat-primary-rgb), .14);
}
.app-refresh-button:active:not(:disabled) {
  box-shadow: none;
  transform: translateY(0) scale(.98);
}
.app-refresh-button:focus-visible {
  outline: none;
  box-shadow: var(--oat-focus-ring);
}
.app-refresh-icon {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}
.spinning { animation: refresh-spin .8s linear infinite; }
@keyframes refresh-spin { to { transform: rotate(360deg); } }
</style>
