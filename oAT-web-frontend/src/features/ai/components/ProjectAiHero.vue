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

<style scoped>
.hero-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px minmax(250px, 320px);
  align-items: center;
  gap: 18px;
  overflow: hidden;
  padding: 24px 28px;
  border: 1px solid color-mix(in srgb, var(--ai-accent, #0f766e) 28%, transparent);
  border-radius: 28px;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, .12) 1px, transparent 1px),
    linear-gradient(rgba(255, 255, 255, .12) 1px, transparent 1px),
    radial-gradient(circle at 76% 18%, color-mix(in srgb, var(--ai-accent, #0f766e) 22%, white), transparent 34%),
    linear-gradient(135deg, color-mix(in srgb, var(--ai-accent, #0f766e) 84%, #991b1b), #111827 72%);
  background-size: 40px 40px, 40px 40px, auto, auto;
  color: #fff;
  box-shadow: 0 22px 54px rgba(15, 23, 42, .12);
}

.hero-kicker {
  color: rgba(255, 255, 255, .78);
  font-size: 12px;
  font-weight: 900;
  letter-spacing: .12em;
  text-transform: uppercase;
}

h2 {
  max-width: 900px;
  margin: 8px 0 0;
  color: #fff;
  font-size: clamp(23px, 2vw, 32px);
  line-height: 1.34;
}

p {
  max-width: 720px;
  margin: 12px 0 0;
  color: rgba(255, 255, 255, .82);
  line-height: 1.55;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}

.primary-button,
.ghost-button,
.ghost-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 42px;
  border-radius: 999px;
  padding: 0 18px;
  font: inherit;
  font-weight: 900;
  text-decoration: none;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, background .16s ease, color .16s ease, border-color .16s ease;
}

.primary-button {
  border: 0;
  background: linear-gradient(135deg, var(--ai-accent, #0f766e), color-mix(in srgb, var(--ai-accent, #0f766e) 72%, #0f172a));
  color: #fff;
  box-shadow: 0 16px 34px rgba(0, 0, 0, .16);
}

.ghost-button,
.ghost-link {
  border: 1px solid rgba(255, 255, 255, .38);
  background: rgba(255, 255, 255, .88);
  color: color-mix(in srgb, var(--ai-accent, #0f766e) 72%, #0f172a);
}

.primary-button:hover,
.ghost-button:hover,
.ghost-link:hover {
  transform: translateY(-1px);
}

.hero-mascot {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 128px;
}

.stage-ring {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, .34);
  border-radius: 999px;
}

.stage-ring.one {
  width: 112px;
  height: 112px;
}

.stage-ring.two {
  width: 138px;
  height: 138px;
  opacity: .68;
}

.hero-meta {
  display: grid;
  gap: 10px;
}

.hero-chip {
  padding: 13px 14px;
  border: 1px solid rgba(255, 255, 255, .18);
  border-radius: 18px;
  background: rgba(255, 255, 255, .13);
  backdrop-filter: blur(12px);
}

.hero-chip strong {
  display: block;
  color: #fff;
  font-size: 22px;
  line-height: 1.1;
}

.hero-chip span {
  display: block;
  margin-top: 5px;
  color: rgba(255, 255, 255, .82);
  font-size: 13px;
}

@media (max-width: 980px) {
  .hero-card {
    grid-template-columns: minmax(0, 1fr) 120px;
  }

  .hero-meta {
    grid-column: 1 / -1;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .hero-card {
    grid-template-columns: 1fr;
    padding: 20px;
  }

  .hero-mascot {
    display: none;
  }

  .hero-meta {
    grid-template-columns: 1fr;
  }
}
</style>
