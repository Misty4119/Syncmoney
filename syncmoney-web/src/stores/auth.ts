import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiClient, type ApiResponse } from '@/api/client'
import type { SystemStatus } from '@/api/types'
import { handleError } from '@/types/errors'

export const useAuthStore = defineStore('auth', () => {
  const apiKey = ref<string>(localStorage.getItem('apiKey') || '')
  const isAuthenticated = ref<boolean>(!!apiKey.value)
  const isLoading = ref<boolean>(false)
  const error = ref<string>('')

  const isLoggedIn = computed(() => isAuthenticated.value)

  async function login(key: string): Promise<boolean> {
    isLoading.value = true
    error.value = ''

    try {
      const response = await apiClient.get<ApiResponse<SystemStatus>>('/api/system/status', {
        headers: { Authorization: `Bearer ${key}` }
      })

      if (response.data && response.data.success) {
        apiKey.value = key
        localStorage.setItem('apiKey', key)
        isAuthenticated.value = true
        return true
      } else {
        error.value = response.data?.error?.message || 'Login failed'
        return false
      }
    } catch (err: unknown) {
      const errorInfo = handleError(err)
      error.value = errorInfo.message || 'Network error'
      return false
    } finally {
      isLoading.value = false
    }
  }

  function logout() {
    apiKey.value = ''
    localStorage.removeItem('apiKey')
    isAuthenticated.value = false
  }

  async function checkAuth() {
    const storedKey = localStorage.getItem('apiKey')
    if (storedKey) {
      apiKey.value = storedKey
      await validateToken(storedKey)
    }
  }


  async function validateToken(key: string): Promise<boolean> {
    try {

      const tempApiClient = apiClient.create()
      const response = await tempApiClient.get<ApiResponse<SystemStatus>>('/api/system/status', {
        headers: { Authorization: `Bearer ${key}` }
      })

      if (response.data && response.data.success) {
        isAuthenticated.value = true
        return true
      } else {

        logout()
        return false
      }
    } catch {

      logout()
      return false
    }
  }

  return {
    apiKey,
    isAuthenticated,
    isLoading,
    error,
    isLoggedIn,
    login,
    logout,
    checkAuth
  }
})
