<template>
  <Teleport to="body">
    <div class="fixed top-4 right-4 z-[60] flex flex-col gap-3 max-w-sm">
      <TransitionGroup name="notification">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="glass-card flex items-start gap-3 p-4 shadow-lg"
          :class="borderClass(toast.type)"
        >
          <div class="flex-shrink-0 mt-0.5">
            <CheckCircle v-if="toast.type === 'success'" class="w-5 h-5 text-success" />
            <XCircle v-else-if="toast.type === 'error'" class="w-5 h-5 text-error" />
            <AlertTriangle v-else-if="toast.type === 'warning'" class="w-5 h-5 text-warning" />
            <Info v-else class="w-5 h-5 text-info" />
          </div>
          <div class="flex-1 min-w-0">
            <p class="text-sm font-semibold text-gray-900 dark:text-surface-100">{{ toast.title }}</p>
            <p v-if="toast.message" class="text-xs text-gray-600 dark:text-surface-400 mt-0.5">{{ toast.message }}</p>
          </div>
          <button
            class="p-1 rounded-lg text-gray-500 dark:text-surface-500 hover:text-gray-700 dark:hover:text-surface-200 hover:bg-gray-100 dark:hover:bg-surface-700 transition-colors"
            @click="removeToast(toast.id)"
          >
            <X class="w-4 h-4" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useNotificationStore } from '@/stores/notification'
import { CheckCircle, XCircle, AlertTriangle, Info, X } from 'lucide-vue-next'

const notificationStore = useNotificationStore()
const toasts = computed(() => notificationStore.toasts)
const removeToast = notificationStore.removeToast

function borderClass(type: string) {
  const map: Record<string, string> = {
    success: 'border-l-2 border-l-success',
    error:   'border-l-2 border-l-error',
    warning: 'border-l-2 border-l-warning',
    info:    'border-l-2 border-l-info',
  }
  return map[type] || ''
}
</script>

<style scoped>
.notification-enter-active,
.notification-leave-active {
  transition: all 0.3s ease;
}
.notification-enter-from {
  opacity: 0;
  transform: translateX(100%);
}
.notification-leave-to {
  opacity: 0;
  transform: translateX(100%);
}
</style>
