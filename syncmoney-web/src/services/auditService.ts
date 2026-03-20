import { apiClient } from '@/api/client'
import type { AuditRecord, AuditType, ApiResponse, PaginatedResponse } from '@/api/types'

export interface AuditQuery {
    page?: number
    pageSize?: number
    player?: string
    type?: AuditType
    startTime?: number
}

/** [SYNC-WEB-004] Fetch audit records with filters. */
export async function getAuditRecords(params: AuditQuery): Promise<ApiResponse<PaginatedResponse<AuditRecord>> | undefined> {
    const query = new URLSearchParams()
    if (params.page) query.set('page', String(params.page))
    if (params.pageSize) query.set('pageSize', String(params.pageSize))
    if (params.player) query.set('player', params.player)
    if (params.type) query.set('type', params.type)
    if (params.startTime) query.set('startTime', String(params.startTime))
    const res = await apiClient.get<ApiResponse<PaginatedResponse<AuditRecord>>>(`/api/audit/search?${query}`)
    return res.data
}

/** [SYNC-WEB-005] Export audit records as CSV string. */
export function exportAuditCSV(records: AuditRecord[]): string {
    const headers = ['Time', 'Player', 'Type', 'Amount', 'Balance After', 'Source', 'Server']
    const escape = (val: string | number | undefined): string => {
        const str = String(val ?? '')
        return `"${str.replace(/"/g, '""')}"`
    }
    const rows = records.map(r => [
        escape(new Date(r.timestamp).toISOString()),
        escape(r.playerName || r.playerUuid),
        escape(r.type),
        escape(r.amount),
        escape(r.balanceAfter),
        escape(r.source),
        escape(r.serverName || '')
    ])
    return [headers, ...rows]
        .map(row => row.join(','))
        .join('\n')
}
