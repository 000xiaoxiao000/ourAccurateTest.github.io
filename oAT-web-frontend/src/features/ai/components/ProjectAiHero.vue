<template>
  <div class="hero-card">
    <div>
      <div class="hero-kicker">智能协作</div>
      <h2>{{ context.welcomeMessage }}</h2>
      <p>{{ context.mascotHint }}</p>
      <div class="hero-actions">
        <button class="primary-button" type="button" @click="$emit('use-question', '帮我总结当前项目的需求验证风险')">启动概览</button>
        <button class="ghost-button" type="button" @click="$emit('use-question', '哪些需求缺少用例或源码证据')">追溯缺口</button>
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
        <strong>{{ context.appCount }}</strong>
        <span>源码工程</span>
      </div>
      <div class="hero-chip">
        <strong>RTM</strong>
        <span>双向追溯</span>
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
