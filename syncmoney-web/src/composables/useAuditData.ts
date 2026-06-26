import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiClient, type AuditRecord } from '@/api/client'
import type { AuditEvent } from '@/types/events'
import { useSSEStore } from '@/stores/sse'
import { useNodesStore } from '@/stores/nodes'

/**
 * [SYNC-WEB-040] Audit log data composable.
 *
 * Encapsulates all audit record loading, cursor pagination, sorting,
 * real-time SSE insertion and silent polling. Extracted out of
 * AuditLogView.vue so the view stays a thin orchestration layer while the
 * data flow (cursor params, dedup, virtual-list ordering) is unchanged.
 */
export function useAuditData() {
  const { t } = useI18n()
  const ws = useSSEStore()
  const nodesStore = useNodesStore()

  const auditRecords = ref<AuditRecord[]>([])
  const loading = ref(false)
  const hasMore = ref(true)
  const cursor = ref<string | null>(null)
  const newRecordsCount = ref(0)

  const playerSearch = ref('')
  const typeFilter = ref('')
  const startTime = ref<number | null>(null)
  const endTime = ref<number | null>(null)
  const isRealtimeEnabled = ref(true)

  const displayedIds = ref<Set<string>>(new Set())
  const newRecordIds = ref<Set<string>>(new Set())

  const isInitialLoading = computed(() => loading.value && auditRecords.value.length === 0)

  const sortState = ref<{ key: string; order: 'asc' | 'desc' }>({ key: 'timestamp', order: 'desc' })

  const sortedAuditRecords = computed(() => {
    const { key: sortKey, order } = sortState.value

    return [...auditRecords.value].sort((a, b) => {
      let cmp = 0

      if (sortKey === 'timestamp' || !sortKey) {
        const timeA = Number(a.timestamp) || 0
        const timeB = Number(b.timestamp) || 0
        cmp = timeA - timeB

        if (cmp === 0) {
          const seqA = Number(a.sequence) || 0
          const seqB = Number(b.sequence) || 0
          cmp = seqB - seqA
        }

        cmp = order === 'asc' ? cmp : -cmp
      } else if (sortKey === 'sequence') {
        const seqA = Number(a.sequence) || 0
        const seqB = Number(b.sequence) || 0
        cmp = seqA - seqB
        cmp = order === 'asc' ? cmp : -cmp
      } else {
        const aVal = a[sortKey as keyof typeof a]
        const bVal = b[sortKey as keyof typeof a]
        if (aVal !== undefined && bVal !== undefined) {
          if (['amount', 'balanceAfter'].includes(sortKey)) {
            const numA = typeof aVal === 'string' ? parseFloat(aVal) : Number(aVal)
            const numB = typeof bVal === 'string' ? parseFloat(bVal) : Number(bVal)
            cmp = numA - numB
          } else {
            cmp = String(aVal).localeCompare(String(bVal))
          }
        }
        cmp = order === 'asc' ? cmp : -cmp
      }

      return cmp
    })
  })

  function handleSort(key: string) {
    if (sortState.value.key === key) {
      sortState.value.order = sortState.value.order === 'asc' ? 'desc' : 'asc'
    } else {
      sortState.value = { key, order: 'desc' }
    }
  }

  function insertRecordSorted(records: AuditRecord[], newRecord: AuditRecord): AuditRecord[] {
    const newList = [...records]
    const index = newList.findIndex(r =>
      r.timestamp < newRecord.timestamp ||
      (r.timestamp === newRecord.timestamp && (r.sequence || 0) < (newRecord.sequence || 0))
    )

    if (index === -1) {
      newList.push(newRecord)
    } else {
      newList.splice(index, 0, newRecord)
    }

    return newList
  }

  /** Build the shared filter query string used by both pagination and polling. */
  function appendFilterParams(url: string): string {
    if (playerSearch.value) url += `&player=${encodeURIComponent(playerSearch.value)}`
    if (typeFilter.value) url += `&type=${typeFilter.value}`
    if (startTime.value) url += `&startTime=${startTime.value}`
    if (endTime.value) url += `&endTime=${endTime.value}`
    return url
  }

  function handleSearch() {
    displayedIds.value.clear()
    newRecordIds.value.clear()
    newRecordsCount.value = 0
    auditRecords.value = []
    cursor.value = null
    hasMore.value = true
    loadAuditData()
  }

  function scrollToTop() {
    window.scrollTo({ top: 0, behavior: 'smooth' })
    newRecordsCount.value = 0
  }

  async function loadAuditData() {
    if (loading.value || !hasMore.value) return

    loading.value = true
    try {
      let url = `/api/audit/search-cursor?pageSize=50`
      if (cursor.value) url += `&cursor=${encodeURIComponent(cursor.value)}`
      url = appendFilterParams(url)

      const response = await apiClient.get(url)
      const body = response.data

      if (body?.success && Array.isArray(body?.data)) {
        const newRecords: AuditRecord[] = body.data
        newRecords.forEach((record: AuditRecord) => {
          if (!displayedIds.value.has(record.id)) {
            displayedIds.value.add(record.id)
            auditRecords.value.push(record)
          }
        })

        const pagination = body.pagination
        if (pagination) {
          cursor.value = pagination.nextCursor || null
          hasMore.value = pagination.hasMore ?? false
        } else {
          hasMore.value = newRecords.length >= 50
          if (newRecords.length > 0) {
            const lastRecord = newRecords[newRecords.length - 1]
            cursor.value = `${lastRecord.timestamp},${lastRecord.sequence || 0}`
          }
        }
      }
    } catch (error) {
      console.error('Failed to load audit data:', error)
    } finally {
      loading.value = false
    }
  }

  /** [SYNC-WEB-041] Filter-aware silent refresh (used while real-time is off). */
  async function silentRefresh() {
    if (loading.value) return

    try {
      let url = `/api/audit/search-cursor?pageSize=50`
      url = appendFilterParams(url)

      const response = await apiClient.get(url, { silent: true })
      const newRecords = response.data.data || []

      const toInsert: AuditRecord[] = []
      for (const record of newRecords) {
        if (!displayedIds.value.has(record.id)) {
          displayedIds.value.add(record.id)
          toInsert.push(record)
        }
      }

      if (toInsert.length > 0) {
        toInsert.sort((a, b) => b.timestamp - a.timestamp)
        for (const record of toInsert) {
          auditRecords.value = insertRecordSorted(auditRecords.value, record)
        }

        toInsert.forEach(r => newRecordIds.value.add(r.id))
        setTimeout(() => {
          toInsert.forEach(r => newRecordIds.value.delete(r.id))
        }, 3000)
      }
    } catch (e) {
      console.error('Silent refresh failed:', e)
    }
  }

  function matchesCurrentFilter(data: AuditEvent): boolean {
    if (playerSearch.value) {
      const playerMatch = data.playerName?.toLowerCase().includes(playerSearch.value.toLowerCase())
      if (!playerMatch) return false
    }

    if (typeFilter.value && typeFilter.value !== 'ALL') {
      if (data.source !== typeFilter.value && data.type !== typeFilter.value) {
        return false
      }
    }

    if (startTime.value && data.timestamp) {
      if (data.timestamp < startTime.value) return false
    }
    if (endTime.value && data.timestamp) {
      if (data.timestamp > endTime.value) return false
    }

    return true
  }

  /**
   * [SYNC-WEB-042] Toggle real-time mode. When re-enabling, perform an
   * immediate filter-aware catch-up so records that arrived while polling
   * was paused are merged in without resetting the list.
   */
  function toggleRealtime(enabled: boolean) {
    isRealtimeEnabled.value = enabled
    if (enabled) {
      silentRefresh()
    }
  }

  let refreshInterval: ReturnType<typeof setInterval> | null = null
  let auditSseHandler: ((data: unknown) => void) | null = null

  onMounted(async () => {
    await nodesStore.fetchNodes().catch(() => {})

    loadAuditData()

    refreshInterval = setInterval(() => {
      if (!loading.value && !isRealtimeEnabled.value) {
        silentRefresh()
      }
    }, 60000)

    auditSseHandler = (data: unknown) => {
      const evt = data as AuditEvent
      if (!isRealtimeEnabled.value) return

      if (matchesCurrentFilter(evt)) {
        const recordId = evt.id || (evt as Record<string, unknown>).recordId || (evt as Record<string, unknown>).uuid
        if (recordId && !displayedIds.value.has(String(recordId))) {
          displayedIds.value.add(String(recordId))
          const newRecord: AuditRecord = {
            id: String(recordId),
            playerUuid: evt.playerUuid || '',
            playerName: evt.playerName || t('dashboard.unknownPlayer'),
            type: (evt.type as AuditRecord['type']) || 'UNKNOWN',
            amount: String(evt.amount || 0),
            balanceBefore: String((evt as Record<string, unknown>).balanceBefore || 0),
            balanceAfter: String((evt as Record<string, unknown>).balanceAfter || evt.balance || 0),
            source: String((evt as Record<string, unknown>).source || evt.type || 'UNKNOWN'),
            timestamp: evt.timestamp || Date.now(),
            serverName: String((evt as Record<string, unknown>).serverName || (evt as Record<string, unknown>).server || 'unknown'),
            sequence: evt.sequence
          }
          auditRecords.value = insertRecordSorted(auditRecords.value, newRecord)

          newRecordsCount.value++

          newRecordIds.value.add(String(recordId))
          setTimeout(() => {
            newRecordIds.value.delete(String(recordId))
          }, 3000)
        }
      }
    }

    ws.on('audit', auditSseHandler)
  })

  onUnmounted(() => {
    if (refreshInterval) clearInterval(refreshInterval)
    if (auditSseHandler) ws.off('audit', auditSseHandler)
  })

  return {
    auditRecords,
    sortedAuditRecords,
    loading,
    hasMore,
    newRecordsCount,
    newRecordIds,
    sortState,
    isInitialLoading,
    playerSearch,
    typeFilter,
    startTime,
    endTime,
    isRealtimeEnabled,
    handleSearch,
    loadAuditData,
    handleSort,
    scrollToTop,
    toggleRealtime,
  }
}
