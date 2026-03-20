<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-950 transition-colors duration-300">
    <NotificationArea />

    <template v-if="showLayout">
      <Sidebar
        :collapsed="sidebarCollapsed"
        :mobile-open="mobileSidebarOpen"
        @update:collapsed="sidebarCollapsed = $event"
        @update:mobile-open="mobileSidebarOpen = $event"
      />

      <div
        class="flex flex-col min-h-screen transition-all duration-300"
        :class="mainOffset"
      >
        <Header
          :sidebar-collapsed="sidebarCollapsed"
          @toggle-sidebar="toggleSidebar"
        />
        <main class="flex-1 px-4 md:px-6 lg:px-8 pb-8">
          <router-view v-slot="{ Component, route: viewRoute }">
            <Transition name="page" mode="out-in">
              <component :is="Component" :key="viewRoute.path" />
            </Transition>
          </router-view>
        </main>
      </div>

      <!-- Mobile overlay -->
      <Transition name="fade">
        <div
          v-if="isMobile && mobileSidebarOpen"
          class="fixed inset-0 bg-black/50 backdrop-blur-sm z-30"
          @click="mobileSidebarOpen = false"
        />
      </Transition>
    </template>

    <template v-else>
      <router-view />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useBreakpoints, breakpointsTailwind } from '@vueuse/core'
import Sidebar from './Sidebar.vue'
import Header from './Header.vue'
import NotificationArea from './NotificationArea.vue'

const route = useRoute()
const breakpoints = useBreakpoints(breakpointsTailwind)
const isMobile = breakpoints.smaller('md')

const sidebarCollapsed = ref(false)
const mobileSidebarOpen = ref(false)

const showLayout = computed(() => route.path !== '/login')

const mainOffset = computed(() => {
  if (isMobile.value) return 'ml-0'
  return sidebarCollapsed.value ? 'ml-20' : 'ml-64'
})

function toggleSidebar() {
  if (isMobile.value) {
    mobileSidebarOpen.value = !mobileSidebarOpen.value
  } else {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
}
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }
</style>
