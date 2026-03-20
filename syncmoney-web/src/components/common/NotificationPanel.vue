<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { X, Bell, Trash2 } from 'lucide-vue-next'
import { useNotificationStore } from '@/stores/notification'
import type { Notification } from '@/stores/notification'

interface Props {
  notifications?: Notification[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  clear: []
}>()

const { t } = useI18n()
const notificationStore = useNotificationStore()


const displayAlerts = computed(() => {
  return props.notifications || notificationStore.alerts
})

function formatTime(timestamp: number): string {
  const date = new Date(timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function getTypeColor(type: Notification['type']): string {
  const colors: Record<Notification['type'], string> = {
    info: 'border-l-info text-info',
    success: 'border-l-success text-success',
    warning: 'border-l-warning text-warning',
    error: 'border-l-error text-error'
  }
  return colors[type] || 'border-l-info text-info'
}

function getCategoryIcon(category?: string): string {
  const icons: Record<string, string> = {
    security: '🛡️',
    system: '⚙️',
    transaction: '💰',
    audit: '📋',
    general: '📌'
  }
  return icons[category || 'general'] || '📌'
}

function handleAlertClick(alert: Notification) {

  notificationStore.markAlertAsRead(alert.id)

  notificationStore.removeAlert(alert.id)
}

function handleClearAll() {
  notificationStore.clearAlerts()
  emit('clear')
}
</script>

<template>
  <Transition name="slide">
    <div
      class="absolute right-0 top-12 w-80 max-h-96 overflow-hidden bg-surface-50 dark:bg-surface-900 border border-surface-200 dark:border-surface-700 rounded-xl shadow-2xl z-50 flex flex-col"
    >
      <!-- Header -->
      <div class="flex items-center justify-between px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-white dark:bg-surface-800/50 rounded-t-xl">
        <span class="font-bold text-surface-900 dark:text-surface-100">{{ t('notifications.title') }}</span>
        <div class="flex items-center gap-2">
          <button
            v-if="displayAlerts.length > 0"
            @click="handleClearAll"
            class="p-1.5 rounded-lg hover:bg-surface-200 dark:hover:bg-surface-700 text-surface-400 hover:text-error transition-all"
            :title="t('notifications.clearAll')"
          >
            <Trash2 class="w-4 h-4" />
          </button>
          <button
            @click="emit('close')"
            class="p-1.5 rounded-lg hover:bg-surface-200 dark:hover:bg-surface-700 text-surface-400 hover:text-surface-600 dark:hover:text-surface-300 transition-all"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Alert List -->
      <div class="flex-1 overflow-y-auto">
        <div v-if="displayAlerts.length === 0" class="p-8 text-center text-surface-400 dark:text-surface-500">
          <Bell class="w-8 h-8 mx-auto mb-2 opacity-50" />
          <p class="text-sm">{{ t('notifications.empty') }}</p>
        </div>

        <div v-else>
          <div
            v-for="alert in displayAlerts"
            :key="alert.id"
            class="px-4 py-3 border-b border-surface-200 dark:border-surface-700/50 hover:bg-surface-200 dark:hover:bg-surface-800/50 transition-colors cursor-pointer border-l-4"
            :class="[getTypeColor(alert.type), !alert.read ? 'bg-surface-100 dark:bg-surface-800' : '']"
            @click="handleAlertClick(alert)"
          >
            <div class="flex items-start justify-between gap-2">
              <div class="flex items-start gap-2 flex-1 min-w-0">
                <span class="text-sm flex-shrink-0 mt-0.5">{{ getCategoryIcon(alert.category) }}</span>
                <div class="flex-1 min-w-0">
                  <p class="font-medium text-sm text-surface-900 dark:text-surface-100 truncate">
                    {{ alert.title }}
                  </p>
                  <p v-if="alert.message" class="text-xs text-surface-500 dark:text-surface-400 mt-0.5 truncate">
                    {{ alert.message }}
                  </p>
                </div>
              </div>
              <span class="text-xs text-surface-400 dark:text-surface-500 whitespace-nowrap">
                {{ formatTime(alert.timestamp) }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: all 0.2s ease;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(-10px) translateX(10px);
}
</style>
