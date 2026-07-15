<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="dialogState.visible" class="dialog-mask" @click.self="cancelDialog">
        <section class="dialog-card" :class="`tone-${dialogState.tone}`" role="dialog" aria-modal="true">
          <div class="dialog-icon" aria-hidden="true">{{ iconText }}</div>
          <div class="dialog-main">
            <h2>{{ dialogState.title }}</h2>
            <p v-if="dialogState.message">{{ dialogState.message }}</p>
            <input
              v-if="dialogState.kind === 'prompt'"
              ref="promptInput"
              v-model.trim="dialogState.inputValue"
              class="dialog-input"
              type="text"
              :maxlength="dialogState.maxLength"
              :placeholder="dialogState.placeholder"
              @keyup.enter="submit"
            />
            <div class="dialog-actions">
              <button v-if="dialogState.kind !== 'alert'" class="dialog-button secondary" type="button" @click="cancelDialog">
                {{ dialogState.cancelText }}
              </button>
              <button class="dialog-button primary" type="button" @click="submit">
                {{ dialogState.confirmText }}
              </button>
            </div>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

import { cancelDialog, dialogState, resolveDialog } from '@/composables/useDialog'

const promptInput = ref<HTMLInputElement | null>(null)

const iconText = computed(() => {
  if (dialogState.value.tone === 'danger') return '!'
  if (dialogState.value.tone === 'success') return '✓'
  if (dialogState.value.tone === 'warning') return '?'
  return 'i'
})

function submit() {
  if (dialogState.value.kind === 'prompt') {
    resolveDialog(dialogState.value.inputValue.trim())
    return
  }
  resolveDialog(dialogState.value.kind === 'confirm' ? true : undefined)
}

watch(
  () => dialogState.value.visible,
  async (visible) => {
    if (visible && dialogState.value.kind === 'prompt') {
      await nextTick()
      promptInput.value?.focus()
      promptInput.value?.select()
    }
  },
)
</script>

<style scoped>
.dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 5000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .44);
  backdrop-filter: blur(12px);
}

.dialog-card {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  gap: 16px;
  width: min(480px, 100%);
  padding: 22px;
  border: 1px solid rgba(15, 23, 42, .08);
  border-radius: 22px;
  background:
    radial-gradient(circle at top right, rgba(0, 181, 173, .14), transparent 34%),
    #fff;
  box-shadow: 0 28px 70px rgba(15, 23, 42, .24);
}

.dialog-icon {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border-radius: 18px;
  background: rgba(15, 118, 110, .12);
  color: #0f766e;
  font-size: 24px;
  font-weight: 900;
}

.tone-danger .dialog-icon {
  background: rgba(185, 28, 28, .10);
  color: #b91c1c;
}

.tone-success .dialog-icon {
  background: rgba(22, 163, 74, .12);
  color: #15803d;
}

.tone-warning .dialog-icon {
  background: rgba(217, 119, 6, .12);
  color: #b45309;
}

.dialog-main {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.dialog-main h2 {
  margin: 0;
  color: #172033;
  font-size: 20px;
  line-height: 1.25;
}

.dialog-main p {
  max-width: 100%;
  margin: 0;
  color: #64748b;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-line;
  word-break: break-word;
}

.dialog-input {
  width: 100%;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 14px;
  padding: 11px 13px;
  background: #fff;
  color: #172033;
}

.dialog-input:focus {
  border-color: rgba(15, 118, 110, .65);
  box-shadow: 0 0 0 3px rgba(15, 118, 110, .12);
  outline: none;
}

.dialog-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

.dialog-button {
  border-radius: 999px;
  padding: 10px 16px;
  cursor: pointer;
  font-weight: 800;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, border-color .16s ease;
}

.dialog-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px rgba(15, 23, 42, .12);
}

.dialog-button:active {
  transform: translateY(0) scale(.98);
  box-shadow: none;
}

.dialog-button.secondary {
  border: 1px solid rgba(15, 118, 110, .18);
  background: rgba(15, 118, 110, .06);
  color: #0f766e;
}

.dialog-button.primary {
  border: none;
  background: linear-gradient(135deg, #0f766e, #14b8a6);
  color: #fff;
}

.tone-danger .dialog-button.primary {
  background: linear-gradient(135deg, #b91c1c, #ef4444);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity .18s ease;
}

.dialog-fade-enter-active .dialog-card,
.dialog-fade-leave-active .dialog-card {
  transition: transform .18s ease, opacity .18s ease;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .dialog-card,
.dialog-fade-leave-to .dialog-card {
  opacity: 0;
  transform: translateY(10px) scale(.98);
}

@media (max-width: 560px) {
  .dialog-card {
    grid-template-columns: 1fr;
  }
}
</style>
