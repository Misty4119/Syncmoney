import { apiClient } from '@/api/client'
import type { DashboardStats, ApiResponse } from '@/api/types'

/** [SYNC-WEB-003] Fetch economy dashboard stats. */
export async function getDashboardStats(): Promise<DashboardStats | undefined> {
    const res = await apiClient.get<ApiResponse<DashboardStats>>('/api/economy/stats')
    return res.data?.data
}
