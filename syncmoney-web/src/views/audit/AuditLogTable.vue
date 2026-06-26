<template>
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
        <!-- Desktop header (hidden on mobile card view) -->
        <table v-if="!isMobile" class="w-full table-fixed">
          <thead class="sticky top-0 z-20 bg-surface-100 dark:bg-surface-800 shadow-sm">
            <tr>
              <th
                v-for="col in columns"
                :key="col.key"
                @click="col.sortable && $emit('sort', col.key)"
                @keydown.enter.prevent="col.sortable && $emit('sort', col.key)"
                @keydown.space.prevent="col.sortable && $emit('sort', col.key)"
                :tabindex="col.sortable ? 0 : undefined"
                :role="col.sortable ? 'button' : undefined"
                :aria-sort="ariaSort(col.key)"
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
          <!-- Desktop table row -->
          <table v-if="!isMobile" class="w-full table-fixed">
            <tbody>
              <tr
                tabindex="0"
                role="button"
                :aria-label="t('audit.detail.title')"
                @click="$emit('row-click', item.record)"
                @keydown.enter.prevent="$emit('row-click', item.record)"
                @keydown.space.prevent="$emit('row-click', item.record)"
                :class="[
                  'border-b border-surface-200 dark:border-surface-700 hover:bg-surface-50 dark:hover:bg-surface-800/50 transition-colors cursor-pointer focus:outline-none focus:bg-surface-50 dark:focus:bg-surface-800/50',
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

          <!-- Mobile card row -->
          <div
            v-else
            tabindex="0"
            role="button"
            :aria-label="t('audit.detail.title')"
            @click="$emit('row-click', item.record)"
            @keydown.enter.prevent="$emit('row-click', item.record)"
            @keydown.space.prevent="$emit('row-click', item.record)"
            :class="[
              'mx-3 my-1.5 p-3 rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-50/50 dark:bg-surface-800/40 cursor-pointer transition-colors hover:bg-surface-100 dark:hover:bg-surface-800/70 focus:outline-none focus:border-primary',
              getRowClass(item.record) ? 'new-record-row' : ''
            ]"
            :style="{ height: (itemHeight - 12) + 'px' }"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-medium text-surface-900 dark:text-surface-100 truncate">{{ item.record?.playerName }}</span>
              <Badge :variant="typeBadgeVariant(String(item.record?.type))">
                {{ t(`audit.typeOptions.${String(item.record?.type).toLowerCase()}`) }}
              </Badge>
            </div>
            <div class="mt-1.5 flex items-center justify-between gap-2">
              <span class="text-surface-500 dark:text-surface-400 text-xs font-mono">{{ formatDate(Number(item.record?.timestamp)) }}</span>
              <span class="font-mono font-bold text-sm" :class="parseFloat(String(item.record?.amount)) >= 0 ? 'text-success' : 'text-error'">
                {{ parseFloat(String(item.record?.amount)) >= 0 ? '+' : '' }}{{ item.record?.amount }}
              </span>
            </div>
            <div class="mt-1 flex items-center justify-between gap-2 text-xs text-surface-500 dark:text-surface-400">
              <span class="truncate">{{ t('audit.balance') }}: <span class="font-mono">{{ item.record?.balanceAfter }}</span></span>
              <span class="truncate">{{ item.record?.source }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="records.length === 0 && !loading" class="p-8">
        <EmptyState :icon="FileText" :title="t('common.noData')" />
      </div>

      <!-- Load more trigger -->
      <div ref="loadTrigger" class="p-4 border-t border-surface-700/50">
        <div v-if="loading && records.length > 0" class="text-center py-4 text-surface-500">
          {{ t('audit.loadingMore') }}
        </div>
        <div v-else-if="!hasMore && records.length > 0" class="text-center py-4 text-surface-500">
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AuditRecord } from '@/api/client'
import { useSettingsStore } from '@/stores/settings'
import { formatInTimeZone } from 'date-fns-tz'
import { normalizeTimezone } from '@/utils/timezone'
import { FileText, ArrowUp, ArrowDown } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Badge from '@/components/common/Badge.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const props = defineProps<{
  records: AuditRecord[]
  loading: boolean
  hasMore: boolean
  isInitialLoading: boolean
  newRecordIds: Set<string>
  sortState: { key: string; order: 'asc' | 'desc' }
}>()

const emit = defineEmits<{
  sort: [key: string]
  'load-more': []
  'row-click': [record: AuditRecord]
}>()

const { t } = useI18n()
const settingsStore = useSettingsStore()

const columns: { key: string; label: string; sortable?: boolean; width?: string }[] = [
  { key: 'timestamp', label: 'audit.time', sortable: true, width: '180px' },
  { key: 'playerName', label: 'audit.player', sortable: true, width: 'minmax(120px, 1fr)' },
  { key: 'type', label: 'audit.type', sortable: true, width: '100px' },
  { key: 'amount', label: 'audit.amount', sortable: true, width: '140px' },
  { key: 'balanceAfter', label: 'audit.balance', sortable: true, width: '140px' },
  { key: 'source', label: 'audit.source', sortable: true, width: '120px' },
]

const scrollContainer = ref<HTMLElement | null>(null)
const loadTrigger = ref<HTMLElement | null>(null)
const isMobile = ref(false)
const itemHeight = ref(48)
const tableHeight = ref(600)
const scrollTop = ref(0)

const visibleRange = computed(() => {
  const start = Math.max(0, Math.floor(scrollTop.value / itemHeight.value) - 5)
  const visibleCount = Math.ceil(tableHeight.value / itemHeight.value) + 10
  const end = Math.min(props.records.length, start + visibleCount)
  return { start, end }
})

const virtualRecords = computed(() => {
  const { start, end } = visibleRange.value
  return props.records.slice(start, end).map((record, index) => ({
    record,
    top: (start + index) * itemHeight.value
  }))
})

const totalContentHeight = computed(() => props.records.length * itemHeight.value)

function handleScroll(e: Event) {
  const target = e.target as HTMLElement
  scrollTop.value = target.scrollTop
}

watch(() => props.records.length, () => {
  if (scrollContainer.value) {
    scrollTop.value = scrollContainer.value.scrollTop
  }
})

const mobileQuery = window.matchMedia('(max-width: 640px)')

function updateLayout() {
  isMobile.value = mobileQuery.matches
  // [SYNC-WEB-045] Cards need more vertical space than table rows.
  itemHeight.value = isMobile.value ? 132 : 48
  const available = window.innerHeight - 300
  tableHeight.value = Math.max(400, Math.min(available, 800))
}

function ariaSort(key: string): 'ascending' | 'descending' | 'none' {
  if (props.sortState.key !== key) return 'none'
  return props.sortState.order === 'asc' ? 'ascending' : 'descending'
}

function typeBadgeVariant(type: string) {
  const map: Record<string, 'success' | 'warning' | 'info' | 'error' | 'default'> = {
    DEPOSIT: 'success',
    WITHDRAW: 'warning',
    TRANSFER: 'info',
  }
  return map[type] || 'default'
}

function formatDate(timestamp: number): string {
  try {
    const normalized = normalizeTimezone(settingsStore.timezone)
    return formatInTimeZone(timestamp, normalized, 'yyyy-MM-dd HH:mm:ssXXX')
  } catch {
    return new Date(timestamp).toLocaleString()
  }
}

function getRowClass(row: AuditRecord | undefined): string {
  const id = row?.id
  return id !== undefined && props.newRecordIds.has(id) ? 'new-record-row' : ''
}

let observer: IntersectionObserver | null = null

function setupIntersectionObserver() {
  if (!loadTrigger.value) return

  observer = new IntersectionObserver(
    (entries) => {
      const entry = entries[0]
      if (entry.isIntersecting && props.hasMore && !props.loading) {
        emit('load-more')
      }
    },
    { threshold: 0.1 }
  )

  observer.observe(loadTrigger.value)
}

onMounted(() => {
  updateLayout()
  window.addEventListener('resize', updateLayout)
  setupIntersectionObserver()
})

onUnmounted(() => {
  window.removeEventListener('resize', updateLayout)
  if (observer) observer.disconnect()
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
</style>
