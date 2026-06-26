<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="open && record"
        class="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm"
        @click.self="$emit('close')"
      >
        <Transition name="modal-content" appear>
          <div
            ref="dialogRef"
            role="dialog"
            aria-modal="true"
            :aria-label="t('audit.detail.title')"
            tabindex="-1"
            class="glass-card w-full max-w-md max-h-[85vh] overflow-y-auto p-5 focus:outline-none"
            @keydown.esc="$emit('close')"
          >
            <div class="flex items-center justify-between mb-4">
              <h2 class="text-lg font-semibold text-surface-900 dark:text-surface-100">
                {{ t('audit.detail.title') }}
              </h2>
              <button
                ref="closeRef"
                class="p-1.5 rounded-lg text-surface-400 hover:text-error hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors focus:outline-none focus:ring-2 focus:ring-primary"
                :aria-label="t('audit.detail.close')"
                @click="$emit('close')"
              >
                <X class="w-5 h-5" />
              </button>
            </div>

            <dl class="space-y-2.5 text-sm">
              <div v-for="row in rows" :key="row.label" class="flex items-start justify-between gap-4">
                <dt class="text-surface-500 dark:text-surface-400 shrink-0">{{ row.label }}</dt>
                <dd class="text-right font-mono text-surface-900 dark:text-surface-100 break-all">
                  <Badge v-if="row.badge" :variant="typeBadgeVariant(String(record.type))">{{ row.value }}</Badge>
                  <span v-else :class="row.class">{{ row.value }}</span>
                </dd>
              </div>
            </dl>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { X } from 'lucide-vue-next'
import type { AuditRecord } from '@/api/client'
import { useSettingsStore } from '@/stores/settings'
import { formatInTimeZone } from 'date-fns-tz'
import { normalizeTimezone } from '@/utils/timezone'
import Badge from '@/components/common/Badge.vue'

const props = defineProps<{
  record: AuditRecord | null
  open: boolean
}>()

defineEmits<{ close: [] }>()

const { t } = useI18n()
const settingsStore = useSettingsStore()

const dialogRef = ref<HTMLElement | null>(null)
const closeRef = ref<HTMLElement | null>(null)

watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen) {
      await nextTick()
      closeRef.value?.focus()
    }
  }
)

function formatDate(timestamp: number): string {
  try {
    const normalized = normalizeTimezone(settingsStore.timezone)
    return formatInTimeZone(timestamp, normalized, 'yyyy-MM-dd HH:mm:ssXXX')
  } catch {
    return new Date(timestamp).toLocaleString()
  }
}

function typeBadgeVariant(type: string) {
  const map: Record<string, 'success' | 'warning' | 'info' | 'error' | 'default'> = {
    DEPOSIT: 'success',
    WITHDRAW: 'warning',
    TRANSFER: 'info',
  }
  return map[type] || 'default'
}

interface DetailRow {
  label: string
  value: string
  badge?: boolean
  class?: string
}

const rows = computed<DetailRow[]>(() => {
  const r = props.record
  if (!r) return []

  const amountNum = parseFloat(String(r.amount))
  const list: DetailRow[] = [
    { label: t('audit.time'), value: formatDate(Number(r.timestamp)) },
    { label: t('audit.player'), value: r.playerName || r.playerUuid },
    { label: t('audit.type'), value: t(`audit.typeOptions.${String(r.type).toLowerCase()}`), badge: true },
    {
      label: t('audit.amount'),
      value: `${amountNum >= 0 ? '+' : ''}${r.amount}`,
      class: amountNum >= 0 ? 'text-success font-bold' : 'text-error font-bold',
    },
    { label: t('audit.detail.balanceBefore'), value: r.balanceBefore },
    { label: t('audit.balance'), value: r.balanceAfter },
    { label: t('audit.source'), value: r.source },
    { label: t('audit.server'), value: r.serverName },
  ]

  if (r.targetName) list.push({ label: t('audit.detail.target'), value: r.targetName })
  if (r.reason) list.push({ label: t('audit.reason'), value: r.reason })
  if (r.playerUuid) list.push({ label: t('audit.detail.playerUuid'), value: r.playerUuid })
  list.push({ label: t('audit.detail.id'), value: r.id })

  return list
})
</script>
