<template>
  <form class="compose layout-item" :style="layoutStyle" @pointerdown="$emit('layout-drag', $event)" @submit.prevent="$emit('send')">
    <span
      v-for="direction in resizeDirections"
      :key="direction"
      :class="['layout-resizer', direction]"
      title="拖拽调整区域大小"
      @pointerdown.stop.prevent="$emit('layout-resize', $event, direction)"
    ></span>
    <textarea :value="question" rows="3" placeholder="随时提问，例如：这个页面的数据该从哪里看" @input="$emit('update:question', ($event.target as HTMLTextAreaElement).value)" @keydown.enter.exact="$emit('enter', $event)"></textarea>
    <input ref="imageInput" type="file" accept="image/*" class="hidden-input" @change="$emit('image-change', $event)" />
    <div class="compose-actions">
      <span class="state">{{ stateText }}</span>
      <div class="toolbar">
        <button type="button" :class="['tool-button', imageData && 'active']" title="上传图片提问" @click="selectImage">
          图
          <span v-if="imageData" class="remove-image" @click.stop="$emit('clear-image')">x</span>
        </button>
        <button type="button" :class="['tool-button', recording && 'active']" title="语音输入" @click="$emit('toggle-voice')">声</button>
        <button class="send-button" type="submit" :disabled="asking">{{ asking ? '生成中' : '发送' }}</button>
      </div>
    </div>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { StyleValue } from 'vue'

import type { AiFloatingLayoutResizeDirection } from '@/features/ai/types'

defineProps<{
  question: string
  imageData: string
  recording: boolean
  asking: boolean
  stateText: string
  layoutStyle: StyleValue
  resizeDirections: AiFloatingLayoutResizeDirection[]
}>()

defineEmits<{
  'update:question': [value: string]
  send: []
  enter: [event: KeyboardEvent]
  'image-change': [event: Event]
  'clear-image': []
  'toggle-voice': []
  'layout-drag': [event: PointerEvent]
  'layout-resize': [event: PointerEvent, direction: AiFloatingLayoutResizeDirection]
}>()

const imageInput = ref<HTMLInputElement | null>(null)

function selectImage() {
  imageInput.value?.click()
}
</script>
