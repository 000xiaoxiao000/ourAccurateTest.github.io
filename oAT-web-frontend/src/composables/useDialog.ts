import { ref } from 'vue'

type DialogKind = 'alert' | 'confirm' | 'prompt'
type DialogTone = 'default' | 'danger' | 'success' | 'warning'
type DialogResult = boolean | string | null | undefined

type DialogState = {
  visible: boolean
  kind: DialogKind
  tone: DialogTone
  title: string
  message: string
  confirmText: string
  cancelText: string
  inputValue: string
  placeholder: string
  maxLength: number
  resolve?: (value: DialogResult) => void
}

type BaseDialogOptions = {
  title: string
  message?: string
  confirmText?: string
  cancelText?: string
  tone?: DialogTone
}

type PromptDialogOptions = BaseDialogOptions & {
  defaultValue?: string
  placeholder?: string
  maxLength?: number
}

const emptyState = (): DialogState => ({
  visible: false,
  kind: 'alert',
  tone: 'default',
  title: '',
  message: '',
  confirmText: '确定',
  cancelText: '取消',
  inputValue: '',
  placeholder: '',
  maxLength: 80,
})

export const dialogState = ref<DialogState>(emptyState())

function openDialog(kind: DialogKind, options: BaseDialogOptions | PromptDialogOptions, resolve: (value: DialogResult) => void) {
  if (dialogState.value.visible) {
    dialogState.value.resolve?.(fallbackResult(dialogState.value.kind))
  }
  dialogState.value = {
    ...emptyState(),
    ...options,
    visible: true,
    kind,
    tone: options.tone || 'default',
    message: options.message || '',
    confirmText: options.confirmText || (kind === 'confirm' ? '确认' : '确定'),
    cancelText: options.cancelText || '取消',
    inputValue: 'defaultValue' in options ? options.defaultValue || '' : '',
    placeholder: 'placeholder' in options ? options.placeholder || '' : '',
    maxLength: 'maxLength' in options ? options.maxLength || 80 : 80,
    resolve,
  }
}

function fallbackResult(kind: DialogKind): DialogResult {
  if (kind === 'prompt') return null
  if (kind === 'confirm') return false
  return undefined
}

export function resolveDialog(value: DialogResult) {
  const resolve = dialogState.value.resolve
  dialogState.value = emptyState()
  resolve?.(value)
}

export function cancelDialog() {
  resolveDialog(fallbackResult(dialogState.value.kind))
}

export function confirmDialog(options: BaseDialogOptions) {
  return new Promise<boolean>((resolve) => {
    openDialog('confirm', options, (value) => resolve(value === true))
  })
}

export function alertDialog(options: BaseDialogOptions | string) {
  const normalized = typeof options === 'string' ? { title: '操作完成', message: options, tone: 'success' as DialogTone } : options
  return new Promise<void>((resolve) => {
    openDialog('alert', normalized, () => resolve())
  })
}

export function promptDialog(options: PromptDialogOptions) {
  return new Promise<string | null>((resolve) => {
    openDialog('prompt', options, (value) => resolve(typeof value === 'string' ? value : null))
  })
}

export function useDialog() {
  return {
    alert: alertDialog,
    confirm: confirmDialog,
    prompt: promptDialog,
  }
}
