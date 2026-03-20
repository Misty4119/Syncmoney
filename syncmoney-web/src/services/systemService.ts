import { apiClient } from '@/api/client'
import type { SystemStatus, MetricsResponse, ApiResponse, NodeStatus, AggregatedStats } from '@/api/types'

/** [SYNC-WEB-001] Fetch system status. */
export async function getSystemStatus(): Promise<SystemStatus | undefined> {
    const res = await apiClient.get<ApiResponse<SystemStatus>>('/api/system/status')
    return res.data?.data
}

/** [SYNC-WEB-002] Fetch system metrics. */
export async function getSystemMetrics(): Promise<MetricsResponse | undefined> {
    const res = await apiClient.get<ApiResponse<MetricsResponse>>('/api/system/metrics')
    return res.data?.data
}

/** [SYNC-WEB-015] Fetch all nodes status (aggregated). */
export async function fetchAllNodesStatus(): Promise<Record<string, NodeStatus>> {
    const res = await apiClient.get<ApiResponse<Record<string, NodeStatus>>>('/api/nodes/status')
    return res.data?.data || {}
}

/** [SYNC-WEB-016] Manually ping a single node. */
export async function pingNode(url: string, apiKey: string): Promise<boolean> {
    try {
        const controller = new AbortController()
        const timeoutId = setTimeout(() => controller.abort(), 5000)

        const res = await apiClient.get('/api/system/status', {
            baseURL: url,
            headers: { Authorization: `Bearer ${apiKey}` },
            signal: controller.signal,
            validateStatus: () => true
        })

        clearTimeout(timeoutId)
        return res.status >= 200 && res.status < 300
    } catch {
        return false
    }
}

/** [SYNC-WEB-017] Fetch cross-server economic statistics. */
export async function fetchCrossServerStats(): Promise<AggregatedStats | undefined> {
    const res = await apiClient.get<ApiResponse<AggregatedStats>>('/api/economy/cross-server-stats')
    return res.data?.data
}
