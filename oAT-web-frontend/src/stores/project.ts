import { ref } from 'vue'
import type { Ref } from 'vue'
import { defineStore } from 'pinia'

import {
  addProjectMembers,
  createProject,
  createProjectApp,
  askAiInteractive,
  clearAiSessionState,
  createUsecaseDirectory,
  deleteProjectLabel,
  deleteProject,
  deleteProjectApp,
  deleteUsecase,
  deleteUsecaseDirectory,
  fetchAppSettings,
  fetchAiInteractiveContext,
  fetchCollectorSources,
  fetchProjectApps,
  fetchProjectContext,
  fetchProjectLabels,
  fetchProjectMembers,
  fetchProjects,
  fetchRepositoryBranches,
  fetchRepositoryConfig,
  fetchUsecaseDirectoryDeletePreview,
  fetchUsecaseBootstrap,
  fetchUsecaseDetail,
  fetchUsecaseList,
  rebuildUsecaseSearchData,
  removeProjectMember,
  renameUsecaseDirectory,
  saveAiSessionState,
  saveAppSettings,
  saveRepositoryConfig,
  saveUsecase,
  updateUsecaseShare,
  uploadUsecases,
  updateProject,
  updateProjectMemberRole,
  upsertProjectLabel,
} from '@/api/bootstrap'
import type {
  AIInteractivePagePayload,
  AIInteractiveReply,
  AppSummary,
  AppSettingsPayload,
  CollectorSourcesPayload,
  DirectoryDeletePreview,
  ProjectContext,
  ProjectLabelsPayload,
  ProjectMembersPayload,
  ProjectSummary,
  RepositoryConfigPayload,
  UsecaseBootstrapPayload,
  UsecaseDetailPayload,
  UsecaseListPayload,
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
  const usecaseListByProjectId = ref<Record<string, UsecaseListPayload>>({})
  const usecaseBootstrapByKey = ref<Record<string, UsecaseBootstrapPayload>>({})
  const usecaseDetailByKey = ref<Record<string, UsecaseDetailPayload>>({})
  const aiContextByProjectId = ref<Record<string, AIInteractivePagePayload>>({})
  const aiLastReplyByProjectId = ref<Record<string, AIInteractiveReply>>({})
  const aiAssistantLastReplyByProjectId = ref<Record<string, AIInteractiveReply>>({})

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

  async function loadUsecaseList(
    projectId: string,
    params?: { directory?: string; sort?: string; keyword?: string },
  ) {
    return loadRecordByKey(usecaseListByProjectId, projectId, () => fetchUsecaseList(projectId, params))
  }

  function usecaseKey(projectId: string, usecaseId: string) {
    return `${projectId}:${usecaseId}`
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

  async function loadUsecaseBootstrap(projectId: string, params?: { directory?: string; id?: string }) {
    const key = usecaseKey(projectId, params?.id || 'new')
    return loadRecordByKey(usecaseBootstrapByKey, key, () => fetchUsecaseBootstrap(projectId, params))
  }

  async function loadUsecaseDetail(projectId: string, usecaseId: string) {
    return loadRecordByKey(
      usecaseDetailByKey,
      usecaseKey(projectId, usecaseId),
      () => fetchUsecaseDetail(projectId, usecaseId),
    )
  }

  async function persistUsecase(
    projectId: string,
    payload: Parameters<typeof saveUsecase>[1],
  ) {
    const usecaseId = await saveUsecase(projectId, payload)
    return usecaseId
  }

  async function removeUsecase(projectId: string, usecaseId: string) {
    return deleteUsecase(projectId, usecaseId)
  }

  async function changeUsecaseShare(projectId: string, usecaseId: string, share: boolean) {
    return updateUsecaseShare(projectId, usecaseId, share)
  }

  async function importUsecases(projectId: string, directory: string, file: File) {
    return uploadUsecases(projectId, directory, file)
  }

  async function rebuildUsecaseSearch(projectId: string) {
    return rebuildUsecaseSearchData(projectId)
  }

  async function addUsecaseDirectory(projectId: string, payload: { parentId?: string; name: string }) {
    return createUsecaseDirectory(projectId, payload)
  }

  async function updateUsecaseDirectory(
    projectId: string,
    directoryId: string,
    payload: { parentId: string; name: string },
  ) {
    return renameUsecaseDirectory(projectId, directoryId, payload)
  }

  async function previewUsecaseDirectoryDelete(projectId: string, directoryId: string) {
    return fetchUsecaseDirectoryDeletePreview(projectId, directoryId)
  }

  async function removeUsecaseDirectory(
    projectId: string,
    directoryId: string,
    payload: { parentId: string; name: string; deleteUsecases: boolean },
  ) {
    return deleteUsecaseDirectory(projectId, directoryId, payload)
  }

  async function loadAiContext(projectId: string) {
    return loadRecordByKey(aiContextByProjectId, projectId, () => fetchAiInteractiveContext(projectId))
  }

  async function askAi(projectId: string, payload: Parameters<typeof askAiInteractive>[1]) {
    const reply = await askAiInteractive(projectId, payload)
    if (payload.memoryScope === 'assistant') {
      assignByKey(aiAssistantLastReplyByProjectId, projectId, reply)
    } else {
      assignByKey(aiLastReplyByProjectId, projectId, reply)
    }
    if (typeof reply.sessionState === 'string') {
      const current = aiContextByProjectId.value[projectId]
      if (current) {
        assignByKey(aiContextByProjectId, projectId, { ...current, sessionState: reply.sessionState })
      }
    }
    return reply
  }

  async function persistAiSessionState(projectId: string, sessionState: string) {
    const saved = await saveAiSessionState(projectId, sessionState)
    const current = aiContextByProjectId.value[projectId]
    if (current) {
      assignByKey(aiContextByProjectId, projectId, { ...current, sessionState: saved })
    }
    return saved
  }

  async function resetAiSessionState(projectId: string, memoryScope: 'workbench' | 'assistant' = 'workbench') {
    const cleared = await clearAiSessionState(projectId, memoryScope)
    if (memoryScope === 'workbench') {
      const current = aiContextByProjectId.value[projectId]
      if (current) {
        assignByKey(aiContextByProjectId, projectId, { ...current, sessionState: cleared })
      }
      assignByKey(aiLastReplyByProjectId, projectId, {})
    } else {
      assignByKey(aiAssistantLastReplyByProjectId, projectId, {})
    }
    return cleared
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
    usecaseListByProjectId,
    usecaseBootstrapByKey,
    usecaseDetailByKey,
    aiContextByProjectId,
    aiLastReplyByProjectId,
    aiAssistantLastReplyByProjectId,
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
    loadUsecaseList,
    loadUsecaseBootstrap,
    loadUsecaseDetail,
    persistUsecase,
    removeUsecase,
    changeUsecaseShare,
    importUsecases,
    rebuildUsecaseSearch,
    addUsecaseDirectory,
    updateUsecaseDirectory,
    previewUsecaseDirectoryDelete,
    removeUsecaseDirectory,
    loadAiContext,
    askAi,
    persistAiSessionState,
    resetAiSessionState,
  }
})
