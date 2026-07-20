<template>
  <section class="account-page">
    <div class="page-header">
      <div>
        <div class="eyebrow">Account</div>
        <h1>账号设置</h1>
      </div>
      <AppRefreshButton :loading="loading" @click="load" />
    </div>

    <div class="tab-row">
      <button
        class="tab-button"
        :class="{ active: activeTab === 'profile' }"
        type="button"
        @click="setTab('profile')"
      >
        基本信息
      </button>
      <button
        class="tab-button"
        :class="{ active: activeTab === 'password' }"
        type="button"
        @click="setTab('password')"
      >
        更改密码
      </button>
    </div>

    <div v-if="loading" class="status-card">正在加载账号信息...</div>
    <div v-else class="layout">
      <form v-if="activeTab === 'profile'" class="panel" @submit.prevent="saveProfile">
        <h2>基本信息</h2>
        <label class="field">
          <span>用户名称</span>
          <input v-model.trim="profileForm.name" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>邮箱地址</span>
          <input v-model.trim="profileForm.email" class="text-input" type="email" />
        </label>
        <label class="field">
          <span>昵称</span>
          <input v-model.trim="profileForm.nickname" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>手机号</span>
          <input v-model.trim="profileForm.phone" class="text-input" type="text" />
        </label>
        <label class="field">
          <span>个人说明</span>
          <textarea v-model.trim="profileForm.readme" class="text-area" rows="4" />
        </label>
        <p v-if="profileMessage" :class="profileError ? 'message error' : 'message success'">{{ profileMessage }}</p>
        <div class="actions">
          <button class="primary-button" type="submit" :disabled="savingProfile">
            {{ savingProfile ? '保存中...' : '更新基本信息' }}
          </button>
        </div>
      </form>

      <form v-else class="panel" @submit.prevent="savePassword">
        <h2>更改密码</h2>
        <label class="field">
          <span>原密码</span>
          <input v-model="passwordForm.oldPassword" class="text-input" type="password" autocomplete="current-password" />
        </label>
        <label class="field">
          <span>新密码</span>
          <input v-model="passwordForm.newPassword" class="text-input" type="password" autocomplete="new-password" />
        </label>
        <label class="field">
          <span>确认新密码</span>
          <input
            v-model="passwordForm.newPasswordConfirm"
            class="text-input"
            type="password"
            autocomplete="new-password"
          />
        </label>
        <p v-if="passwordMessage" :class="passwordError ? 'message error' : 'message success'">{{ passwordMessage }}</p>
        <div class="actions">
          <button class="primary-button" type="submit" :disabled="savingPassword">
            {{ savingPassword ? '提交中...' : '更改密码' }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppRefreshButton from '@/components/AppRefreshButton.vue'

import { fetchAccountProfile, updateAccountPassword, updateAccountProfile } from '@/api/bootstrap'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileMessage = ref('')
const passwordMessage = ref('')
const profileError = ref(false)
const passwordError = ref(false)

const activeTab = computed(() => (route.query.tab === 'password' ? 'password' : 'profile'))

const profileForm = reactive({
  name: '',
  email: '',
  nickname: '',
  phone: '',
  readme: '',
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  newPasswordConfirm: '',
})

function setTab(tab: 'profile' | 'password') {
  router.replace(tab === 'password' ? { path: '/account', query: { tab: 'password' } } : { path: '/account' })
}

function syncProfile(payload: {
  name?: string
  email?: string
  nickname?: string
  phone?: string
  readme?: string
}) {
  profileForm.name = payload.name || ''
  profileForm.email = payload.email || ''
  profileForm.nickname = payload.nickname || ''
  profileForm.phone = payload.phone || ''
  profileForm.readme = payload.readme || ''
}

async function load() {
  if (loading.value) return
  loading.value = true
  profileMessage.value = ''
  passwordMessage.value = ''
  try {
    const profile = await fetchAccountProfile()
    syncProfile(profile)
  } catch (err) {
    profileError.value = true
    profileMessage.value = err instanceof Error ? err.message : '加载账号信息失败'
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  if (savingProfile.value) return
  if (!profileForm.name || !profileForm.email) {
    profileError.value = true
    profileMessage.value = '用户名称和邮箱地址不能为空'
    return
  }
  savingProfile.value = true
  profileError.value = false
  profileMessage.value = ''
  try {
    const updated = await updateAccountProfile({ ...profileForm })
    syncProfile(updated)
    await authStore.refresh()
    profileMessage.value = '基本信息已更新'
  } catch (err) {
    profileError.value = true
    profileMessage.value = err instanceof Error ? err.message : '更新基本信息失败'
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  if (savingPassword.value) return
  if (!passwordForm.oldPassword || !passwordForm.newPassword || !passwordForm.newPasswordConfirm) {
    passwordError.value = true
    passwordMessage.value = '请完整填写密码信息'
    return
  }
  savingPassword.value = true
  passwordError.value = false
  passwordMessage.value = ''
  try {
    await updateAccountPassword({ ...passwordForm })
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.newPasswordConfirm = ''
    passwordMessage.value = '密码修改成功'
  } catch (err) {
    passwordError.value = true
    passwordMessage.value = err instanceof Error ? err.message : '密码修改失败'
  } finally {
    savingPassword.value = false
  }
}

onMounted(load)

watch(
  () => route.query.tab,
  () => {
    profileMessage.value = ''
    passwordMessage.value = ''
  },
)
</script>

<style scoped>
.account-page {
  display: grid;
  gap: 18px;
}

.page-header,
.tab-row,
.actions {
  display: flex;
  gap: 12px;
}

.page-header {
  justify-content: space-between;
  align-items: center;
}


.action-button,
.tab-button,
.primary-button {
  border: none;
  border-radius: 999px;
  padding: 10px 14px;
  cursor: pointer;
}

.action-button {
  background: #0f172a;
  color: #fff;
}

.tab-button {
  background: rgba(15, 23, 42, 0.06);
  color: #475569;
}

.tab-button.active,
.primary-button {
  background: #0f766e;
  color: #fff;
}

.status-card,
.panel {
  padding: 20px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.layout {
  display: grid;
  grid-template-columns: minmax(0, 720px);
}

.panel {
  display: grid;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.text-input,
.text-area {
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;
}

.message {
  margin: 0;
  font-size: 14px;
}

.message.success {
  color: #0f766e;
}

.message.error {
  color: #b91c1c;
}
</style>
