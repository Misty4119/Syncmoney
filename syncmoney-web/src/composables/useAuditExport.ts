import { computed, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiClient, type AuditRecord } from '@/api/client'
import { exportAuditCSV } from '@/services/auditService'
import { useNotificationStore } from '@/stores/notification'

interface UseAuditExportOptions {
  records: Ref<AuditRecord[]>
  playerSearch: Ref<string>
  typeFilter: Ref<string>
  startTime: Ref<number | null>
  endTime: Ref<number | null>
  loading: Ref<boolean>
}

/**
 * [SYNC-WEB-043] Audit export composable.
 *
 * Extracted from AuditLogView.vue. Behaviour is unchanged: when a date range
 * is active the full matching dataset is fetched (cursor-paginated) before
 * export, otherwise only the currently loaded records are exported.
 */
export function useAuditExport(opts: UseAuditExportOptions) {
  const { t } = useI18n()
  const notificationStore = useNotificationStore()

  const hasDateFilter = computed(
    () => opts.startTime.value !== null || opts.endTime.value !== null
  )

  function downloadFile(content: string, filename: string, mimeType: string) {
    const blob = new Blob([content], { type: mimeType })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  function formatDateRangeForFilename(): string {
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const dateStr = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`

    if (opts.startTime.value && opts.endTime.value) {
      const start = new Date(opts.startTime.value)
      const end = new Date(opts.endTime.value)
      const startStr = `${start.getFullYear()}${pad(start.getMonth() + 1)}${pad(start.getDate())}`
      const endStr = `${end.getFullYear()}${pad(end.getMonth() + 1)}${pad(end.getDate())}`
      return `${startStr}-${endStr}`
    }

    return dateStr
  }

  async function exportAllData(format: 'csv' | 'json') {
    opts.loading.value = true
    try {
      const allRecords: AuditRecord[] = []
      let currentCursor: string | null = null
      let hasMoreRecords = true
      const batchSize = 1000
      const maxRecords = 100000

      while (hasMoreRecords && allRecords.length < maxRecords) {
        let url = `/api/audit/search-cursor?pageSize=${batchSize}`
        if (currentCursor) url += `&cursor=${encodeURIComponent(currentCursor)}`
        if (opts.playerSearch.value) url += `&player=${encodeURIComponent(opts.playerSearch.value)}`
        if (opts.typeFilter.value) url += `&type=${opts.typeFilter.value}`
        if (opts.startTime.value) url += `&startTime=${opts.startTime.value}`
        if (opts.endTime.value) url += `&endTime=${opts.endTime.value}`

        const response = await apiClient.get(url)
        const body = response.data

        if (body?.success && Array.isArray(body?.data)) {
          allRecords.push(...body.data)

          const pagination = body.pagination
          if (pagination?.nextCursor) {
            currentCursor = pagination.nextCursor
            hasMoreRecords = pagination.hasMore ?? false
          } else {
            hasMoreRecords = false
          }
        } else {
          hasMoreRecords = false
        }
      }

      const uniqueMap = new Map<string, AuditRecord>()
      allRecords.forEach(r => uniqueMap.set(r.id, r))
      const uniqueRecords = Array.from(uniqueMap.values())

      uniqueRecords.sort((a, b) => b.timestamp - a.timestamp)

      if (format === 'csv') {
        const csv = exportAuditCSV(uniqueRecords)
        const filename = `audit-log-${formatDateRangeForFilename()}.csv`
        downloadFile(csv, filename, 'text/csv')
      } else {
        const json = JSON.stringify(uniqueRecords, null, 2)
        const filename = `audit-log-${formatDateRangeForFilename()}.json`
        downloadFile(json, filename, 'application/json')
      }
    } catch (error) {
      console.error('Export failed:', error)
      notificationStore.addNotification('error', t('audit.export.failed'), '')
    } finally {
      opts.loading.value = false
    }
  }

  async function exportCSV() {
    if (hasDateFilter.value) {
      await exportAllData('csv')
    } else {
      if (!opts.records.value.length) return
      const csv = exportAuditCSV(opts.records.value)
      downloadFile(csv, 'audit-log.csv', 'text/csv')
    }
  }

  async function exportJSON() {
    if (hasDateFilter.value) {
      await exportAllData('json')
    } else {
      if (!opts.records.value.length) return
      downloadFile(JSON.stringify(opts.records.value, null, 2), 'audit-log.json', 'application/json')
    }
  }

  return { exportCSV, exportJSON }
}
