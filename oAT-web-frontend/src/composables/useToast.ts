import { ref } from 'vue'

export type ToastTone = 'default' | 'success' | 'danger' | 'warning' | 'info'

export interface ToastItem {
  key: string
  message: string
  tone: ToastTone
  duration: number
  timer?: number
}

export const toasts = ref<ToastItem[]>([])

let toastIdCounter = 0

export function showToast(message: string, options?: { tone?: ToastTone; duration?: number }) {
  const key = `toast-${Date.now()}-${++toastIdCounter}`
  const tone = options?.tone || 'default'
  const duration = options?.duration || (tone === 'danger' ? 6000 : 4000)
  
  const timer = window.setTimeout(() => {
    dismissToast(key)
  }, duration)
  
  const toast: ToastItem = {
    key,
    message,
    tone,
    duration,
    timer,
  }
  
  toasts.value = [toast, ...toasts.value].slice(0, 5)
  
  return key
}

export function dismissToast(key: string) {
  const toast = toasts.value.find((item) => item.key === key)
  if (toast?.timer) {
    window.clearTimeout(toast.timer)
  }
  toasts.value = toasts.value.filter((item) => item.key !== key)
}

export function useToast() {
  return {
    success: (message: string, duration?: number) => showToast(message, { tone: 'success', duration }),
    error: (message: string, duration?: number) => showToast(message, { tone: 'danger', duration }),
    warning: (message: string, duration?: number) => showToast(message, { tone: 'warning', duration }),
    info: (message: string, duration?: number) => showToast(message, { tone: 'info', duration }),
    show: showToast,
    dismiss: dismissToast,
  }
}
