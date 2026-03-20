import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { useSSE, type SSEEventType } from '@/composables/useSSE'
import { useAuthStore } from './auth'

/**
 * [SYNC-WEB-017]
 * SSE Store - Global SSE connection management
 * 
 * Wraps useSSE Composable as a Pinia Store.
 * Implements lifecycle: connect on login, disconnect on logout.
 * Manages Server-Sent Events (unidirectional), not WebSocket (bidirectional).
 */
export const useSSEStore = defineStore('sse', () => {

  let sseComposable: ReturnType<typeof useSSE> | null = null
  let initialized = false


  const connected = ref(false)
  const error = ref<string>('')
  const connectionStatus = ref<'connecting' | 'connected' | 'disconnected' | 'error'>('disconnected')


  const isConnected = computed(() => connected.value)
  const hasError = computed(() => !!error.value)

  /**
   * [SYNC-WEB-018] Initialize SSE connection
   * Called only when not initialized and authenticated.
   */
  function init() {
    if (initialized) {
      console.log('[SSE Store] Already initialized, skipping')
      return
    }

    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) {
      console.log('[SSE Store] Not authenticated, skipping init')
      return
    }

    console.log('[SSE Store] Initializing SSE connection...')


    const sse = useSSE()
    sseComposable = sse


    watch(() => sse.connected.value, (val) => {
      connected.value = val
    })
    watch(() => sse.error.value, (val) => {
      error.value = val
    })
    watch(() => sse.connectionStatus.value, (val) => {
      connectionStatus.value = val
    })


    sse.connect('/api/sse')
    
    initialized = true
    console.log('[SSE Store] SSE connection initialized')
  }

  /**
   * [SYNC-WEB-022] Destroy SSE connection
   */
  function destroy() {
    if (!initialized || !sseComposable) {
      console.log('[SSE Store] Not initialized, skipping destroy')
      return
    }

    console.log('[SSE Store] Destroying SSE connection...')


    sseComposable.disconnect()


    connected.value = false
    error.value = ''
    connectionStatus.value = 'disconnected'

    initialized = false
    sseComposable = null
    console.log('[SSE Store] SSE connection destroyed')
  }

  /**
   * [SYNC-WEB-020] Reconnect
   */
  function reconnect() {
    console.log('[SSE Store] Reconnecting SSE...')
    destroy()

    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) {
      console.log('[SSE Store] Not authenticated, cannot reconnect')
      return
    }


    const sse = useSSE()
    sseComposable = sse


    watch(() => sse.connected.value, (val) => { connected.value = val })
    watch(() => sse.error.value, (val) => { error.value = val })
    watch(() => sse.connectionStatus.value, (val) => { connectionStatus.value = val })

    sse.connect('/api/sse')
    initialized = true
    console.log('[SSE Store] SSE reconnected')
  }

  /**
   * Listen to SSE events.
   * Note: uses unknown to avoid union type mismatch, caller must cast.
   * [SYNC-WEB-021] Auto-initialize SSE connection (if not initialized)
   */
  function on(type: SSEEventType, handler: (data: unknown) => void) {

    if (!initialized) {
      const authStore = useAuthStore()
      if (authStore.isAuthenticated) {
        console.log('[SSE Store] Auto-initializing SSE due to handler registration')
        init()
      } else {
        console.warn('[SSE Store] Not authenticated, cannot auto-initialize SSE')
        return
      }
    }

    if (!sseComposable) {
      console.warn('[SSE Store] SSE not initialized, cannot register handler')
      return
    }
    sseComposable.on(type, handler)
  }

  /**
   * [SYNC-WEB-023] Remove SSE event listener.
   * Note: uses unknown to avoid union type mismatch, caller must cast.
   */
  function off(type: SSEEventType, handler: (data: unknown) => void) {
    if (!sseComposable) {
      return
    }
    sseComposable.off(type, handler)
  }

  /**
   * [SYNC-WEB-024] Get the last received message.
   */
  function getLastMessage() {
    return sseComposable?.lastMessage.value ?? null
  }

  return {

    connected,
    error,
    connectionStatus,


    isConnected,
    hasError,


    init,
    destroy,
    reconnect,
    on,
    off,
    getLastMessage
  }
})
