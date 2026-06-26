<template>
  <Card variant="glass" padding="sm">
    <div class="space-y-3">
      <!-- Row 1: Primary filters -->
      <div class="flex flex-wrap items-center gap-3">
        <!-- Real-time toggle and connection status -->
        <div class="flex items-center gap-2">
          <label class="flex items-center gap-2 cursor-pointer select-none">
            <input
              type="checkbox"
              :checked="realtime"
              class="sr-only peer"
              :aria-label="t('audit.realtime')"
              @change="onRealtimeChange"
            />
            <div :class="[
              'relative w-11 h-6 rounded-full transition-colors duration-300',
              'peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full',
              'peer-checked:after:border-white after:content-[\'\'] after:absolute after:top-[2px]',
              'after:start-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all',
              realtime ? 'bg-success' : 'bg-error'
            ]"></div>
            <span :class="['text-sm font-medium transition-colors duration-300', realtime ? 'text-success' : 'text-error']">
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
        <AuditLogSearchBar
          :model-value="player"
          :placeholder="t('audit.filters.player')"
          @update:model-value="player = $event"
          @enter="$emit('search')"
        />

        <!-- Type filter -->
        <select
          :value="type"
          class="px-3 py-2 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300 appearance-none cursor-pointer"
          :aria-label="t('audit.filters.type')"
          @change="type = ($event.target as HTMLSelectElement).value"
        >
          <option value="" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.all') }}</option>
          <option value="DEPOSIT" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.deposit') }}</option>
          <option value="WITHDRAW" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.withdraw') }}</option>
          <option value="TRANSFER" class="bg-surface-50 dark:bg-surface-900">{{ t('audit.typeOptions.transfer') }}</option>
        </select>

        <!-- Action buttons -->
        <div class="flex items-center gap-2 ml-auto">
          <Button variant="primary" size="sm" :icon="Search" @click="$emit('search')">
            {{ t('common.search') }}
          </Button>

          <Button variant="outline" size="sm" :icon="Download" @click="$emit('export', 'csv')">
            CSV
          </Button>
          <Button variant="outline" size="sm" :icon="Download" @click="$emit('export', 'json')">
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
            :aria-label="t('audit.filters.startTime')"
          />

          <span class="text-surface-400 text-sm select-none">—</span>

          <!-- End datetime input -->
          <input
            type="datetime-local"
            v-model="endDateTimeLocal"
            @change="onDateTimeChange"
            class="px-2 py-1.5 w-[150px] bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary transition-all duration-300"
            :aria-label="t('audit.filters.endTime')"
          />

          <!-- Clear date button -->
          <button
            v-if="hasDateFilter"
            @click="clearDateFilter"
            class="p-1.5 text-surface-400 hover:text-error transition-colors"
            :title="t('audit.filters.clearDate')"
            :aria-label="t('audit.filters.clearDate')"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  </Card>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search, Download, X } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import AuditLogSearchBar from './AuditLogSearchBar.vue'

const { t } = useI18n()

const player = defineModel<string>('player', { default: '' })
const type = defineModel<string>('type', { default: '' })
const realtime = defineModel<boolean>('realtime', { default: true })
const startTime = defineModel<number | null>('startTime', { default: null })
const endTime = defineModel<number | null>('endTime', { default: null })

defineProps<{
  connectionStatus: string
  connectionStatusText: string
}>()

const emit = defineEmits<{
  search: []
  export: [format: 'csv' | 'json']
  'toggle-realtime': [enabled: boolean]
}>()

function onRealtimeChange(e: Event) {
  const checked = (e.target as HTMLInputElement).checked
  realtime.value = checked
  emit('toggle-realtime', checked)
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

const hasDateFilter = computed(() => startTime.value !== null || endTime.value !== null)

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
  emit('search')
}
</script>

<style scoped>
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
