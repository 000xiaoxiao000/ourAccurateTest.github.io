import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchCurrentUser, login as requestLogin, logout as requestLogout } from '@/api/bootstrap'
import type { UserSummary } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<UserSummary | null>(null)
  const loaded = ref(false)

  async function ensureLoaded() {
    if (loaded.value) {
      return currentUser.value
    }
    try {
      currentUser.value = await fetchCurrentUser()
    } finally {
      loaded.value = true
    }
    return currentUser.value
  }

  async function refresh() {
    loaded.value = false
    return ensureLoaded()
  }

  async function login(nameOrEmail: string, password: string) {
    currentUser.value = await requestLogin({ nameOrEmail, password })
    loaded.value = true
    return currentUser.value
  }

  async function logout() {
    await requestLogout()
    currentUser.value = null
    loaded.value = true
  }

  const isAuthenticated = computed(() => !!currentUser.value)

  return {
    currentUser,
    loaded,
    ensureLoaded,
    refresh,
    login,
    logout,
    isAuthenticated,
  }
})
