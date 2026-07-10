import { computed, ref } from 'vue'

type ProjectLanguage = 'JAVA' | 'FRONTEND' | 'GO' | 'PYTHON' | 'CPP'

const projectId = ref('')
const appId = ref('')
const versionNumber = ref('')
const commitId = ref('')
const language = ref<ProjectLanguage | 'ALL'>('ALL')

export function useProjectContext() {
  const hasProject = computed(() => Boolean(projectId.value))

  function setProjectContext(next: {
    projectId?: string
    appId?: string
    versionNumber?: string
    commitId?: string
    language?: ProjectLanguage | 'ALL'
  }) {
    if (next.projectId !== undefined) projectId.value = next.projectId
    if (next.appId !== undefined) appId.value = next.appId
    if (next.versionNumber !== undefined) versionNumber.value = next.versionNumber
    if (next.commitId !== undefined) commitId.value = next.commitId
    if (next.language !== undefined) language.value = next.language
  }

  return {
    projectId,
    appId,
    versionNumber,
    commitId,
    language,
    hasProject,
    setProjectContext,
  }
}
