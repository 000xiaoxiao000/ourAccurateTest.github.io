<template>
  <nav v-if="items.length" class="breadcrumbs" aria-label="当前位置">
    <button class="back-button" type="button" @click="goBack">← 返回</button>
    <span class="breadcrumb-label">当前位置</span>
    <RouterLink
      v-for="(item, index) in items"
      :key="`${item.label}-${index}`"
      :to="item.to || route.fullPath"
      :class="{ current: index === items.length - 1 || !item.to }"
    >
      {{ item.label }}
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useProjectStore } from '@/stores/project'

type BreadcrumbItem = { label: string; to?: string }

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const projectId = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : '')
const appId = computed(() => {
  if (typeof route.params.appId === 'string') return route.params.appId
  if (typeof route.query.appId === 'string') return route.query.appId
  return ''
})
const context = computed(() => projectId.value ? projectStore.contextByProjectId[projectId.value] : undefined)
const currentApp = computed(() => context.value?.apps.find((app) => app.id === appId.value))
const backTarget = computed(() => {
  const name = String(route.name || '')
  const projectHome = projectId.value ? `/p/${projectId.value}/home` : '/projects'
  const appSettings = projectId.value && appId.value ? `/p/${projectId.value}/apps/${appId.value}/settings` : projectHome
  const appList = projectId.value ? `/p/${projectId.value}/apps` : '/projects'

  const routeBackTargets: Record<string, string> = {
    projects: '/projects',
    'project-home': '/projects',
    'project-ai': projectHome,
    'project-apps': projectHome,
    'online-apps': appList,
    'app-settings': appList,
    'app-repository': appSettings,
    'app-api-endpoints': appSettings,
    'version-apps': projectHome,
    'version-list': `/p/${projectId.value}/version/apps`,
    'version-create': `/p/${projectId.value}/apps/${appId.value}/versions`,
    'version-compare': `/p/${projectId.value}/apps/${appId.value}/versions`,
    'version-report-detail': `/p/${projectId.value}/apps/${appId.value}/compare`,
    'quality-gate-ci': projectHome,
    'map-home': projectHome,
    'map-app': `/p/${projectId.value}/map/home`,
    'map-code': `/p/${projectId.value}/map/home`,
    'search-center': `/p/${projectId.value}/map/home`,
    'usecase-list': projectHome,
    'usecase-new': `/p/${projectId.value}/usecases`,
    'usecase-edit': `/p/${projectId.value}/usecases/${route.params.usecaseId}`,
    'usecase-detail': `/p/${projectId.value}/usecases`,
    'project-members': projectHome,
    'project-labels': projectHome,
  }

  return routeBackTargets[name] || projectHome
})

const routeLabels: Record<string, string> = {
  projects: '项目列表',
  'account-settings': '用户设置',
  'project-home': '项目首页',
  'project-ai': 'AI 工作台',
  'project-apps': '应用管理',
  'online-apps': '采集源健康度',
  'app-settings': '应用设置',
  'app-repository': '仓库配置',
  'app-api-endpoints': '接口扫描',
  'version-apps': '版本中心',
  'version-list': '版本列表',
  'version-create': '新建版本',
  'version-compare': '版本比对',
  'version-report-detail': '比对报告',
  'map-home': '链路地图',
  'map-app': '应用地图',
  'map-code': '代码地图',
  'search-center': '搜索中心',
  'usecase-list': '测试用例',
  'usecase-new': '新建用例',
  'usecase-edit': '编辑用例',
  'usecase-detail': '用例详情',
  'project-members': '项目成员',
  'project-labels': '标签管理',
}

// Routes where the app node should NOT appear as a breadcrumb ancestor.
// Instead these routes belong to a module hub.
const routeParentChain: Record<string, Array<{ label: string; to: string }>> = {
  'version-list': [{ label: '版本中心', to: `/p/${projectId.value}/version/apps` }],
  'version-create': [
    { label: '版本中心', to: `/p/${projectId.value}/version/apps` },
    { label: '版本列表', to: `/p/${projectId.value}/apps/${appId.value}/versions` },
  ],
  'version-compare': [
    { label: '版本中心', to: `/p/${projectId.value}/version/apps` },
    { label: '版本列表', to: `/p/${projectId.value}/apps/${appId.value}/versions` },
  ],
}

const items = computed<BreadcrumbItem[]>(() => {
  const name = String(route.name || '')
  if (!name || name === 'not-found' || name === 'projects') return []
  if (!projectId.value) return [
    { label: '项目列表', to: '/projects' },
    { label: routeLabels[name] || '当前位置' },
  ]

  const result: BreadcrumbItem[] = [
    { label: '项目列表', to: '/projects' },
    { label: context.value?.project.name || '项目', to: `/p/${projectId.value}/home` },
  ]

  if (name in routeParentChain) {
    // Module-scoped route: insert the module parent chain, no app crumb
    for (const crumb of routeParentChain[name]) {
      result.push(crumb)
    }
  } else if (appId.value) {
    // App-scoped route: show the app as breadcrumb ancestor
    result.push({ label: currentApp.value?.name || '应用', to: `/p/${projectId.value}/apps/${appId.value}/settings` })
  }

  const label = routeLabels[name] || String(route.meta.title || '当前位置')
  if (result[result.length - 1]?.label !== label) {
    result.push({ label })
  }
  return result
})

function goBack() {
  router.push(backTarget.value)
}
</script>

<style scoped>
.breadcrumbs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
  padding: 5px 8px;
  border: 1px solid rgba(15, 23, 42, .07);
  border-radius: 999px;
  background: rgba(255, 255, 255, .72);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
  color: #64748b;
  font-size: 12px;
  width: fit-content;
  max-width: 100%;
}

.breadcrumb-label {
  padding: 0 4px;
  color: #94a3b8;
  font-weight: 800;
}

.breadcrumbs a,
.back-button {
  color: #0f766e;
  font: inherit;
  font-weight: 800;
  cursor: pointer;
}

.breadcrumbs a {
  border: none;
  border-radius: 999px;
  padding: 4px 7px;
  background: transparent;
}

.back-button {
  border: 1px solid rgba(15, 118, 110, .16);
  border-radius: 999px;
  padding: 6px 10px;
  background: rgba(15, 118, 110, .06);
}

.breadcrumbs a:hover,
.back-button:hover {
  background: rgba(15, 118, 110, .07);
}

.breadcrumbs a::after {
  content: '/';
  margin-left: 8px;
  color: #cbd5e1;
}

.breadcrumbs a.current {
  background: #0f766e;
  color: #fff;
  pointer-events: none;
}

.breadcrumbs a.current::after {
  content: '';
  margin: 0;
}

@media (max-width: 760px) {
  .breadcrumbs {
    width: 100%;
    border-radius: 16px;
  }
}
</style>
