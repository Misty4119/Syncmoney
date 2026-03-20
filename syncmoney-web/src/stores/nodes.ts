import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiClient } from '@/api/client'
import type { NodesResponse, ApiResponse } from '@/api/types'

export type NodeHealthStatus = 'online' | 'offline' | 'unknown'

export interface NodeInfo {
  name: string
  url: string
  apiKey: string
  enabled: boolean
  status?: NodeHealthStatus
}

const SSRF_BLOCKED_HOSTNAMES = ['localhost', '127.0.0.1', '0.0.0.0', '::1']

function isUrlBlocked(url: string): boolean {
  try {
    const parsed = new URL(url)
    const hostname = parsed.hostname.toLowerCase()
    return SSRF_BLOCKED_HOSTNAMES.includes(hostname)
  } catch {
    return true
  }
}

export const useNodesStore = defineStore('nodes', () => {
  const nodes = ref<NodeInfo[]>([])
  const nodeStatuses = ref<Map<string, NodeHealthStatus>>(new Map())
  const currentNodeIndex = ref<number>(0)
  const isLoading = ref<boolean>(false)
  const error = ref<string>('')
  const centralMode = ref<boolean>(false)
  const selfUrl = ref<string | null>(null)

  let healthCheckInterval: ReturnType<typeof setInterval> | null = null

  const currentNode = computed(() => nodes.value[currentNodeIndex.value] || null)
  const enabledNodes = computed(() => nodes.value.filter(n => n.enabled))

  function setNodes(nodeList: NodeInfo[]) {
    nodes.value = nodeList
    const savedIndex = localStorage.getItem('selectedNodeIndex')
    if (savedIndex !== null && savedIndex !== undefined) {
      const idx = parseInt(savedIndex, 10)
      if (idx >= 0 && idx < nodeList.length) {
        currentNodeIndex.value = idx
      }
    }
  }

  function selectNode(index: number) {
    if (index >= 0 && index < nodes.value.length) {
      currentNodeIndex.value = index
      localStorage.setItem('selectedNodeIndex', String(index))
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
        selfUrl.value = data.selfUrl
        setNodes(data.nodes)
        for (const node of data.nodes) {
          const status = await checkNodeHealth(node)
          setNodeStatus(node.url, status)
        }
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch nodes'
      throw e
    } finally {
      isLoading.value = false
    }
  }

  async function checkNodeHealth(node: NodeInfo): Promise<NodeHealthStatus> {
    if (!node.enabled) return 'unknown'
    if (isUrlBlocked(node.url)) return 'offline'

    try {
      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), 5000)

      const res = await apiClient.get('/api/system/status', {
        baseURL: node.url,
        headers: { Authorization: `Bearer ${node.apiKey}` },
        signal: controller.signal,
        validateStatus: () => true
      })

      clearTimeout(timeoutId)
      const isOnline = res.status >= 200 && res.status < 300
      const status: NodeHealthStatus = isOnline ? 'online' : 'offline'
      setNodeStatus(node.url, status)
      return status
    } catch {
      setNodeStatus(node.url, 'offline')
      return 'offline'
    }
  }

  async function addNode(node: NodeInfo): Promise<void> {
    if (isUrlBlocked(node.url)) {
      throw new Error('SSRF_BLOCKED')
    }
    const currentNodes = [...nodes.value, node]
    setNodes(currentNodes)
    const status = await checkNodeHealth(node)
    setNodeStatus(node.url, status)
  }

  async function updateNode(index: number, node: NodeInfo): Promise<void> {
    if (index < 0 || index >= nodes.value.length) {
      throw new Error('Invalid node index')
    }
    if (isUrlBlocked(node.url)) {
      throw new Error('SSRF_BLOCKED')
    }
    nodes.value[index] = { ...node }
    const status = await checkNodeHealth(node)
    setNodeStatus(node.url, status)
  }

  async function deleteNode(index: number): Promise<void> {
    if (index < 0 || index >= nodes.value.length) {
      throw new Error('Invalid node index')
    }
    const deletedNode = nodes.value[index]
    nodes.value.splice(index, 1)
    nodeStatuses.value.delete(deletedNode.url)
    if (currentNodeIndex.value >= nodes.value.length) {
      currentNodeIndex.value = Math.max(0, nodes.value.length - 1)
    }
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
    currentNodeIndex,
    currentNode,
    enabledNodes,
    isLoading,
    error,
    centralMode,
    selfUrl,
    setNodes,
    selectNode,
    updateNodeStatus,
    setNodeStatus,
    getNodeStatus,
    fetchNodes,
    checkNodeHealth,
    addNode,
    updateNode,
    deleteNode,
    startHealthCheck,
    stopHealthCheck
  }
})
