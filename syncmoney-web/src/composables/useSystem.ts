import { ref } from 'vue'
import { apiClient } from '@/api/client'
import type { SystemStatus, ApiResponse } from '@/api/types'
import { handleError } from '@/types/errors'

export function useSystem() {
  const status = ref<Partial<SystemStatus>>({})
  const loading = ref(false)
  const errorMessage = ref<string>('')

  async function loadStatus() {
    loading.value = true
    errorMessage.value = ''

    try {
      const response = await apiClient.get<ApiResponse<SystemStatus>>('/api/system/status')
      if (response.data?.success && response.data?.data) {
        status.value = response.data.data
      }
    } catch (err: unknown) {
      const error = handleError(err)
      errorMessage.value = error.message || 'Failed to load system status'
    } finally {
      loading.value = false
    }
  }

  async function checkRedis() {
    try {
      const response = await apiClient.get<ApiResponse<unknown>>('/api/system/redis')
      return response.data?.success ? response.data.data : null
    } catch {
      return null
    }
  }

  async function checkBreaker() {
    try {
      const response = await apiClient.get<ApiResponse<unknown>>('/api/system/breaker')
      return response.data?.success ? response.data.data : null
    } catch {
      return null
    }
  }

  return {
    status,
    loading,
    error: errorMessage,
    loadStatus,
    checkRedis,
    checkBreaker
  }
}
