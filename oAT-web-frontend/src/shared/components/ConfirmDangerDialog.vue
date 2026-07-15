<template>
  <div v-if="visible" class="modal-mask" @click.self="$emit('cancel')">
    <section class="modal-card confirm-card" role="dialog" aria-modal="true" :aria-labelledby="titleId">
      <div class="confirm-icon">!</div>
      <div class="confirm-copy">
        <h2 :id="titleId">{{ title }}</h2>
        <p>{{ message }}</p>
      </div>
      <div class="confirm-actions">
        <button class="ghost-button" type="button" @click="$emit('cancel')">取消</button>
        <button class="danger-button confirm-danger" type="button" @click="$emit('confirm')">确认删除</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  visible: boolean
  title: string
  message: string
  titleId?: string
}>()

defineEmits<{
  (event: 'cancel'): void
  (event: 'confirm'): void
}>()
</script>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .44);
}

.modal-card {
  padding: 18px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.08);
  width: min(460px, calc(100vw - 32px));
  max-height: min(760px, calc(100vh - 48px));
  overflow: auto;
}

.confirm-card {
  display: grid;
  justify-items: center;
  gap: 14px;
  text-align: center;
  background:
    radial-gradient(circle at top, rgba(248, 113, 113, .14), transparent 38%),
    #fff;
}

.confirm-icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border-radius: 999px;
  background: rgba(185, 28, 28, .10);
  color: #b91c1c;
  font-size: 28px;
  font-weight: 900;
}

.confirm-copy h2 {
  margin: 0 0 8px;
  color: #172033;
}

.confirm-copy {
  max-width: 100%;
  min-width: 0;
}

.confirm-copy p {
  margin: 0;
  color: #64748b;
  line-height: 1.7;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.confirm-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  width: 100%;
}

.ghost-button,
.danger-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
}

.ghost-button {
  background: rgba(15, 23, 42, 0.08);
}

.danger-button {
  background: rgba(185, 28, 28, .12);
  color: #b91c1c;
}

.danger-button:hover {
  background: #b91c1c;
  color: #fff;
}

.confirm-danger {
  min-width: 108px;
}
</style>
