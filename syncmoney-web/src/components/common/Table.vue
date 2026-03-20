<template>
  <div class="overflow-x-auto">
    <table class="w-full text-left">
      <thead>
        <tr class="border-b border-surface-200 dark:border-surface-700/50">
          <th
            v-for="column in columns"
            :key="column.key"
            :class="[
              column.class,
              'px-4 py-3 text-xs font-medium text-surface-600 dark:text-surface-400 uppercase tracking-wider',
              column.sortable ? 'cursor-pointer hover:bg-surface-100 dark:hover:bg-surface-800 select-none' : ''
            ]"
            @click="column.sortable && handleSort(column.key)"
          >
            <span class="flex items-center gap-1">
              {{ t(column.label) }}
              <span v-if="column.sortable && sortKey === column.key" class="text-xs">
                {{ sortOrder === 'asc' ? '↑' : '↓' }}
              </span>
            </span>
          </th>
        </tr>
      </thead>
    </table>

    <!-- Regular table body -->
    <table class="w-full text-left">
      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length" class="text-center py-8 text-surface-600 dark:text-surface-500">
            {{ t('common.loading') }}
          </td>
        </tr>
        <tr v-else-if="!data || data.length === 0">
          <td :colspan="columns.length" class="text-center py-8 text-surface-600 dark:text-surface-500">
            {{ t('common.noData') }}
          </td>
        </tr>
        <tr
          v-else
          v-for="(row, index) in sortedData"
          :key="rowKeyValue ? (row[rowKeyValue as keyof typeof row] as string | number) : index"
          :class="[
            'border-b border-surface-200 dark:border-surface-800 hover:bg-surface-100 dark:hover:bg-surface-800/50 transition-colors',
            props.rowClass?.(row)
          ]"
        >
          <td
            v-for="column in columns"
            :key="column.key"
            :class="[column.class, 'px-4 py-3 text-sm text-surface-800 dark:text-surface-300']"
          >
            <slot :name="`cell-${column.key}`" :row="row" :value="row[column.key]">
              {{ row[column.key] }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface Column {
  key: string
  label: string
  sortable?: boolean
  width?: string
  class?: string
}

interface TableRow {
  [key: string]: string | number | boolean | undefined | Record<string, unknown>
}

interface Props {
  columns: Column[]
  data?: TableRow[]
  loading?: boolean
  rowKey?: string
  rowClass?: (row: Record<string, unknown>) => string
}

const props = defineProps<Props>()

const rowKeyValue = props.rowKey || 'id'


const sortKey = ref<string | null>(null)
const sortOrder = ref<'asc' | 'desc'>('asc')

const emit = defineEmits<{
  sort: [payload: { key: string; order: 'asc' | 'desc' }]
}>()

function handleSort(key: string) {
  if (sortKey.value === key) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortOrder.value = 'asc'
  }
  emit('sort', { key: sortKey.value, order: sortOrder.value })
}

const sortedData = computed(() => {
  if (!sortKey.value || !props.data) {
    return props.data || []
  }

  return [...props.data].sort((a, b) => {
    const aVal = a[sortKey.value!]
    const bVal = b[sortKey.value!]
    const modifier = sortOrder.value === 'asc' ? 1 : -1

    if (aVal === undefined || aVal === null) return 1 * modifier
    if (bVal === undefined || bVal === null) return -1 * modifier

    if (typeof aVal === 'number' && typeof bVal === 'number') {
      return (aVal - bVal) * modifier
    }

    return String(aVal).localeCompare(String(bVal)) * modifier
  })
})
</script>
