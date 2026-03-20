import axios, { type AxiosInstance } from 'axios'
import type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse } from './types'
import router from '@/router'

import { useNotificationStore } from '@/stores/notification'
import { useNodesStore } from '@/stores/nodes'

const apiClient: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})


apiClient.interceptors.request.use(
  (config) => {
    const nodesStore = useNodesStore()
    if (nodesStore.currentNode) {
      config.baseURL = nodesStore.currentNode.url
      config.headers.Authorization = `Bearer ${nodesStore.currentNode.apiKey}`
    } else {
      config.baseURL = ''
      const apiKey = localStorage.getItem('apiKey')
      if (apiKey) {
        config.headers.Authorization = `Bearer ${apiKey}`
      }
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)


apiClient.interceptors.response.use(
  (response) => response,
  (error) => {

    const status = error.response?.status
    const notificationStore = useNotificationStore()
    
    if (status === 401) {
      localStorage.removeItem('apiKey')

      notificationStore.addNotification('error', 'Session Expired', 'Your session has expired. Please log in again.')
      router.push('/login')
    } else if (status === 403) {

      notificationStore.addNotification('error', 'Permission Denied', 'You do not have permission to perform this action.')
      router.push('/?error=forbidden')
    } else if (status === 500) {

      notificationStore.addNotification('error', 'Server Error', 'An internal server error occurred. Please try again later.')
      router.push('/?error=server_error')
    } else if (status === 429) {

      notificationStore.addNotification('warning', 'Rate Limited', 'Too many requests. Please wait a moment before retrying.')
      router.push('/?error=rate_limited')
    }
    return Promise.reject(error)
  }
)

export { apiClient }
export type { ApiResponse, AuditRecord, DashboardStats, SystemStatus, Settings, PaginatedResponse, PluginConfig, SystemMetrics, NodeInfo, NodeStatusResponse }
