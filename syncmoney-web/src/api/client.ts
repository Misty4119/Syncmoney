import axios, { type AxiosInstance } from 'axios'
import type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse } from './types'
import router from '@/router'
import i18n from '@/i18n'

import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'

declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
  }
}


const centralApiClient: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})


const rawAxios: AxiosInstance = axios.create({
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})


centralApiClient.interceptors.request.use(
  (config) => {
    config.baseURL = ''
    const apiKey = localStorage.getItem('apiKey')
    if (apiKey) {
      config.headers.Authorization = `Bearer ${apiKey}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)


let isHandlingSessionExpired = false

centralApiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    const notificationStore = useNotificationStore()
    const t = i18n.global.t
    const silent = error.config?.silent === true

    if (status === 401) {
      if (!isHandlingSessionExpired) {
        isHandlingSessionExpired = true
        localStorage.removeItem('apiKey')

        try {
          const authStore = useAuthStore()
          authStore.logout()
        } catch {
          // fallback if pinia not ready yet
        }

        if (!silent) {
          notificationStore.error(t('api.sessionExpired'), t('api.sessionExpiredDesc'))
        }

        if (router.currentRoute.value.path !== '/login') {
          router.push({
            path: '/login',
            query: { redirect: router.currentRoute.value.fullPath }
          }).finally(() => {
            setTimeout(() => {
              isHandlingSessionExpired = false
            }, 1000)
          })
        } else {
          isHandlingSessionExpired = false
        }
      }
    } else if (status === 403) {
      if (!silent) notificationStore.error(t('api.permissionDenied'), t('api.permissionDeniedDesc'))
    } else if (status === 500) {
      if (!silent) notificationStore.error(t('api.serverError'), t('api.serverErrorDesc'))
    } else if (status === 429) {
      if (!silent) notificationStore.warning(t('api.rateLimited'), t('api.rateLimitedDesc'))
    } else if (!error.response && !silent) {
      // Network drop, timeout, or server unreachable
      notificationStore.error(
        t('api.networkError') || 'Network Error',
        t('api.networkErrorDesc') || 'Unable to connect to Syncmoney server. Please check your connection.'
      )
    }
    return Promise.reject(error)
  }
)


const apiClient = centralApiClient

export { apiClient, centralApiClient, rawAxios }
export type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse }
