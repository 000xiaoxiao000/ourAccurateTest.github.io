<template>
  <div ref="shellRef" class="shell" @keydown.esc="closeMenus">
    <header class="shell-header">
      <div class="page-shell shell-header-inner">
        <RouterLink class="brand-block" :to="projectId ? `/p/${projectId}/home` : '/projects'" aria-label="oAccurateTest" @click="closeMenus">
          <img class="brand-emblem" src="/favicon.png" alt="" aria-hidden="true" />
          <img class="brand-logo-image" src="/images/logo.png" alt="oAccurateTest" />
        </RouterLink>
        <nav class="shell-nav" aria-label="主导航">
          <RouterLink v-if="projectId" :to="`/p/${projectId}/verification`">AI 验证</RouterLink>
          <RouterLink v-if="projectId" :to="`/p/${projectId}/git-impact`">Git 影响</RouterLink>
          <RouterLink v-if="projectId" :to="`/p/${projectId}/map/home`">链路地图</RouterLink>
          <div v-if="projectId" class="nav-dropdown app-center" :class="{ open: openMenu === 'app' }" @mouseenter="openNavMenu('app')" @mouseleave="closeMenus">
            <RouterLink class="nav-dropdown-trigger" :to="`/p/${projectId}/apps`" aria-haspopup="true" :aria-expanded="openMenu === 'app'" @click="closeMenus">源码工程 <span class="menu-caret" aria-hidden="true">⌄</span></RouterLink>
            <div class="nav-menu app-menu">
              <input v-model.trim="appKeyword" class="menu-search" type="text" placeholder="搜索源码工程..." />
              <div class="menu-divider"></div>
              <div v-for="app in filteredApps" :key="app.id" class="app-menu-item">
                <RouterLink class="app-main-link" :to="`/p/${projectId}/apps/${app.id}/settings`" :title="appTitle(app)" @click="closeMenus">
                  {{ app.name }} <small>({{ appLanguageText(app) }})</small>
                </RouterLink>
              </div>
              <div v-if="!filteredApps.length" class="empty-menu-item">暂无源码工程</div>
            </div>
          </div>
          <template v-if="currentUser">
            <div v-if="projectId" class="nav-dropdown create-menu" :class="{ open: openMenu === 'create' }" @mouseenter="openNavMenu('create')" @mouseleave="closeMenus">
              <button class="icon-trigger" type="button" aria-label="快速创建" aria-haspopup="true" :aria-expanded="openMenu === 'create'" @click.stop="toggleMenu('create')">＋</button>
              <div class="nav-menu compact right-aligned">
                <RouterLink to="/projects?create=1" @click="closeMenus">创建新项目</RouterLink>
                <RouterLink :to="`/p/${projectId}/apps?create=1`" @click="closeMenus">添加源码工程</RouterLink>
              </div>
            </div>
            <RouterLink v-if="projectId" class="icon-trigger" :to="`/projects?edit=${projectId}`" aria-label="项目设置" title="设置" @click="closeMenus">⚙</RouterLink>
            <div class="nav-dropdown project-switcher" :class="{ open: openMenu === 'project' }" @mouseenter="openNavMenu('project')" @mouseleave="closeMenus">
              <button class="project-trigger" type="button" aria-haspopup="true" :aria-expanded="openMenu === 'project'" @click.stop="toggleMenu('project')">{{ currentProjectName }} <span aria-hidden="true">⌄</span></button>
              <div class="nav-menu project-menu right-aligned">
                <input v-model.trim="projectKeyword" class="menu-search" type="text" placeholder="搜索项目..." />
                <RouterLink v-for="project in filteredProjects" :key="project.id" :to="`/p/${project.id}/home`" @click="closeMenus">
                  {{ project.name }}
                </RouterLink>
                <div v-if="!filteredProjects.length" class="empty-menu-item">暂无项目</div>
              </div>
            </div>
            <div class="nav-dropdown user-menu" :class="{ open: openMenu === 'user' }" @mouseenter="openNavMenu('user')" @mouseleave="closeMenus">
              <button class="icon-trigger" type="button" aria-label="用户菜单" aria-haspopup="true" :aria-expanded="openMenu === 'user'" @click.stop="toggleMenu('user')">👤</button>
              <div class="nav-menu compact right-aligned">
                <RouterLink class="shell-user-link" to="/account" @click="closeMenus">用户设置</RouterLink>
                <button type="button" @click="handleLogout">注销退出</button>
              </div>
            </div>
          </template>
        </nav>
      </div>
    </header>
    <main class="page-shell shell-main" :class="mainModeClass">
      <AppBreadcrumbs />
      <RouterView v-slot="{ Component, route: activeRoute }">
        <Transition name="page-fade" mode="out-in">
          <component :is="Component" :key="activeRoute.fullPath" />
        </Transition>
      </RouterView>
    </main>
    <Transition name="back-top-fade">
      <button v-if="showBackTop" class="back-top-button" type="button" aria-label="回到页面顶部" @click="scrollToTop">
        <span>↑</span>
        <small>顶部</small>
      </button>
    </Transition>
    <AppToastHost />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import AppBreadcrumbs from '@/components/AppBreadcrumbs.vue'
import AppToastHost from '@/components/AppToastHost.vue'
import type { AppSummary } from '@/api/types'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const projectStore = useProjectStore()
const { currentUser } = storeToRefs(authStore)
const { projects } = storeToRefs(projectStore)
const projectKeyword = ref('')
const appKeyword = ref('')
const shellRef = ref<HTMLElement | null>(null)
const showBackTop = ref(false)
const openMenu = ref<'app' | 'create' | 'project' | 'user' | ''>('')
const projectId = computed(() => typeof route.params.projectId === 'string' ? route.params.projectId : '')
const routeAppId = computed(() => typeof route.params.appId === 'string' ? route.params.appId : '')
const context = computed(() => projectId.value ? projectStore.contextByProjectId[projectId.value] : undefined)
const apps = computed(() => context.value?.apps || projectStore.appsByProjectId[projectId.value] || [])
const currentProjectName = computed(() => context.value?.project.name || projects.value.find((project) => project.id === projectId.value)?.name || '项目列表')
const filteredProjects = computed(() => {
  const keyword = projectKeyword.value.toLowerCase()
  return projects.value.filter((project) => !keyword || `${project.name} ${project.describe || ''}`.toLowerCase().includes(keyword))
})
const filteredApps = computed(() => {
  const keyword = appKeyword.value.toLowerCase()
  return apps.value.filter((app) => !keyword || `${app.name} ${app.language || ''} ${app.describe || ''}`.toLowerCase().includes(keyword)).slice(0, 12)
})
const mainModeClass = computed(() => {
  const name = String(route.name || '')
  if (['map-home', 'map-app', 'map-code', 'verification-workspace', 'git-impact'].includes(name)) {
    return 'shell-main-wide'
  }
  if (name.includes('graph') || name.includes('code')) {
    return 'shell-main-focus'
  }
  return ''
})

watch(projectId, async (value) => {
  if (!value) return
  await projectStore.loadProjectContext(value).catch(() => undefined)
}, { immediate: true })

watch(() => route.fullPath, () => {
  closeMenus()
  requestAnimationFrame(updateBackTopVisibility)
})

function toggleMenu(name: typeof openMenu.value) {
  openMenu.value = openMenu.value === name ? '' : name
}

function openNavMenu(name: typeof openMenu.value) {
  openMenu.value = name
}

function closeMenus() {
  openMenu.value = ''
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target as Node | null
  if (shellRef.value && target && !shellRef.value.contains(target)) {
    closeMenus()
  }
}

function updateBackTopVisibility() {
  if (typeof window === 'undefined') return
  showBackTop.value = window.scrollY > 420
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  if (currentUser.value && projects.value.length === 0) {
    projectStore.loadProjects().catch(() => undefined)
  }
  document.addEventListener('click', handleDocumentClick)
  window.addEventListener('scroll', updateBackTopVisibility, { passive: true })
  updateBackTopVisibility()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleDocumentClick)
  window.removeEventListener('scroll', updateBackTopVisibility)
})

function appLanguageText(app: AppSummary) {
  return app.language || app.sourceType || '源码'
}

function appTitle(app: AppSummary) {
  return `${app.name}\n语言: ${app.language || app.sourceType || '-'}\n仓库: ${app.repoConfigured ? '已配置' : '未配置'}`
}

async function handleLogout() {
  await authStore.logout()
  await router.push('/login')
}
</script>

<style scoped>
.shell {
  min-height: 100vh;
}

.shell-header {
  position: sticky;
  top: 0;
  z-index: 900;
  background: rgba(255, 255, 255, .82);
  border-bottom: 1px solid rgba(15, 23, 42, .07);
  box-shadow: 0 10px 30px rgba(15, 23, 42, .06);
  backdrop-filter: saturate(180%) blur(18px);
}

.shell-header-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  min-height: 64px;
  padding: 10px 0;
}

.brand-block {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 10px;
  min-width: 188px;
  min-height: var(--oat-min-target);
  border-radius: 14px;
  padding: 4px 12px 4px 0;
  transition: transform .16s ease, background .16s ease;
}

.brand-block:hover {
  background: rgba(var(--oat-primary-rgb), .05);
  transform: translateY(-1px);
}

.brand-emblem {
  width: 30px;
  height: 30px;
  object-fit: contain;
}

.brand-logo-image {
  display: block;
  width: 145px;
  max-width: 145px;
  height: 26px;
  object-fit: contain;
}

.shell-nav {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.shell-nav > a,
.nav-dropdown-trigger,
.project-trigger,
.icon-trigger {
  min-height: var(--oat-min-target);
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  background: transparent;
  color: var(--oat-text);
  font: inherit;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}

.shell-nav > a:hover,
.nav-dropdown:hover .nav-dropdown-trigger,
.project-trigger:hover,
.icon-trigger:hover,
.shell-nav > a.router-link-active,
.shell-nav > a.router-link-exact-active,
.nav-dropdown.open .nav-dropdown-trigger {
  background: rgba(var(--oat-primary-rgb), .10);
  color: var(--oat-primary-dark);
}

.menu-caret {
  margin-left: 3px;
  color: #64748b;
  font-size: 13px;
}

.shell-user-link {
  color: #5b6b79;
  font-weight: 600;
}

.nav-dropdown {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding-bottom: 10px;
  margin-bottom: -10px;
}

.icon-trigger {
  display: inline-grid;
  place-items: center;
  min-width: var(--oat-min-target);
  padding: 0;
  color: var(--oat-text);
}

.project-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 230px;
  color: var(--oat-text);
}

.nav-menu {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 160;
  min-width: 220px;
  display: none;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, .09);
  border-radius: 18px;
  background: rgba(255, 255, 255, .96);
  box-shadow: var(--oat-shadow-lg);
  backdrop-filter: saturate(180%) blur(20px);
}

.nav-dropdown:hover .nav-menu,
.nav-dropdown:focus-within .nav-menu,
.nav-dropdown.open .nav-menu {
  display: grid;
  gap: 0;
}

.nav-menu.right-aligned {
  left: auto;
  right: 0;
}

.nav-menu a,
.nav-menu button,
.empty-menu-item,
.menu-entry {
  display: block;
  width: 100%;
  border: none;
  border-radius: 12px;
  padding: 11px 14px;
  background: transparent;
  color: var(--oat-text);
  text-align: left;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.nav-menu a:hover,
.nav-menu button:hover,
.app-menu-item:hover {
  background: rgba(var(--oat-primary-rgb), .09);
  color: var(--oat-primary-dark);
}

.nav-menu.compact {
  min-width: 160px;
}

.project-menu {
  width: min(340px, calc(100vw - 24px));
  min-width: 280px;
  max-height: min(420px, calc(100vh - 112px));
  overflow: auto;
  overscroll-behavior: contain;
}

.project-menu a {
  display: flex;
  align-items: center;
  min-height: var(--oat-min-target);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-switcher .menu-search {
  position: sticky;
  top: 0;
  z-index: 1;
  background: rgba(255, 255, 255, .98);
}

.app-menu {
  width: 408px;
  max-width: calc(100vw - 24px);
  max-height: min(480px, calc(100vh - 120px));
  overflow: auto;
}

.menu-search {
  width: calc(100% - 22px);
  margin: 7px 11px 5px;
  border: 1px solid rgba(15, 23, 42, .12);
  border-radius: 14px;
  padding: 10px 12px;
  outline: none;
  font: inherit;
  color: var(--oat-text);
  background: rgba(248, 250, 252, .9);
}

.menu-search:focus {
  border-color: rgba(var(--oat-primary-rgb), .55);
  background: #fff;
  box-shadow: var(--oat-focus-ring);
}

.menu-divider {
  height: 1px;
  margin: 5px 0;
  background: rgba(15, 23, 42, .08);
}

.app-menu-item {
  position: relative;
  display: flex !important;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  border-radius: 12px;
}

.app-menu-item small {
  color: #64748b;
  font-size: 12px;
}

.app-main-link {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub-menu-actions {
  position: absolute;
  top: 50%;
  right: 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%);
  transition: opacity .12s ease;
}

.app-menu-item:hover .sub-menu-actions,
.app-menu-item:focus-within .sub-menu-actions {
  opacity: 1;
  pointer-events: auto;
}

.sub-menu-actions a {
  width: auto;
  min-width: 28px;
  padding: 4px 7px;
  border-radius: 999px;
  text-align: center;
  color: var(--oat-text);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
}

.sub-menu-actions a:hover {
  background: rgba(15, 118, 110, .08);
}

.empty-menu-item {
  color: #94a3b8;
  cursor: default;
}

.shell-main {
  padding: clamp(12px, 1.4vw, 18px) 0 60px;
}

.shell-main-wide {
  width: min(1560px, calc(100vw - clamp(24px, 4vw, 48px)));
}

.shell-main-ai {
  padding-bottom: 8px;
}

.shell-main-focus {
  width: min(1440px, calc(100vw - clamp(24px, 4vw, 48px)));
}

.back-top-button {
  position: fixed;
  right: 26px;
  bottom: 94px;
  z-index: 850;
  display: inline-grid;
  place-items: center;
  gap: 2px;
  width: 50px;
  height: 50px;
  border: 1px solid rgba(var(--oat-primary-rgb), .20);
  border-radius: 999px;
  background: rgba(255, 255, 255, .92);
  color: var(--oat-primary-dark);
  box-shadow: var(--oat-shadow-md);
  backdrop-filter: saturate(180%) blur(16px);
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease, background .18s ease;
}

.back-top-button span {
  font-size: 18px;
  line-height: 1;
}

.back-top-button small {
  font-size: 10px;
  font-weight: 800;
}

.back-top-button:hover {
  border-color: rgba(var(--oat-primary-rgb), .42);
  background: #fff;
  box-shadow: var(--oat-shadow-lg);
  transform: translateY(-3px);
}

.back-top-button:active {
  transform: translateY(0) scale(.96);
}

.back-top-fade-enter-active,
.back-top-fade-leave-active {
  transition: opacity .18s ease, transform .18s ease;
}

.back-top-fade-enter-from,
.back-top-fade-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(.94);
}

@media (max-width: 900px) {
  .shell-header-inner {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .shell-nav {
    width: 100%;
    gap: 4px;
    justify-content: flex-start;
    overflow-x: auto;
    padding-bottom: 4px;
    scrollbar-width: none;
  }

  .shell-nav::-webkit-scrollbar {
    display: none;
  }

  .nav-dropdown {
    position: static;
  }

  .nav-menu {
    left: 12px;
    right: 12px;
    width: auto;
    max-width: calc(100vw - 24px);
  }

  .nav-menu.right-aligned {
    left: 12px;
    right: 12px;
  }

  .back-top-button {
    right: 16px;
    bottom: 82px;
  }
}
</style>
