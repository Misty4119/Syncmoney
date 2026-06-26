import axios, { type AxiosInstance } from 'axios'
import type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse } from './types'
import router from '@/router'
import i18n from '@/i18n'

import { useNotificationStore } from '@/stores/notification'

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


centralApiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    const notificationStore = useNotificationStore()
    const t = i18n.global.t
    const silent = error.config?.silent === true

    if (status === 401) {
      // Session errors must always redirect, regardless of the silent flag.
      localStorage.removeItem('apiKey')
      if (!silent) notificationStore.error(t('api.sessionExpired'), t('api.sessionExpiredDesc'))
      router.push('/login')
    } else if (status === 403) {
      if (!silent) notificationStore.error(t('api.permissionDenied'), t('api.permissionDeniedDesc'))
      router.push('/?error=forbidden')
    } else if (status === 500) {
      if (!silent) notificationStore.error(t('api.serverError'), t('api.serverErrorDesc'))
      router.push('/?error=server_error')
    } else if (status === 429) {
      if (!silent) notificationStore.warning(t('api.rateLimited'), t('api.rateLimitedDesc'))
      router.push('/?error=rate_limited')
    }
    return Promise.reject(error)
  }
)


const apiClient = centralApiClient

export { apiClient, centralApiClient, rawAxios }
export type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse }
