<template>
  <div class="space-y-6">
    <!-- New records notification banner -->
    <div
      v-if="newRecordsCount > 0"
      class="fixed bottom-6 right-6 z-50 flex items-center gap-2 px-4 py-2.5 bg-primary text-white rounded-full shadow-lg cursor-pointer select-none transition-all duration-300 hover:scale-105"
      @click="scrollToTop"
    >
      <span class="text-sm font-medium">{{ newRecordsCount }} {{ t('audit.newRecords') }} ↑</span>
    </div>

    <!-- Filter bar -->
    <Card variant="glass" padding="sm">
      <div class="space-y-3">
        <!-- Row 1: Primary filters -->
        <div class="flex flex-wrap items-center gap-3">
          <!-- Real-time toggle and connection status -->
          <div class="flex items-center gap-2">
            <label class="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                v-model="isRealtimeEnabled"
                class="sr-only peer"
                @change="toggleRealtime"
              />
              <div :class="[
                'relative w-11 h-6 rounded-full transition-colors duration-300',
                'peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full',
                'peer-checked:after:border-white after:content-[\'\'] after:absolute after:top-[2px]',
                'after:start-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all',
                isRealtimeEnabled ? 'bg-success' : 'bg-error'
              ]"></div>
              <span :class="['text-sm font-medium transition-colors duration-300', isRealtimeEnabled ? 'text-success' : 'text-error']">
                {{ t('audit.realtime') }}
              </span>
            </label>

            <!-- Connection status indicator -->
            <div class="flex items-center gap-1.5">
              <span
                :class="[
                  'w-2 h-2 rounded-full transition-all duration-300',
                  connectionStatus === 'connected' ? 'bg-success shadow-[0_0_8px_rgba(34,197,94,0.6)]' :
                  connectionStatus === 'connecting' ? 'bg-warning animate-pulse' :
                  'bg-error shadow-[0_0_8px_rgba(239,68,68,0.6)]'
                ]"
              ></span>
              <span class="text-xs text-surface-500">
                {{ connectionStatusText }}
              </span>
            </div>
          </div>

          <!-- Search input -->
          <div class="relative w-[180px] group">
            <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-primary/50 group-focus-within:text-primary transition-colors duration-300" />
            <input
              v-model="playerSearch"
              type="text"
              class="w-full pl-10 pr-4 py-2 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-100 placeholder-surface-500 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300"
              :placeholder="t('audit.filters.player')"
              @keydown.enter="handleSearch"
            />
          </div>

          <!-- Type filter -->
          <select
            v-model="typeFilter"
            class="px-3 py-2 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300 appearance-none cursor-pointer"
          >
            <option value="" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.all') }}</option>
            <option value="DEPOSIT" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.deposit') }}</option>
            <option value="WITHDRAW" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.withdraw') }}</option>
            <option value="TRANSFER" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.transfer') }}</option>
          </select>

          <!-- Action buttons -->
          <div class="flex items-center gap-2 ml-auto">
            <Button variant="primary" size="sm" :icon="Search" @click="handleSearch">
              {{ t('common.search') }}
            </Button>

            <Button variant="outline" size="sm" :icon="Download" @click="exportCSV">
              CSV
            </Button>
            <Button variant="outline" size="sm" :icon="Download" @click="exportJSON">
              JSON
            </Button>
          </div>
        </div>

        <!-- Row 2: Date filters -->
        <div class="flex flex-wrap items-center gap-2">
          <!-- Shortcut buttons -->
          <div class="flex gap-1">
            <button
              v-for="shortcut in dateShortcuts"
              :key="shortcut.key"
              @click="setDateShortcut(shortcut.key)"
              :class="[
                'px-2 py-1.5 text-xs rounded-md transition-all duration-200 border',
                activeShortcut === shortcut.key
                  ? 'bg-primary text-white border-primary shadow-glow-sm'
                  : 'bg-surface-50/50 dark:bg-surface-950/50 border-surface-200 dark:border-surface-700 text-surface-600 dark:text-surface-400 hover:border-primary hover:text-primary'
              ]"
            >
              {{ t(`audit.dateShortcut.${shortcut.key}`) }}
            </button>
          </div>

          <!-- Start datetime input -->
          <div class="flex items-center gap-1">
            <input
              type="datetime-local"
              v-model="startDateTimeLocal"
              @change="onDateTimeChange"
              class="px-2 py-1.5 w-[150px] bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary transition-all duration-300"
            />

            <span class="text-surface-400 text-sm select-none">—</span>

            <!-- End datetime input -->
            <input
              type="datetime-local"
              v-model="endDateTimeLocal"
              @change="onDateTimeChange"
              class="px-2 py-1.5 w-[150px] bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary transition-all duration-300"
            />

            <!-- Clear date button -->
            <button
              v-if="hasDateFilter"
              @click="clearDateFilter"
              class="p-1.5 text-surface-400 hover:text-error transition-colors"
              title="Clear date filter"
            >
              <X class="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </Card>

      <!-- Table with virtual scrolling -->
    <Card variant="glass" padding="none" class="relative">
      <div
        ref="scrollContainer"
        class="overflow-auto"
        :style="{ height: tableHeight + 'px' }"
        @scroll="handleScroll"
      >
        <!-- Virtual scroll container with spacer -->
        <div
          class="relative w-full"
          :style="{ height: totalContentHeight + 'px' }"
        >
          <table class="w-full table-fixed">
            <thead class="sticky top-0 z-20 bg-surface-100 dark:bg-surface-800 shadow-sm">
              <tr>
                <th
                  v-for="col in columns"
                  :key="col.key"
                  @click="col.sortable && handleTableSort(col.key)"
                  :class="[
                    'px-4 py-3 text-left text-xs font-semibold text-surface-500 dark:text-surface-400 uppercase tracking-wider cursor-pointer select-none',
                    col.sortable ? 'hover:text-primary transition-colors' : ''
                  ]"
                  :style="col.width ? { width: col.width } : {}"
                >
                  <div class="flex items-center gap-1">
                    {{ t(col.label) }}
                    <template v-if="col.sortable">
                      <ArrowUp
                        v-if="sortState.key === col.key && sortState.order === 'asc'"
                        class="w-3 h-3 text-primary"
                      />
                      <ArrowDown
                        v-else-if="sortState.key === col.key && sortState.order === 'desc'"
                        class="w-3 h-3 text-primary"
                      />
                    </template>
                  </div>
                </th>
              </tr>
            </thead>
          </table>

          <!-- Virtual body: only render visible rows -->
          <div
            v-for="item in virtualRecords"
            :key="item.record?.id"
            class="absolute w-full"
            :style="{
              height: itemHeight + 'px',
              transform: `translateY(${item.top}px)`,
            }"
          >
            <table class="w-full table-fixed">
              <tbody>
                <tr
                  :class="[
                    'border-b border-surface-200 dark:border-surface-700 hover:bg-surface-50 dark:hover:bg-surface-800/50 transition-colors',
                    getRowClass(item.record) ? 'new-record-row' : ''
                  ]"
                >
                  <td class="px-4 py-2 text-sm text-surface-900 dark:text-surface-100 overflow-hidden text-ellipsis whitespace-nowrap">
                    <span class="text-surface-600 dark:text-surface-400 text-xs tracking-wider font-mono">{{ formatDate(Number(item.record?.timestamp)) }}</span>
                  </td>
                  <td class="px-4 py-2 text-sm text-surface-900 dark:text-surface-100 overflow-hidden text-ellipsis whitespace-nowrap">
                    {{ item.record?.playerName }}
                  </td>
                  <td class="px-4 py-2">
                    <Badge :variant="typeBadgeVariant(String(item.record?.type))">
                      {{ t(`audit.typeOptions.${String(item.record?.type).toLowerCase()}`) }}
                    </Badge>
                  </td>
                  <td class="px-4 py-2">
                    <span class="font-mono font-bold" :class="parseFloat(String(item.record?.amount)) >= 0 ? 'text-success drop-shadow-[0_0_5px_rgba(34,197,94,0.3)]' : 'text-error drop-shadow-[0_0_5px_rgba(239,68,68,0.3)]'">
                      {{ parseFloat(String(item.record?.amount)) >= 0 ? '+' : '' }}{{ item.record?.amount }}
                    </span>
                  </td>
                  <td class="px-4 py-2 text-sm text-surface-900 dark:text-surface-100 font-mono overflow-hidden text-ellipsis whitespace-nowrap">
                    {{ item.record?.balanceAfter }}
                  </td>
                  <td class="px-4 py-2 text-sm text-surface-600 dark:text-surface-400 overflow-hidden text-ellipsis whitespace-nowrap">
                    {{ item.record?.source }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Empty state -->
        <div v-if="auditRecords.length === 0 && !loading" class="p-8">
          <EmptyState :icon="FileText" :title="t('common.noData')" />
        </div>

        <!-- Load more trigger -->
        <div ref="loadTrigger" class="p-4 border-t border-surface-700/50">
          <div v-if="loading && auditRecords.length > 0" class="text-center py-4 text-surface-500">
            {{ t('audit.loadingMore') }}
          </div>
          <div v-else-if="!hasMore && auditRecords.length > 0" class="text-center py-4 text-surface-500">
            {{ t('audit.noMoreData') }}
          </div>
        </div>
      </div>

      <!-- Loading skeleton -->
      <div v-if="isInitialLoading" class="absolute inset-0 bg-surface-100/50 dark:bg-surface-800/50 z-30">
        <div class="flex items-center justify-center h-full">
          <div class="flex flex-col items-center gap-2">
            <div class="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin"></div>
            <span class="text-sm text-surface-500">{{ t('audit.loadingMore') }}</span>
          </div>
        </div>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiClient, type AuditRecord } from '@/api/client'
import type { AuditEvent } from '@/types/events'
import { exportAuditCSV } from '@/services/auditService'
import { useSSEStore } from '@/stores/sse'
import { useNotificationStore } from '@/stores/notification'
import { useSettingsStore } from '@/stores/settings'
import { useNodesStore } from '@/stores/nodes'
import { formatInTimeZone } from 'date-fns-tz'
import { normalizeTimezone } from '@/utils/timezone'
import { Search, Download, FileText, X, ArrowUp, ArrowDown } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Badge from '@/components/common/Badge.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const { t } = useI18n()
const ws = useSSEStore()
const notificationStore = useNotificationStore()
const settingsStore = useSettingsStore()
const nodesStore = useNodesStore()


const isRealtimeEnabled = ref(true)
const connectionStatus = computed(() => ws.connectionStatus || 'disconnected')

const connectionStatusText = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return t('audit.status.connected')
    case 'connecting': return t('audit.status.connecting')
    case 'disconnected': return t('audit.status.disconnected')
    case 'error': return t('audit.status.error')
    default: return ''
  }
})

function toggleRealtime() {

}



function toLocalDateTimeStrFull(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const y = d.getFullYear()
  const m = pad(d.getMonth() + 1)
  const day = pad(d.getDate())
  const h = pad(d.getHours())
  const min = pad(d.getMinutes())
  const s = pad(d.getSeconds())
  return `${y}-${m}-${day}T${h}:${min}:${s}`
}

const dateShortcuts = [
  { key: 'today' },
  { key: 'yesterday' },
  { key: 'last7' },
  { key: 'last30' },
  { key: 'all' },
]

const activeShortcut = ref<string>('')


const startDateTimeLocal = ref<string>('')
const endDateTimeLocal = ref<string>('')


const startTime = ref<number | null>(null)
const endTime = ref<number | null>(null)


const hasDateFilter = computed(() => {
  return startTime.value !== null || endTime.value !== null
})

/** [SYNC-WEB-031] Parse datetime-local string to timestamp */
function parseDateTimeLocal(str: string): number | null {
  if (!str) return null
  const d = new Date(str)
  if (isNaN(d.getTime())) return null
  return d.getTime()
}

/** [SYNC-WEB-032] Called when user manually edits either datetime input */
function onDateTimeChange() {
  activeShortcut.value = ''
  applyDateRange()
}

/** [SYNC-WEB-033] Clear date filter */
function clearDateFilter() {
  activeShortcut.value = ''
  startDateTimeLocal.value = ''
  endDateTimeLocal.value = ''
  startTime.value = null
  endTime.value = null
}

/** [SYNC-WEB-034] Apply date range from inputs to timestamps */
function applyDateRange() {
  startTime.value = parseDateTimeLocal(startDateTimeLocal.value)
  endTime.value = parseDateTimeLocal(endDateTimeLocal.value)


  if (startDateTimeLocal.value && !startDateTimeLocal.value.includes('T')) {
    const d = new Date(startDateTimeLocal.value + 'T00:00:00')
    startTime.value = d.getTime()
  }
  if (endDateTimeLocal.value && !endDateTimeLocal.value.includes('T')) {
    const d = new Date(endDateTimeLocal.value + 'T23:59:59.999')
    endTime.value = d.getTime()
  }
}

/** [SYNC-WEB-035] Apply a quick shortcut and immediately search. */
function setDateShortcut(key: string) {
  activeShortcut.value = key


  const now = new Date()

  if (key === 'today') {

    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    startOfDay.setHours(0, 0, 0, 0)
    startDateTimeLocal.value = toLocalDateTimeStrFull(startOfDay)


    const endOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    endOfDay.setHours(23, 59, 59, 999)
    endDateTimeLocal.value = toLocalDateTimeStrFull(endOfDay)
  } else if (key === 'yesterday') {
    const yesterday = new Date(now)
    yesterday.setDate(yesterday.getDate() - 1)
    const startOfYesterday = new Date(yesterday.getFullYear(), yesterday.getMonth(), yesterday.getDate())
    startOfYesterday.setHours(0, 0, 0, 0)
    startDateTimeLocal.value = toLocalDateTimeStrFull(startOfYesterday)

    const endOfYesterday = new Date(yesterday.getFullYear(), yesterday.getMonth(), yesterday.getDate())
    endOfYesterday.setHours(23, 59, 59, 999)
    endDateTimeLocal.value = toLocalDateTimeStrFull(endOfYesterday)
  } else if (key === 'last7') {
    const d = new Date(now)
    d.setDate(d.getDate() - 6)
    const startOfDay = new Date(d.getFullYear(), d.getMonth(), d.getDate())
    startOfDay.setHours(0, 0, 0, 0)
    startDateTimeLocal.value = toLocalDateTimeStrFull(startOfDay)

    const endOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    endOfToday.setHours(23, 59, 59, 999)
    endDateTimeLocal.value = toLocalDateTimeStrFull(endOfToday)
  } else if (key === 'last30') {
    const d = new Date(now)
    d.setDate(d.getDate() - 29)
    const startOfDay = new Date(d.getFullYear(), d.getMonth(), d.getDate())
    startOfDay.setHours(0, 0, 0, 0)
    startDateTimeLocal.value = toLocalDateTimeStrFull(startOfDay)

    const endOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    endOfToday.setHours(23, 59, 59, 999)
    endDateTimeLocal.value = toLocalDateTimeStrFull(endOfToday)
  } else if (key === 'all') {
    startDateTimeLocal.value = ''
    endDateTimeLocal.value = ''
  }

  applyDateRange()
  handleSearch()
}


const columns: { key: string; label: string; sortable?: boolean; width?: string }[] = [
  { key: 'timestamp', label: 'audit.time', sortable: true, width: '180px' },
  { key: 'playerName', label: 'audit.player', sortable: true, width: 'minmax(120px, 1fr)' },
  { key: 'type', label: 'audit.type', sortable: true, width: '100px' },
  { key: 'amount', label: 'audit.amount', sortable: true, width: '140px' },
  { key: 'balanceAfter', label: 'audit.balance', sortable: true, width: '140px' },
  { key: 'source', label: 'audit.source', sortable: true, width: '120px' },
]


const scrollContainer = ref<HTMLElement | null>(null)
const itemHeight = ref(48)
const tableHeight = ref(600)
const scrollTop = ref(0)


const auditRecords = ref<AuditRecord[]>([])
const loading = ref(false)
const hasMore = ref(true)
const cursor = ref<string | null>(null)
const newRecordsCount = ref(0)

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


const visibleRange = computed(() => {
  const start = Math.max(0, Math.floor(scrollTop.value / itemHeight.value) - 5)
  const visibleCount = Math.ceil(tableHeight.value / itemHeight.value) + 10
  const end = Math.min(sortedAuditRecords.value.length, start + visibleCount)
  return { start, end }
})

const virtualRecords = computed(() => {
  const { start, end } = visibleRange.value
  return sortedAuditRecords.value.slice(start, end).map((record, index) => ({
    record,
    top: (start + index) * itemHeight.value
  }))
})

const totalContentHeight = computed(() => {
  return sortedAuditRecords.value.length * itemHeight.value
})

function handleScroll(e: Event) {
  const target = e.target as HTMLElement
  scrollTop.value = target.scrollTop
}


watch(() => sortedAuditRecords.value.length, () => {
  if (scrollContainer.value) {
    scrollTop.value = scrollContainer.value.scrollTop
  }
})

function updateTableHeight() {
  const available = window.innerHeight - 300
  tableHeight.value = Math.max(400, Math.min(available, 800))
}

function handleTableSort(key: string) {
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


const displayedIds = ref<Set<string>>(new Set())
const newRecordIds = ref<Set<string>>(new Set())

const playerSearch = ref('')
const typeFilter = ref('')

const loadTrigger = ref<HTMLElement | null>(null)
let refreshInterval: ReturnType<typeof setInterval> | null = null
let observer: IntersectionObserver | null = null

function typeBadgeVariant(type: string) {
  const map: Record<string, 'success' | 'warning' | 'info' | 'error' | 'default'> = {
    DEPOSIT: 'success',
    WITHDRAW: 'warning',
    TRANSFER: 'info',
  }
  return map[type] || 'default'
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

async function silentRefresh() {
  if (loading.value) return

  try {
    const params = new URLSearchParams()
    params.append('pageSize', '50')

    const response = await apiClient.get(`/api/audit/search-cursor?${params}`)
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

async function loadAuditData() {
  if (loading.value || !hasMore.value) return

  loading.value = true
  try {
    let url = `/api/audit/search-cursor?pageSize=50`
    if (cursor.value) url += `&cursor=${encodeURIComponent(cursor.value)}`
    if (playerSearch.value) url += `&player=${encodeURIComponent(playerSearch.value)}`
    if (typeFilter.value) url += `&type=${typeFilter.value}`
    if (startTime.value) url += `&startTime=${startTime.value}`
    if (endTime.value) url += `&endTime=${endTime.value}`

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

function formatDate(timestamp: number): string {
  try {
    const normalized = normalizeTimezone(settingsStore.timezone)
    return formatInTimeZone(timestamp, normalized, 'yyyy-MM-dd HH:mm:ssXXX')
  } catch (e) {
    return new Date(timestamp).toLocaleString()
  }
}


/**
 * [SYNC-WEB-036] Export CSV with full data support:
 * - If date range is selected: fetch ALL records in that range
 * - Otherwise: export only the displayed records
 */
async function exportCSV() {
  if (hasDateFilter.value) {
    await exportAllData('csv')
  } else {
    if (!auditRecords.value.length) return
    const csv = exportAuditCSV(auditRecords.value)
    downloadFile(csv, 'audit-log.csv', 'text/csv')
  }
}

async function exportJSON() {
  if (hasDateFilter.value) {
    await exportAllData('json')
  } else {
    if (!auditRecords.value.length) return
    downloadFile(JSON.stringify(auditRecords.value, null, 2), 'audit-log.json', 'application/json')
  }
}

/**
 * [SYNC-WEB-037] Fetch all records for export (supports date range)
 */
async function exportAllData(format: 'csv' | 'json') {
  loading.value = true
  try {
    const allRecords: AuditRecord[] = []
    let currentCursor: string | null = null
    let hasMoreRecords = true
    const batchSize = 1000
    const maxRecords = 100000

    while (hasMoreRecords && allRecords.length < maxRecords) {
      let url = `/api/audit/search-cursor?pageSize=${batchSize}`
      if (currentCursor) url += `&cursor=${encodeURIComponent(currentCursor)}`
      if (playerSearch.value) url += `&player=${encodeURIComponent(playerSearch.value)}`
      if (typeFilter.value) url += `&type=${typeFilter.value}`
      if (startTime.value) url += `&startTime=${startTime.value}`
      if (endTime.value) url += `&endTime=${endTime.value}`

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
    loading.value = false
  }
}

function formatDateRangeForFilename(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const dateStr = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`

  if (startTime.value && endTime.value) {
    const start = new Date(startTime.value)
    const end = new Date(endTime.value)
    const startStr = `${start.getFullYear()}${pad(start.getMonth() + 1)}${pad(start.getDate())}`
    const endStr = `${end.getFullYear()}${pad(end.getMonth() + 1)}${pad(end.getDate())}`
    return `${startStr}-${endStr}`
  }

  return dateStr
}

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

function getRowClass(row: Record<string, unknown>): string {
  const id = row.id as string | undefined
  return id !== undefined && newRecordIds.value.has(id) ? 'new-record-row' : ''
}

function setupIntersectionObserver() {
  if (!loadTrigger.value) return

  observer = new IntersectionObserver(
    (entries) => {
      const entry = entries[0]
      if (entry.isIntersecting && hasMore.value && !loading.value) {
        loadAuditData()
      }
    },
    { threshold: 0.1 }
  )

  observer.observe(loadTrigger.value)
}


let auditSseHandler: ((data: unknown) => void) | null = null

onMounted(async () => {
  
  await nodesStore.fetchNodes().catch(() => {})
  applyDateRange()
  updateTableHeight()
  window.addEventListener('resize', updateTableHeight)

  loadAuditData()
  setupIntersectionObserver()

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
  window.removeEventListener('resize', updateTableHeight)
  if (refreshInterval) clearInterval(refreshInterval)
  if (observer) observer.disconnect()
  if (auditSseHandler) ws.off('audit', auditSseHandler)
})
</script>

<style scoped>
/* New record animation */
.new-record-row {
  animation: new-record-flash 1s ease-out;
}

@keyframes new-record-flash {
  0% {
    background-color: rgba(59, 130, 246, 0.3);
    transform: translateX(-10px);
  }
  100% {
    background-color: transparent;
    transform: translateX(0);
  }
}

/* Connection status animation */
@keyframes pulse-glow {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 4px currentColor;
  }
  50% {
    opacity: 0.6;
    box-shadow: 0 0 8px currentColor;
  }
}

/* Native date/datetime input dark mode */
input[type="date"]::-webkit-calendar-picker-indicator,
input[type="datetime-local"]::-webkit-calendar-picker-indicator {
  filter: invert(0.5);
  cursor: pointer;
}

.dark input[type="date"]::-webkit-calendar-picker-indicator,
.dark input[type="datetime-local"]::-webkit-calendar-picker-indicator {
  filter: invert(0.8);
}
</style>
