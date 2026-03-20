<template>
  <!-- Desktop sidebar -->
  <aside
    v-if="!isMobile"
    class="fixed left-0 top-0 h-full z-40 flex flex-col border-r border-surface-200 dark:border-surface-700 bg-surface-50/85 dark:bg-surface-950/85 backdrop-blur-xl transition-all duration-300 ease-out"
    :class="collapsed ? 'w-20' : 'w-64'"
  >
    <!-- Brand -->
    <div class="flex items-center gap-3 px-4 h-16 border-b border-surface-200/50 dark:border-surface-700/50">
      <div class="w-8 h-8 rounded-xl gradient-primary flex items-center justify-center flex-shrink-0 shadow-glow-sm">
        <Zap class="w-4 h-4 text-white" />
      </div>
      <span v-if="!collapsed" class="sidebar-label text-lg font-bold gradient-text">Syncmoney</span>
    </div>

    <!-- Server Node Selector -->
    <div v-if="nodesStore.enabledNodes.length > 0" class="px-3 py-2 border-b border-surface-200/50 dark:border-surface-700/50">
      <select
        v-model="selectedNodeIndex"
        class="w-full px-3 py-2 text-sm rounded-lg bg-surface-100 dark:bg-surface-800 border border-surface-200 dark:border-surface-700 text-surface-700 dark:text-surface-300 focus:outline-none focus:ring-2 focus:ring-primary/50"
        @change="handleNodeChange"
      >
        <option v-if="!nodesStore.currentNode" value="" disabled>
          {{ t('nodes.selectServer') || 'Select a server...' }}
        </option>
        <option
          v-for="(node, index) in nodesStore.enabledNodes"
          :key="index"
          :value="index"
        >
          {{ node.name }} {{ node.status === 'offline' ? '(Offline)' : '' }}
        </option>
      </select>
    </div>

    <!-- Navigation -->
    <nav class="flex-1 py-4 space-y-1 px-3 overflow-y-auto">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-link"
        :class="{ active: isActive(item.path) }"
        :title="collapsed ? t(item.label) : undefined"
      >
        <component :is="item.icon" class="w-5 h-5 flex-shrink-0" />
        <span v-if="!collapsed" class="sidebar-label">{{ t(item.label) }}</span>
      </router-link>
    </nav>

    <!-- Footer -->
    <div class="p-3 border-t border-surface-200/50 dark:border-surface-700/50 space-y-2">
      <button
        class="nav-link w-full justify-center text-surface-600 dark:text-surface-400 hover:text-surface-900 dark:hover:text-surface-100"
        @click="$emit('update:collapsed', !collapsed)"
      >
        <ChevronLeft v-if="!collapsed" class="w-5 h-5" />
        <ChevronRight v-else class="w-5 h-5" />
        <span v-if="!collapsed" class="sidebar-label">{{ t('nav.collapse') || 'Collapse' }}</span>
      </button>
      <button class="nav-link text-error w-full hover:bg-error/10 hover:text-error hover:translate-x-1" @click="handleLogout">
        <LogOut class="w-5 h-5 flex-shrink-0" />
        <span v-if="!collapsed" class="sidebar-label">{{ t('auth.logout') }}</span>
      </button>
    </div>
  </aside>

  <!-- Mobile sidebar overlay -->
  <Transition name="sidebar-slide">
    <aside
      v-if="isMobile && mobileOpen"
      class="fixed left-0 top-0 h-full w-64 z-50 flex flex-col border-r border-surface-200 dark:border-surface-700 bg-surface-50/95 dark:bg-surface-950/95 backdrop-blur-xl"
    >
      <!-- Brand -->
      <div class="flex items-center justify-between px-4 h-16 border-b border-surface-200/50 dark:border-surface-700/50">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-xl gradient-primary flex items-center justify-center shadow-glow-sm">
            <Zap class="w-4 h-4 text-white" />
          </div>
          <span class="text-lg font-bold gradient-text">Syncmoney</span>
        </div>
        <button class="p-2 rounded-lg hover:bg-surface-200 dark:hover:bg-surface-800 text-surface-600 dark:text-surface-400 transition-colors" @click="$emit('update:mobile-open', false)">
          <X class="w-5 h-5" />
        </button>
      </div>

      <!-- Navigation -->
      <nav class="flex-1 py-4 space-y-1 px-3 overflow-y-auto">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-link"
          :class="{ active: isActive(item.path) }"
          @click="$emit('update:mobile-open', false)"
        >
          <component :is="item.icon" class="w-5 h-5" />
          <span>{{ t(item.label) }}</span>
        </router-link>
      </nav>

      <!-- Logout -->
      <div class="p-3 border-t border-surface-700/50">
        <button class="nav-link text-error w-full" @click="handleLogout">
          <LogOut class="w-5 h-5" />
          <span>{{ t('auth.logout') }}</span>
        </button>
      </div>
    </aside>
  </Transition>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNodesStore } from '@/stores/nodes'
import { ref, watch, computed } from 'vue'
import { useBreakpoints, breakpointsTailwind } from '@vueuse/core'
import {
  LayoutDashboard, Activity, FileText, Settings, Wrench,
  LogOut, ChevronLeft, ChevronRight, Zap, X, LayoutGrid, Server
} from 'lucide-vue-next'

interface Props {
  collapsed: boolean
  mobileOpen: boolean
}

defineProps<Props>()
defineEmits<{
  'update:collapsed': [value: boolean]
  'update:mobile-open': [value: boolean]
}>()

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const nodesStore = useNodesStore()
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobile = breakpoints.smaller('md')

const selectedNodeIndex = ref<number>(0)

watch(() => nodesStore.currentNodeIndex, (newIndex) => {
  selectedNodeIndex.value = newIndex
}, { immediate: true })

function handleNodeChange() {
  if (typeof selectedNodeIndex.value === 'number') {
    nodesStore.selectNode(selectedNodeIndex.value)
  }
}

const baseNavItems = [
  { path: '/dashboard', icon: LayoutDashboard, label: 'nav.dashboard' },
  { path: '/system',    icon: Activity,         label: 'nav.systemStatus' },
  { path: '/audit',     icon: FileText,         label: 'nav.auditLog' },
  { path: '/config',    icon: Wrench,           label: 'nav.config' },
  { path: '/settings',  icon: Settings,         label: 'nav.settings' },
]

const centralNavItems = computed(() => {
  const items = []
  if (nodesStore.enabledNodes.length > 0) {
    items.push({ path: '/central', icon: LayoutGrid, label: 'nav.centralDashboard' })
  }
  if (authStore.isAuthenticated) {
    items.push({ path: '/nodes', icon: Server, label: 'nav.nodesManagement' })
  }
  return items
})

const navItems = computed(() => [...baseNavItems, ...centralNavItems.value])

function isActive(path: string): boolean {
  return route.path === path
}

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.nav-link {
  @apply flex items-center gap-3 px-3 py-3 rounded-xl text-sm text-surface-600 dark:text-surface-400 font-medium;
  @apply transition-all duration-300 ease-out no-underline relative overflow-hidden;
  will-change: transform, color, background-color;
}
.nav-link::before {
  content: '';
  @apply absolute inset-0 bg-gradient-to-r from-[var(--color-primary)] to-transparent opacity-0 transition-opacity duration-300;
}
.nav-link:hover:not(.text-error) {
  color: var(--color-primary);
  @apply translate-x-1;
}
.nav-link:hover::before {
  @apply opacity-10;
}
.nav-link.active {
  color: var(--color-primary);
  background-color: color-mix(in srgb, var(--color-primary) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-primary) 20%, transparent);
  box-shadow: inset 4px 0 0 var(--color-primary), 0 0 15px var(--color-primary-glow);
  font-weight: 600;
}
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 0.25rem;
  background-color: var(--color-primary);
  box-shadow: 0 0 10px var(--color-primary-glow);
}

/* Mobile slide */
.sidebar-slide-enter-active { transition: transform 0.4s cubic-bezier(0.4, 0, 0.2, 1); }
.sidebar-slide-leave-active { transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1); }
.sidebar-slide-enter-from,
.sidebar-slide-leave-to { transform: translateX(-100%); }
</style>
