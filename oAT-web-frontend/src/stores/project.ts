import { ref } from 'vue'
import type { Ref } from 'vue'
import { defineStore } from 'pinia'

import {
  addProjectMembers,
  createProject,
  createProjectApp,
  deleteProjectLabel,
  deleteProject,
  deleteProjectApp,
  fetchAppSettings,
  fetchCollectorSources,
  fetchProjectApps,
  fetchProjectContext,
  fetchProjectLabels,
  fetchProjectMembers,
  fetchProjects,
  fetchRepositoryBranches,
  fetchRepositoryConfig,
  removeProjectMember,
  saveAppSettings,
  saveRepositoryConfig,
  updateProject,
  updateProjectMemberRole,
  upsertProjectLabel,
} from '@/api/bootstrap'
import type {
  AppSummary,
  AppSettingsPayload,
  CollectorSourcesPayload,
  ProjectContext,
  ProjectLabelsPayload,
  ProjectMembersPayload,
  ProjectSummary,
  RepositoryConfigPayload,
} from '@/api/types'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectSummary[]>([])
  const contextByProjectId = ref<Record<string, ProjectContext>>({})
  const appsByProjectId = ref<Record<string, AppSummary[]>>({})
  const membersByProjectId = ref<Record<string, ProjectMembersPayload>>({})
  const labelsByProjectId = ref<Record<string, ProjectLabelsPayload>>({})
  const collectorSourcesByProjectId = ref<Record<string, CollectorSourcesPayload>>({})
  const appSettingsByKey = ref<Record<string, AppSettingsPayload>>({})
  const repositoryByKey = ref<Record<string, RepositoryConfigPayload>>({})

  async function loadProjects() {
    projects.value = await fetchProjects()
    return projects.value
  }

  async function createManagedProject(payload: { name: string; describe?: string }) {
    const project = await createProject(payload)
    projects.value = [...projects.value, project]
    return project
  }

  async function updateManagedProject(projectId: string, payload: { name: string; describe?: string }) {
    const updated = await updateProject(projectId, payload)
    projects.value = projects.value.map((project) => (project.id === projectId ? updated : project))
    const currentContext = contextByProjectId.value[projectId]
    if (currentContext) {
      assignByKey(contextByProjectId, projectId, { ...currentContext, project: updated })
    }
    return updated
  }

  async function removeManagedProject(projectId: string, password: string) {
    await deleteProject(projectId, { password })
    projects.value = projects.value.filter((project) => project.id !== projectId)
  }

  async function loadProjectContext(projectId: string) {
    return loadRecordByKey(contextByProjectId, projectId, () => fetchProjectContext(projectId))
  }

  async function loadProjectApps(projectId: string) {
    return loadRecordByKey(appsByProjectId, projectId, () => fetchProjectApps(projectId))
  }

  async function createManagedApp(
    projectId: string,
    payload: {
      name: string
      srcName?: string
      language?: string
      languageConfig?: string
      range?: string
      describe?: string
      properties?: string
      currentVersion?: string
      currentBranch?: string
      currentCommitId?: string
    },
  ) {
    const app = await createProjectApp(projectId, payload)
    assignByKey(appsByProjectId, projectId, [...(appsByProjectId.value[projectId] || []), app])
    const currentContext = contextByProjectId.value[projectId]
    if (currentContext) {
      assignByKey(contextByProjectId, projectId, {
        ...currentContext,
        apps: [...currentContext.apps, app],
        appCount: currentContext.appCount + 1,
      })
    }
    return app
  }

  async function removeManagedApp(projectId: string, appId: string, password: string) {
    await deleteProjectApp(projectId, appId, { password })
    const currentApps = appsByProjectId.value[projectId] || []
    assignByKey(
      appsByProjectId,
      projectId,
      currentApps.filter((app) => app.id !== appId),
    )
    const currentContext = contextByProjectId.value[projectId]
    if (currentContext) {
      const remainingApps = currentContext.apps.filter((app) => app.id !== appId)
      assignByKey(contextByProjectId, projectId, {
        ...currentContext,
        apps: remainingApps,
        appCount: remainingApps.length,
        onlineAppCount: remainingApps.filter((app) => app.onlineCount > 0).length,
      })
    }
  }

  async function loadProjectMembers(projectId: string) {
    return loadRecordByKey(membersByProjectId, projectId, () => fetchProjectMembers(projectId))
  }

  async function loadCollectorSources(projectId: string) {
    return loadRecordByKey(collectorSourcesByProjectId, projectId, () => fetchCollectorSources(projectId))
  }

  async function addMembers(projectId: string, userIds: string[]) {
    return loadRecordByKey(membersByProjectId, projectId, () => addProjectMembers(projectId, userIds))
  }

  async function deleteMember(projectId: string, projectMemberId: string) {
    return loadRecordByKey(membersByProjectId, projectId, () => removeProjectMember(projectId, projectMemberId))
  }

  async function changeMemberRole(projectId: string, projectMemberId: string, role: string) {
    return loadRecordByKey(
      membersByProjectId,
      projectId,
      () => updateProjectMemberRole(projectId, projectMemberId, role),
    )
  }

  async function loadProjectLabels(projectId: string) {
    return loadRecordByKey(labelsByProjectId, projectId, () => fetchProjectLabels(projectId))
  }

  async function saveProjectLabel(projectId: string, type: string, name: string, color: string) {
    return loadRecordByKey(
      labelsByProjectId,
      projectId,
      () => upsertProjectLabel(projectId, { type, name, color }),
    )
  }

  async function removeLabel(projectId: string, type: string, name: string) {
    return loadRecordByKey(
      labelsByProjectId,
      projectId,
      () => deleteProjectLabel(projectId, { type, name }),
    )
  }

  function appKey(projectId: string, appId: string) {
    return `${projectId}:${appId}`
  }

  async function loadAppSettings(projectId: string, appId: string) {
    return loadRecordByKey(appSettingsByKey, appKey(projectId, appId), () => fetchAppSettings(projectId, appId))
  }

  async function updateAppSettings(
    projectId: string,
    appId: string,
    payload: Parameters<typeof saveAppSettings>[2],
  ) {
    return loadRecordByKey(
      appSettingsByKey,
      appKey(projectId, appId),
      () => saveAppSettings(projectId, appId, payload),
    )
  }

  async function loadRepository(projectId: string, appId: string) {
    return loadRecordByKey(repositoryByKey, appKey(projectId, appId), () => fetchRepositoryConfig(projectId, appId))
  }

  async function updateRepository(
    projectId: string,
    appId: string,
    payload: Parameters<typeof saveRepositoryConfig>[2],
  ) {
    return loadRecordByKey(
      repositoryByKey,
      appKey(projectId, appId),
      () => saveRepositoryConfig(projectId, appId, payload),
    )
  }

  async function loadRepositoryBranches(projectId: string, appId: string) {
    return fetchRepositoryBranches(projectId, appId)
  }

  function assignByKey<T>(target: Ref<Record<string, T>>, key: string, payload: T) {
    target.value = {
      ...target.value,
      [key]: payload,
    }
    return payload
  }

  async function loadRecordByKey<T>(target: Ref<Record<string, T>>, key: string, loader: () => Promise<T>) {
    const payload = await loader()
    return assignByKey(target, key, payload)
  }

  return {
    projects,
    contextByProjectId,
    appsByProjectId,
    membersByProjectId,
    labelsByProjectId,
    collectorSourcesByProjectId,
    appSettingsByKey,
    repositoryByKey,
    loadProjects,
    createManagedProject,
    updateManagedProject,
    removeManagedProject,
    loadProjectContext,
    loadProjectApps,
    createManagedApp,
    removeManagedApp,
    loadProjectMembers,
    addMembers,
    deleteMember,
    changeMemberRole,
    loadProjectLabels,
    saveProjectLabel,
    removeLabel,
    loadCollectorSources,
    loadAppSettings,
    updateAppSettings,
    loadRepository,
    updateRepository,
    loadRepositoryBranches,
  }
})
