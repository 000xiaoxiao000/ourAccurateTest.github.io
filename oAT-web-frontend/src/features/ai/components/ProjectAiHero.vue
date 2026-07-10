<template>
  <div class="hero-card">
    <div>
      <div class="hero-kicker">智能协作</div>
      <h2>{{ context.welcomeMessage }}</h2>
      <p>{{ context.mascotHint }}</p>
      <div class="hero-actions">
        <button class="primary-button" type="button" @click="$emit('use-question', '帮我总结一下当前项目概况')">启动概览</button>
        <button class="ghost-button" type="button" @click="$emit('use-question', '如果线上有异常，排查顺序是什么')">开始排查</button>
        <RouterLink class="ghost-link" :to="`/p/${projectId}/map/home`">打开链路地图</RouterLink>
      </div>
    </div>
    <div class="hero-mascot">
      <div class="stage-ring one"></div>
      <div class="stage-ring two"></div>
      <MascotCanvas :size="118" :color="context.mascot?.mascotPrimary || '#0f766e'" :seed="projectId" :mood="mood" :interactive="true" />
    </div>
    <div class="hero-meta">
      <div class="hero-chip">
        <strong>{{ context.onlineAppCount }}</strong>
        <span>在线应用</span>
      </div>
      <div class="hero-chip">
        <strong>{{ context.appCount }}</strong>
        <span>全部应用</span>
      </div>
      <div class="hero-chip">
        <strong>{{ context.aiTimeout }}s</strong>
        <span>超时设置</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'

import type { AIInteractivePagePayload } from '@/api/types'
import MascotCanvas from '@/components/MascotCanvas.vue'

defineProps<{
  context: AIInteractivePagePayload
  projectId: string
  mood: 'thinking' | 'error' | 'happy'
}>()

defineEmits<{
  'use-question': [question: string]
}>()
</script>
