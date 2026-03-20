<template>
  <!-- [FE-M11 FIX] SSE Connection Status Indicator -->
  <div v-if="!isConnected && showWarning" class="connection-warning">
    <span class="warning-icon">⚠</span>
    <span>{{ t('app.disconnected') }}</span>
    <button @click="reconnect" class="reconnect-btn">{{ t('app.reconnect') }}</button>
  </div>

  <Layout>
    <router-view />
  </Layout>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useSSEStore } from '@/stores/sse'
import Layout from '@/components/layout/Layout.vue'

const { t } = useI18n()
const authStore = useAuthStore()
const sseStore = useSSEStore()

const showWarning = ref(false)

const isConnected = computed(() => sseStore.connected)

function reconnect() {
  sseStore.init()
}


watch(() => authStore.isAuthenticated, (isAuthed) => {
  if (isAuthed) {
    console.log('[App] Authenticated, initializing SSE...')
    sseStore.init()
  } else {
    console.log('[App] Logged out, destroying SSE...')
    sseStore.destroy()
  }
})

onMounted(() => {



  authStore.checkAuth()


  const checkAndInitSSE = () => {
    if (authStore.isAuthenticated && !sseStore.connected) {
      console.log('[App] Post-auth-check: initializing SSE...')
      sseStore.init()
    }
  }


  setTimeout(checkAndInitSSE, 100)


  setTimeout(() => {
    if (!sseStore.connected) {
      showWarning.value = true
    }
  }, 5000)
})
</script>

<style scoped>
.connection-warning {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: #ef4444;
  color: white;
  padding: 8px;
  z-index: 9999;
}

:global(.light) .connection-warning {
  background: #dc2626;
}

.warning-icon {
  font-size: 16px;
}

.reconnect-btn {
  margin-left: 8px;
  padding: 4px 12px;
  background: white;
  color: #ef4444;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

:global(.light) .reconnect-btn {
  background: #f3f4f6;
  color: #dc2626;
}

.reconnect-btn:hover {
  background: #f5f5f5;
}

:global(.light) .reconnect-btn:hover {
  background: #e5e7eb;
}
</style>
