<template>
  <div v-show="!collapsed" class="quick-links">
    <button v-for="link in links" :key="link.title + link.url" type="button" @click="$emit('open', link)">
      <span class="quick-link-icon">{{ quickLinkIcon(link.title) }}</span>
      <span class="quick-link-copy">
        <strong>{{ link.title }}</strong>
        <small>{{ link.description }}</small>
      </span>
    </button>
    <div v-if="!links.length" class="quick-links-empty">暂无快捷入口，请先在 AI 工作台或当前页面产生上下文。</div>
  </div>
</template>

<script setup lang="ts">
import type { AIQuickLink } from '@/api/types'

defineProps<{
  links: AIQuickLink[]
  collapsed: boolean
}>()

defineEmits<{
  open: [link: AIQuickLink]
}>()

function quickLinkIcon(title?: string) {
  const text = title || ''
  if (text.includes('监控') || text.includes('链路')) return '链'
  if (text.includes('AI')) return 'AI'
  if (text.includes('应用')) return '用'
  return '↗'
}
</script>
