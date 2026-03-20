<template>
  <header class="h-16 glass-card border border-surface-200 dark:border-surface-700 flex items-center justify-between px-4 md:px-6 sticky top-4 z-20 mx-4 md:mx-6 lg:mx-8 rounded-2xl mb-6 backdrop-blur-xl bg-surface-50/70 dark:bg-surface-950/70 shadow-[0_8px_32px_var(--glass-shadow-color)] transition-all duration-300">
    <!-- Left: hamburger + page title -->
    <div class="flex items-center gap-3">
      <button
        v-if="isMobile"
        class="p-2 rounded-lg hover:bg-primary/10 hover:text-primary text-surface-400 transition-all duration-300"
        @click="$emit('toggleSidebar')"
      >
        <Menu class="w-5 h-5" />
      </button>
      <div class="flex items-center gap-3">
        <div class="w-1.5 h-6 bg-primary rounded-full animate-pulse-dot shadow-glow-primary"></div>
        <h1 class="text-lg font-bold gradient-text tracking-wide">{{ t(pageTitle) }}</h1>
      </div>
    </div>

    <!-- Right: status + theme toggle + notifications + locale -->
    <div class="flex items-center gap-4">
      <StatusDot :status="connectionStatus" />

      <!-- Theme Toggle -->
      <button
        class="p-2 rounded-lg hover:bg-primary/10 hover:text-primary text-surface-400 transition-all duration-300 relative group"
        @click="toggleTheme"
        :title="t('nav.toggleTheme')"
      >
        <Moon v-if="settingsStore.theme === 'light'" class="w-5 h-5 group-hover:scale-110 transition-transform" />
        <Sun v-else class="w-5 h-5 group-hover:scale-110 transition-transform" />
      </button>

      <!-- Notifications Bell - always visible -->
      <div class="relative">
        <button
          class="p-2 rounded-lg hover:bg-primary/10 hover:text-primary text-surface-400 transition-all duration-300 relative group"
          @click="showNotifications = !showNotifications"
          :title="t('nav.notifications')"
        >
          <Bell class="w-5 h-5 group-hover:scale-110 transition-transform" />
          <span
            v-if="unreadCount > 0"
            class="absolute top-0 right-0 w-4 h-4 bg-primary shadow-glow-primary text-surface-bg text-[10px] font-bold rounded-full flex items-center justify-center translate-x-1 -translate-y-1"
          >
            {{ unreadCount > 9 ? '9+' : unreadCount }}
          </span>
        </button>
        <NotificationPanel 
          v-if="showNotifications" 
          @close="closeNotifications"
          @clear="clearNotifications"
        />
      </div>

      <select
        v-model="currentLocale"
        class="px-3 py-1.5 bg-surface-50/50 dark:bg-surface-950/50 border border-surface-200 dark:border-surface-700 hover:border-primary/50 rounded-lg text-surface-600 dark:text-surface-200 text-sm focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300 appearance-none cursor-pointer [&::-ms-expand]:hidden [&::-webkit-appearance]:none"
        @change="handleLocaleChange"
      >
        <option value="zh-TW" class="bg-surface-50 dark:bg-surface-900 text-surface-900 dark:text-surface-100">TW</option>
        <option value="en-US" class="bg-surface-50 dark:bg-surface-900 text-surface-900 dark:text-surface-100">EN</option>
      </select>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useSettingsStore } from '@/stores/settings'
import { useNotificationStore } from '@/stores/notification'
import { useSSEStore } from '@/stores/sse'
import { useBreakpoints, breakpointsTailwind } from '@vueuse/core'
import { Menu, Bell, Sun, Moon } from 'lucide-vue-next'
import StatusDot from '@/components/common/StatusDot.vue'
import NotificationPanel from '@/components/common/NotificationPanel.vue'

interface Props {
  sidebarCollapsed?: boolean
}

defineProps<Props>()
defineEmits<{
  toggleSidebar: []
}>()

const { t, locale } = useI18n()
const route = useRoute()
const settingsStore = useSettingsStore()
const notificationStore = useNotificationStore()
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobile = breakpoints.smaller('md')

const sseStore = useSSEStore()

const currentLocale = ref(settingsStore.locale)
const showNotifications = ref(false)


const connectionStatus = computed<'connected' | 'disconnected' | 'warning' | 'loading'>(() => {
  if (sseStore.error) {
    return 'warning'
  }
  if (sseStore.connected) {
    return 'connected'
  }
  return 'disconnected'
})

const unreadCount = computed(() => notificationStore.unreadAlertCount)

const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    '/dashboard': 'nav.dashboard',
    '/system':    'nav.systemStatus',
    '/audit':     'nav.auditLog',
    '/config':    'nav.config',
    '/settings':  'nav.settings',
  }
  return titles[route.path] || ''
})

function handleLocaleChange() {
  const newLocale = currentLocale.value as 'zh-TW' | 'en-US'
  settingsStore.setLocale(newLocale)
  locale.value = newLocale
}

function toggleTheme() {
  const newTheme = settingsStore.theme === 'dark' ? 'light' : 'dark'
  settingsStore.setTheme(newTheme)
}

function closeNotifications() {
  showNotifications.value = false
}

function clearNotifications() {
  notificationStore.clearAlerts()
}
</script>
