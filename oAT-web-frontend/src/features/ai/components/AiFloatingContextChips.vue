<template>
  <div v-for="chip in chips" :key="chip.id" class="context-chip">
    {{ chip.label }}：<span>{{ chip.value }}</span>
    <button type="button" title="清除" @click="$emit('remove', chip.id)">x</button>
  </div>
</template>

<script setup lang="ts">
import type { AiFloatingContextChip, AiFloatingContextChipId } from '@/features/ai/types'

defineProps<{
  chips: AiFloatingContextChip[]
}>()

defineEmits<{
  remove: [id: AiFloatingContextChipId]
}>()
</script>
