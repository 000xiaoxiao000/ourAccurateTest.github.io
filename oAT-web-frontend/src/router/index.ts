import { createRouter, createWebHistory } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    if (to.hash) {
      return { el: to.hash, top: 84, behavior: 'smooth' }
    }
    if (to.path !== from.path) {
      return { top: 0, behavior: 'smooth' }
    }
    return false
  },
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { public: true },
    },
    {
      path: '/share/usecase/:usecaseId',
      name: 'share-usecase',
      component: () => import('@/pages/ShareUsecasePage.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: AppShell,
      children: [
        {
          path: '',
          redirect: '/projects',
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/pages/ProjectsPage.vue'),
        },
        {
          path: 'account',
          name: 'account-settings',
          component: () => import('@/pages/AccountSettingsPage.vue'),
        },
        {
          path: 'p/:projectId/home',
          name: 'project-home',
          component: () => import('@/pages/ProjectHomePage.vue'),
        },
        {
          path: 'p/:projectId/verification',
          name: 'verification-workspace',
          component: () => import('@/pages/VerificationWorkspacePage.vue'),
        },
        {
          path: 'p/:projectId/verification/connections',
          name: 'data-connections',
          component: () => import('@/pages/DataConnectionPage.vue'),
        },
        {
          path: 'p/:projectId/verification/baselines/:baselineId/matrix',
          name: 'traceability-matrix',
          component: () => import('@/pages/TraceabilityMatrixPage.vue'),
        },
        {
          path: 'p/:projectId/verification/baselines/:baselineId/findings',
          name: 'finding-review',
          component: () => import('@/pages/FindingReviewPage.vue'),
        },
        {
          path: 'p/:projectId/verification/baselines/:baselineId/coverage',
          name: 'coverage-execution',
          component: () => import('@/pages/CoverageExecutionPage.vue'),
        },
        {
          path: 'p/:projectId/verification/baselines/:baselineId/gate',
          name: 'quality-gate',
          component: () => import('@/pages/QualityGatePage.vue'),
        },
        {
          path: 'p/:projectId/apps',
          name: 'project-apps',
          component: () => import('@/pages/ProjectAppsPage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/settings',
          name: 'app-settings',
          component: () => import('@/pages/AppSettingsPage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/repository',
          name: 'app-repository',
          component: () => import('@/pages/RepositoryConfigPage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/api-endpoints',
          name: 'app-api-endpoints',
          component: () => import('@/pages/ApiEndpointsPage.vue'),
        },
        {
          path: 'p/:projectId/version/apps',
          name: 'version-apps',
          component: () => import('@/pages/VersionAppsPage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/versions',
          name: 'version-list',
          component: () => import('@/pages/VersionListPage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/versions/new',
          name: 'version-create',
          component: () => import('@/pages/VersionCreatePage.vue'),
        },
        {
          path: 'p/:projectId/apps/:appId/compare',
          name: 'version-compare',
          component: () => import('@/pages/VersionComparePage.vue'),
        },
        {
          path: 'p/:projectId/version/reports/:reportId',
          name: 'version-report-detail',
          component: () => import('@/pages/VersionReportDetailPage.vue'),
        },
        {
          path: 'p/:projectId/map/home',
          name: 'map-home',
          component: () => import('@/pages/MapHomePage.vue'),
        },
        {
          path: 'p/:projectId/map/app/:appId',
          name: 'map-app',
          component: () => import('@/pages/MapAppPage.vue'),
        },
        {
          path: 'p/:projectId/map/code',
          name: 'map-code',
          component: () => import('@/pages/MapCodePage.vue'),
        },
        {
          path: 'p/:projectId/search',
          name: 'search-center',
          component: () => import('@/pages/SearchCenterPage.vue'),
        },
        {
          path: 'p/:projectId/usecases',
          name: 'usecase-list',
          component: () => import('@/pages/UsecaseListPage.vue'),
        },
        {
          path: 'p/:projectId/usecases/new',
          name: 'usecase-new',
          component: () => import('@/pages/UsecaseEditorPage.vue'),
        },
        {
          path: 'p/:projectId/usecases/:usecaseId/edit',
          name: 'usecase-edit',
          component: () => import('@/pages/UsecaseEditorPage.vue'),
        },
        {
          path: 'p/:projectId/usecases/:usecaseId',
          name: 'usecase-detail',
          component: () => import('@/pages/UsecaseDetailPage.vue'),
        },
        {
          path: 'p/:projectId/members',
          name: 'project-members',
          component: () => import('@/pages/ProjectMembersPage.vue'),
        },
        {
          path: 'p/:projectId/labels',
          name: 'project-labels',
          component: () => import('@/pages/ProjectLabelsPage.vue'),
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/pages/NotFoundPage.vue'),
      meta: { public: true },
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  const authStore = useAuthStore()

  try {
    await authStore.ensureLoaded()
  } catch (error) {
    if (isAuthRequired(error)) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    throw error
  }

  if (!authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  return true
})

export default router

function isAuthRequired(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    return true
  }
  if (typeof error === 'object' && error !== null) {
    const maybeError = error as { status?: unknown; code?: unknown }
    return maybeError.status === 401 || maybeError.code === 'AUTH_REQUIRED'
  }
  return false
}
