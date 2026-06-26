<template>
  <div class="space-y-6">
    <!-- New records notification banner -->
    <button
      v-if="newRecordsCount > 0"
      type="button"
      class="fixed bottom-6 right-6 z-50 flex items-center gap-2 px-4 py-2.5 bg-primary text-white rounded-full shadow-lg cursor-pointer select-none transition-all duration-300 hover:scale-105 focus:outline-none focus:ring-2 focus:ring-primary"
      @click="scrollToTop"
    >
      <span class="text-sm font-medium">{{ newRecordsCount }} {{ t('audit.newRecords') }} ↑</span>
    </button>

    <!-- Filter bar -->
    <AuditLogFilters
      v-model:player="playerSearch"
      v-model:type="typeFilter"
      v-model:realtime="isRealtimeEnabled"
      v-model:start-time="startTime"
      v-model:end-time="endTime"
      :connection-status="connectionStatus"
      :connection-status-text="connectionStatusText"
      @search="handleSearch"
      @export="onExport"
      @toggle-realtime="toggleRealtime"
    />

    <!-- Table with virtual scrolling -->
    <AuditLogTable
      :records="sortedAuditRecords"
      :loading="loading"
      :has-more="hasMore"
      :is-initial-loading="isInitialLoading"
      :new-record-ids="newRecordIds"
      :sort-state="sortState"
      @sort="handleSort"
      @load-more="loadAuditData"
      @row-click="openDetail"
    />

    <!-- Record detail panel -->
    <AuditLogDetailPanel
      :record="selectedRecord"
      :open="detailOpen"
      @close="closeDetail"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AuditRecord } from '@/api/client'
import { useSSEStore } from '@/stores/sse'
import { useAuditData } from '@/composables/useAuditData'
import { useAuditExport } from '@/composables/useAuditExport'
import AuditLogFilters from './audit/AuditLogFilters.vue'
import AuditLogTable from './audit/AuditLogTable.vue'
import AuditLogDetailPanel from './audit/AuditLogDetailPanel.vue'

const { t } = useI18n()
const ws = useSSEStore()

const {
  sortedAuditRecords,
  auditRecords,
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
} = useAuditData()

const { exportCSV, exportJSON } = useAuditExport({
  records: auditRecords,
  playerSearch,
  typeFilter,
  startTime,
  endTime,
  loading,
})

function onExport(format: 'csv' | 'json') {
  if (format === 'csv') exportCSV()
  else exportJSON()
}

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

const selectedRecord = ref<AuditRecord | null>(null)
const detailOpen = ref(false)

function openDetail(record: AuditRecord) {
  selectedRecord.value = record
  detailOpen.value = true
}

function closeDetail() {
  detailOpen.value = false
}
</script>
