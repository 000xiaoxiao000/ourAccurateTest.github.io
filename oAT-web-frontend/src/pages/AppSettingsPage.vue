<template>
  <section>
    <div class="page-header">
      <div>
        <div class="eyebrow">Source Project</div>
        <h1>源码工程设置</h1>
      </div>
      <button class="action-button" type="button" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="status-card">正在加载源码工程设置...</div>
    <div v-else-if="error" class="status-card error">{{ error }}</div>
    <template v-else-if="payload">
      <form class="form-card" @submit.prevent="save">
        <label class="block app-id-field">
          <span>源码工程 Id</span>
          <div class="copy-input-row">
            <input class="text-input" type="text" :value="displayAppId" readonly />
            <button class="secondary-button copy-button" type="button" @click="copyAppId">复制</button>
          </div>
        </label>

        <div class="form-grid">
          <label>
            <span>工程名称</span>
            <input v-model="form.name" class="text-input" type="text" />
          </label>
          <label>
            <span>主语言</span>
            <select v-model="form.language" class="select">
              <option v-for="option in languageOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
        </div>

        <label class="block">
          <span>工程说明</span>
          <textarea v-model="form.describe" class="text-area" rows="3"></textarea>
        </label>

        <LanguageConfigForm v-model="form.languageConfig" :language="form.language" />

        <div class="actions">
          <button class="primary-button" type="submit">保存</button>
          <RouterLink class="secondary-link" :to="`/p/${projectId}/apps/${appId}/repository`">
            仓库配置
          </RouterLink>
          <button class="danger-button" type="button" @click="deleteDialogOpen = true">删除源码工程</button>
        </div>
      </form>
    </template>

    <div v-if="deleteDialogOpen" class="modal-mask" @click.self="deleteDialogOpen = false">
      <form class="modal-card" @submit.prevent="removeApp">
        <h2>删除源码工程</h2>
        <p class="subtext">此操作会删除当前源码工程及相关配置，请输入当前账号密码确认。</p>
        <input v-model="deletePassword" class="text-input" type="password" placeholder="请输入密码" />
        <p v-if="deleteError" class="error-text">{{ deleteError }}</p>
        <div class="actions">
          <button class="danger-button solid" type="submit" :disabled="loading">确认删除</button>
          <button class="secondary-button" type="button" @click="deleteDialogOpen = false">取消</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useProjectStore } from '@/stores/project'
import { useToast } from '@/composables/useToast'
import LanguageConfigForm from '@/features/app/LanguageConfigForm.vue'
import { languageOptions } from '@/features/app/languageProfiles'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const toast = useToast()
const projectId = computed(() => String(route.params.projectId || ''))
const appId = computed(() => String(route.params.appId || ''))
const storeKey = computed(() => `${projectId.value}:${appId.value}`)
const payload = computed(() => projectStore.appSettingsByKey[storeKey.value])
const displayAppId = computed(() => payload.value?.app?.id || appId.value)
const loading = ref(false)
const error = ref('')
const deleteDialogOpen = ref(false)
const deletePassword = ref('')
const deleteError = ref('')

const form = reactive({
  name: '',
  language: 'JAVA',
  languageConfig: '{}',
  describe: '',
})

function syncForm() {
  if (!payload.value) {
    return
  }
  const app = payload.value.app
  form.name = app.name || ''
  form.language = app.language || 'JAVA'
  form.languageConfig = app.languageConfig || '{}'
  form.describe = app.describe || ''
}

async function copyAppId() {
  if (!displayAppId.value) {
    toast.warning('没有可复制的源码工程 Id')
    return
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(displayAppId.value)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = displayAppId.value
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      textarea.remove()
    }
    toast.success('源码工程 Id 已复制')
  } catch {
    toast.error('复制源码工程 Id 失败')
  }
}

async function load() {
  if (!projectId.value || !appId.value) {
    error.value = '缺少 projectId 或 appId'
    return
  }
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await projectStore.loadAppSettings(projectId.value, appId.value)
    syncForm()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载源码工程设置失败'
  } finally {
    loading.value = false
  }
}

async function removeApp() {
  if (loading.value) return
  if (!deletePassword.value) {
    deleteError.value = '请输入密码'
    return
  }
  loading.value = true
  deleteError.value = ''
  try {
    await projectStore.removeManagedApp(projectId.value, appId.value, deletePassword.value)
    await router.push(`/p/${projectId.value}/apps`)
  } catch (err) {
    deleteError.value = err instanceof Error ? err.message : '删除源码工程失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await projectStore.updateAppSettings(projectId.value, appId.value, normalizeFormForSubmit())
    syncForm()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '保存源码工程设置失败'
  } finally {
    loading.value = false
  }
}

function normalizeFormForSubmit() {
  return {
    ...form,
    srcName: form.name,
    range: 'only',
    properties: '',
    currentVersion: payload.value?.app.currentVersion || '',
    currentBranch: payload.value?.app.currentBranch || '',
    currentCommitId: payload.value?.app.currentCommitId || '',
  }
}

onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}


.action-button,
.primary-button,
.secondary-button,
.danger-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  color: #fff;
  cursor: pointer;
}

.secondary-button {
  color: #0f766e;
  background: rgba(15, 118, 110, .08);
}

.danger-button {
  color: #b91c1c;
  background: rgba(185, 28, 28, .1);
}

.danger-button.solid {
  background: #b91c1c;
  color: #fff;
}

.action-button {
  background: #0f172a;
}

.primary-button {
  background: #0f766e;
}

.secondary-link {
  color: #0f766e;
  font-weight: 700;
}

.status-card,
.form-card,
.modal-card {
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.status-card.error,
.error-text {
  color: #b91c1c;
}

.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .44);
}

.modal-card {
  width: min(460px, calc(100vw - 32px));
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.block,
.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block {
  margin-top: 16px;
}

.app-id-field {
  margin-top: 0;
}

.copy-input-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.copy-button {
  min-width: 72px;
  white-space: nowrap;
}

.text-input,
.text-area,
.select {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}

.subsection {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid rgba(15, 23, 42, 0.08);
}

.checkbox-row,
.checkbox-group {
  display: flex;
  gap: 12px;
  align-items: center;
}

.checkbox-group {
  flex-wrap: wrap;
  margin-top: 14px;
}

.helper-text {
  margin: 10px 0 0;
  color: #64748b;
  font-size: 13px;
}

.dashboard {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.metric {
  padding: 14px;
  border-radius: 16px;
  background: #f8fbfb;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.metric strong {
  display: block;
  font-size: 22px;
}

.metric span {
  color: #64748b;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-top: 20px;
}

@media (max-width: 900px) {
  .form-grid,
  .dashboard {
    grid-template-columns: 1fr;
  }

  .copy-input-row {
    grid-template-columns: 1fr;
  }
}
</style>
