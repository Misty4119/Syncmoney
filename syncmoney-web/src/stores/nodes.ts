import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiClient } from '@/api/client'
import type { NodesResponse, ApiResponse } from '@/api/types'
import type { ConfigChange } from '@/services/configService'

export type NodeHealthStatus = 'online' | 'offline' | 'unknown'

export interface NodeInfo {
  name: string
  url: string
  apiKey: string
  enabled: boolean
  status?: NodeHealthStatus
}

export interface SyncResult {
  total: number
  succeeded: number
  failed: number
  results: Array<{
    index: number
    name: string
    status: string
    error?: string
  }>
}

export const useNodesStore = defineStore('nodes', () => {
  const nodes = ref<NodeInfo[]>([])
  const nodeStatuses = ref<Map<string, NodeHealthStatus>>(new Map())
  const isLoading = ref<boolean>(false)
  const error = ref<string>('')
  const centralMode = ref<boolean>(false)

  let healthCheckInterval: ReturnType<typeof setInterval> | null = null

  const enabledNodes = computed(() => nodes.value.filter(n => n.enabled))

  function setNodes(nodeList: NodeInfo[]) {
    nodes.value = nodeList

    
    for (const node of nodeList) {
      if (node.status) {
        const status: NodeHealthStatus =
          node.status === 'online' ? 'online' :
          node.status === 'offline' ? 'offline' : 'unknown'
        nodeStatuses.value.set(node.url, status)
      }
    }
  }

  function updateNodeStatus(index: number, status: NodeHealthStatus) {
    if (index >= 0 && index < nodes.value.length) {
      nodes.value[index].status = status
    }
  }

  function setNodeStatus(url: string, status: NodeHealthStatus) {
    nodeStatuses.value.set(url, status)
  }

  function getNodeStatus(url: string): NodeHealthStatus {
    return nodeStatuses.value.get(url) || 'unknown'
  }

  async function fetchNodes(): Promise<void> {
    isLoading.value = true
    error.value = ''
    try {
      const res = await apiClient.get<ApiResponse<NodesResponse>>('/api/nodes')
      const data = res.data?.data
      if (data) {
        centralMode.value = data.centralMode
        setNodes(data.nodes)
        
        
        
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch nodes'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function checkNodeHealth(node: NodeInfo): Promise<NodeHealthStatus> {
    
    
    const nodeIndex = nodes.value.findIndex(n => n.url === node.url)
    if (nodeIndex === -1) return 'unknown'
    if (!node.enabled) return 'unknown'

    try {
      const res = await apiClient.post<ApiResponse<{ status: string }>>(`/api/nodes/${nodeIndex}/ping`)
      if (res.status >= 200 && res.status < 300 && res.data?.success && res.data?.data) {
        
        const status: NodeHealthStatus =
          res.data.data.status === 'online' ? 'online' :
          res.data.data.status === 'offline' ? 'offline' : 'unknown'
        setNodeStatus(node.url, status)
        return status
      }
      setNodeStatus(node.url, 'offline')
      return 'offline'
    } catch {
      setNodeStatus(node.url, 'offline')
      return 'offline'
    }
  }

  async function deleteNode(index: number): Promise<void> {
    if (index < 0 || index >= nodes.value.length) {
      throw new Error('Invalid node index')
    }
    await apiClient.delete(`/api/nodes/${index}`)
    await fetchNodes()
  }

  async function syncConfigToNodes(changes: ConfigChange[], reload = true): Promise<SyncResult> {
    const response = await apiClient.post<ApiResponse<SyncResult>>('/api/nodes/sync', {
      changes,
      reload
    })
    if (!response.data?.success || !response.data?.data) {
      throw new Error('Failed to sync config to nodes')
    }
    return response.data.data
  }

  async function syncConfigToNode(index: number, changes: ConfigChange[], reload = true): Promise<void> {
    await apiClient.post(`/api/nodes/${index}/sync`, {
      changes,
      reload
    })
  }

  function startHealthCheck(intervalMs: number = 30000): void {
    stopHealthCheck()
    healthCheckInterval = setInterval(async () => {
      for (let i = 0; i < nodes.value.length; i++) {
        const node = nodes.value[i]
        const status = await checkNodeHealth(node)
        setNodeStatus(node.url, status)
        updateNodeStatus(i, status)
      }
    }, intervalMs)
  }

  function stopHealthCheck(): void {
    if (healthCheckInterval !== null) {
      clearInterval(healthCheckInterval)
      healthCheckInterval = null
    }
  }

  return {
    nodes,
    nodeStatuses,
    enabledNodes,
    isLoading,
    error,
    centralMode,
    setNodes,
    updateNodeStatus,
    setNodeStatus,
    getNodeStatus,
    fetchNodes,
    checkNodeHealth,
    deleteNode,
    syncConfigToNodes,
    syncConfigToNode,
    startHealthCheck,
    stopHealthCheck
  }
})
