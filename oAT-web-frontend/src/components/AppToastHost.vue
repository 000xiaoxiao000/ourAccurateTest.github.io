<template>
  <Teleport to="body">
    <TransitionGroup
      v-if="toasts.length"
      name="toast-fade"
      tag="div"
      class="toast-stack"
      aria-live="polite"
      aria-label="操作提示"
    >
      <article
        v-for="toast in toasts"
        :key="toast.key"
        class="toast-card"
        :class="`tone-${toast.tone}`"
        role="alert"
      >
        <div class="toast-icon" aria-hidden="true">{{ iconText(toast.tone) }}</div>
        <p class="toast-message">{{ toast.message }}</p>
        <button
          class="toast-close"
          type="button"
          aria-label="关闭提示"
          @click="dismissToast(toast.key)"
        >×</button>
      </article>
    </TransitionGroup>
  </Teleport>
</template>

<script setup lang="ts">
import { dismissToast, toasts } from '@/composables/useToast'
import type { ToastTone } from '@/composables/useToast'

function iconText(tone: ToastTone) {
  switch (tone) {
    case 'success':
      return '✓'
    case 'danger':
      return '!'
    case 'warning':
      return '?'
    case 'info':
      return 'i'
    default:
      return '•'
  }
}
</script>

<style scoped>
.toast-stack {
  position: fixed;
  top: 80px;
  right: 18px;
  z-index: 2100;
  display: grid;
  gap: 12px;
  width: min(380px, calc(100vw - 32px));
  pointer-events: none;
}

.toast-card {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 28px;
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(100, 116, 139, 0.16);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 18px 45px rgba(15, 23, 42, 0.14);
  backdrop-filter: blur(14px);
  pointer-events: auto;
  animation: toast-slide-in 0.3s ease;
}

@keyframes toast-slide-in {
  from {
    opacity: 0;
    transform: translateX(100%);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.toast-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 14px;
  background: #f1f5f9;
  color: #475569;
  font-size: 18px;
  font-weight: 900;
  flex-shrink: 0;
}

.toast-message {
  margin: 0;
  padding: 8px 0;
  color: #0f172a;
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  align-self: center;
}

.toast-close {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
}

.toast-close:hover {
  background: rgba(15, 23, 42, 0.12);
  color: #0f172a;
}

.toast-card.tone-success {
  border-color: rgba(22, 163, 74, 0.2);
}

.toast-card.tone-success .toast-icon {
  background: #f0fdf4;
  color: #15803d;
}

.toast-card.tone-danger {
  border-color: rgba(185, 28, 28, 0.22);
}

.toast-card.tone-danger .toast-icon {
  background: #fef2f2;
  color: #b91c1c;
}

.toast-card.tone-warning {
  border-color: rgba(234, 88, 12, 0.22);
}

.toast-card.tone-warning .toast-icon {
  background: #fff7ed;
  color: #c2410c;
}

.toast-card.tone-info {
  border-color: rgba(29, 78, 216, 0.2);
}

.toast-card.tone-info .toast-icon {
  background: #eff6ff;
  color: #1d4ed8;
}

.toast-fade-move,
.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: all 0.3s ease;
}

.toast-fade-enter-from {
  opacity: 0;
  transform: translateX(100%);
}

.toast-fade-leave-to {
  opacity: 0;
  transform: translateY(-12px) scale(0.95);
}

.toast-fade-leave-active {
  position: absolute;
}

@media (max-width: 640px) {
  .toast-stack {
    top: 72px;
    right: 12px;
    left: 12px;
    width: auto;
  }
}
</style>
