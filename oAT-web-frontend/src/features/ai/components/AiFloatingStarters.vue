<template>
  <div v-show="!collapsed" class="starters">
    <button v-for="item in starters" :key="item" type="button" @click="$emit('preset', item)">{{ item }}</button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  starters: string[]
  collapsed: boolean
}>()

defineEmits<{
  preset: [text: string]
}>()
</script>
