<template>
  <div class="space-y-6">
    <!-- Stats grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <StatCard
        v-for="(stat, i) in statCards"
        :key="stat.label"
        v-bind="stat"
        :loading="loading"
        :style="{ animationDelay: `${i * 100}ms` }"
        class="animate-fade-slide-up opacity-0"
      />
    </div>

    <!-- System health card -->
    <Card variant="glass" glow class="border-primary/30">
      <template #header>
        <div class="flex items-center gap-3 px-2">
          <div class="p-2 bg-primary/10 rounded-xl shadow-glow-sm">
            <Shield class="w-5 h-5 text-primary" />
          </div>
          <span class="text-base font-bold gradient-text uppercase tracking-wide">
            {{ t('dashboard.systemHealth') }}
          </span>
        </div>
      </template>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <!-- Circuit Breaker -->
        <div class="flex items-center gap-3">
          <StatusDot :status="circuitBreakerStatus" />
          <div>
            <p class="text-xs text-surface-600 dark:text-surface-400">{{ t('dashboard.circuitBreaker') }}</p>
            <p class="text-sm font-medium" :class="circuitBreakerClass">
              {{ stats.circuitBreaker }}
            </p>
          </div>
        </div>
        <!-- Redis -->
        <div class="flex items-center gap-3">
          <StatusDot :status="ws.isConnected ? 'connected' : 'disconnected'" />
          <div>
            <p class="text-xs text-surface-600 dark:text-surface-400">{{ t('dashboard.connection') }}</p>
            <p class="text-sm font-medium" :class="ws.connected ? 'text-success' : 'text-error'">
              {{ ws.isConnected ? t('dashboard.connected') : t('dashboard.disconnected') }}
            </p>
          </div>
        </div>
        <!-- Online -->
        <div class="flex items-center gap-3">
          <Users class="w-5 h-5 text-secondary" />
          <div>
            <p class="text-xs text-surface-600 dark:text-surface-400">{{ t('dashboard.onlinePlayers') }}</p>
            <p class="text-sm font-medium text-surface-900 dark:text-surface-100">{{ stats.onlinePlayers }}</p>
          </div>
        </div>
      </div>
    </Card>

    <!-- Last updated -->
    <p class="text-xs text-surface-600 dark:text-surface-400 text-right">
      {{ t('dashboard.lastUpdated') }}: {{ lastUpdated }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiClient, type DashboardStats } from '@/api/client'
import { useSSEStore } from '@/stores/sse'
import { useNotificationStore } from '@/stores/notification'
import { Wallet, Users, ArrowUpDown, TrendingUp, Shield } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusDot from '@/components/common/StatusDot.vue'

const { t, locale } = useI18n()
const ws = useSSEStore()
const notificationStore = useNotificationStore()

const loading = ref(true)
const stats = ref<DashboardStats>({
  totalSupply: '0',
  totalPlayers: 0,
  todayTransactions: 0,
  onlinePlayers: 0,
  circuitBreaker: 'NORMAL',
})
const lastUpdated = ref<string>('')


function debounce<T extends (...args: unknown[]) => unknown>(fn: T, delay: number) {
  let timeoutId: ReturnType<typeof setTimeout>
  return (...args: Parameters<T>) => {
    clearTimeout(timeoutId)
    timeoutId = setTimeout(() => fn(...args), delay)
  }
}


const debouncedLoadData = debounce(() => {
  loadDashboardData()
}, 1000)

const statCards = computed(() => [
  {
    label: t('dashboard.totalSupply'),
    value: stats.value.totalSupply,
    icon: Wallet,
    color: 'primary' as const,
  },
  {
    label: t('dashboard.totalPlayers'),
    value: stats.value.totalPlayers,
    icon: Users,
    color: 'secondary' as const,
  },
  {
    label: t('dashboard.todayTransactions'),
    value: stats.value.todayTransactions,
    icon: ArrowUpDown,
    color: 'green' as const,
  },
  {
    label: t('dashboard.onlinePlayers'),
    value: stats.value.onlinePlayers,
    icon: TrendingUp,
    color: 'amber' as const,
  },
])

const circuitBreakerClass = computed(() => {
  switch (stats.value.circuitBreaker) {
    case 'NORMAL':  return 'text-success'
    case 'WARNING': return 'text-warning'
    case 'LOCKED':  return 'text-error'
    default:        return 'text-surface-600 dark:text-surface-300'
  }
})

const circuitBreakerStatus = computed(() => {
  switch (stats.value.circuitBreaker) {
    case 'NORMAL':  return 'connected' as const
    case 'WARNING': return 'warning' as const
    default:        return 'disconnected' as const
  }
})

let refreshInterval: ReturnType<typeof setInterval>

async function loadDashboardData() {
  try {

    const [statusResponse, economyResponse] = await Promise.allSettled([
      apiClient.get('/api/system/status'),
      apiClient.get('/api/economy/stats')
    ])


    if (statusResponse.status === 'fulfilled') {
      if (statusResponse.value.data?.success && statusResponse.value.data?.data) {
        const data = statusResponse.value.data.data
        stats.value.onlinePlayers = data.onlinePlayers || 0
        if (data.circuitBreaker) {
          stats.value.circuitBreaker = data.circuitBreaker.state || 'NORMAL'
        }
      }
    } else {
      console.error('Failed to load system status:', statusResponse.reason)
    }


    if (economyResponse.status === 'fulfilled') {
      if (economyResponse.value.data?.success && economyResponse.value.data?.data) {
        const econData = economyResponse.value.data.data
        stats.value.totalSupply = econData.totalSupply != null ? formatCurrency(econData.totalSupply) : 'N/A'
        stats.value.totalPlayers = econData.totalPlayers || 0
        stats.value.todayTransactions = econData.todayTransactions || 0
      }
    } else {
      console.error('Failed to load economy stats:', economyResponse.reason)

      stats.value.totalSupply = 'N/A'
    }

    lastUpdated.value = new Date().toLocaleTimeString(locale.value)
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  } finally {
    loading.value = false
  }
}

function formatCurrency(value: number | string): string {
  if (typeof value === 'string') value = parseFloat(value)
  if (isNaN(value)) return 'N/A'
  return new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }).format(value)
}

onMounted(() => {
  loadDashboardData()
  refreshInterval = setInterval(loadDashboardData, 5000)



  ws.on('transaction', (data: unknown) => {
    const playerName = (data as { playerName?: string; player?: string }).playerName || (data as { player?: string }).player || t('dashboard.unknownPlayer')
    const amount = String((data as { amount?: number }).amount || 0)
    const type = String((data as { type?: string }).type || 'UNKNOWN')
    const amountPrefix = type === 'DEPOSIT' ? '+' : type === 'WITHDRAW' ? '-' : ''

    const displayAmount = amount.startsWith('+') || amount.startsWith('-')
      ? amount
      : `${amountPrefix}${amount}`


    notificationStore.addTransactionNotification(
      playerName,
      amount,
      type,
      `${playerName}: ${displayAmount}`
    )
    debouncedLoadData()
  })
  ws.on('circuit_break', (data: unknown) => {
    const eventData = data as { state?: string; reason?: string }
    notificationStore.addBreakerNotification(
      eventData?.state || 'WARNING',
      eventData?.reason ? `${t('notification.circuitBreakerAlert')}: ${eventData.reason}` : t('notification.circuitBreakerAlert')
    )
    debouncedLoadData()
  })


  ws.on('system', (data: unknown) => {
    const eventData = data as { type?: string; title?: string; message?: string }
    const eventType = eventData?.type || 'system_alert'

    switch (eventType) {
      case 'player_warning':
        notificationStore.addAlert('warning', eventData?.title || 'Player Warning', eventData?.message || '')
        break
      case 'player_locked':
        notificationStore.addAlert('error', eventData?.title || 'Player Locked', eventData?.message || '')
        break
      case 'player_unlocked':
        notificationStore.addAlert('success', eventData?.title || 'Player Unlocked', eventData?.message || '')
        break
      case 'rate_limit':
        notificationStore.addAlert('warning', eventData?.title || 'Rate Limit Exceeded', eventData?.message || '')
        break
      case 'global_lock':
        notificationStore.addAlert('error', eventData?.title || 'System Locked', eventData?.message || '')
        break
      case 'memory_high':
        notificationStore.addAlert('warning', eventData?.title || 'High Memory', eventData?.message || '')
        break
      case 'redis_pool_critical':
        notificationStore.addAlert('warning', eventData?.title || 'Redis Pool Critical', eventData?.message || '')
        break
      default:
        notificationStore.addAlert('info', eventData?.title || 'System Alert', eventData?.message || '')
    }
    debouncedLoadData()
  })

  ws.on('player_join', (data: unknown) => {
    const playerData = data as { playerName?: string }
    notificationStore.addNotification('info', t('notification.playerJoined'), t('notification.playerJoinedMessage', { player: playerData.playerName }))
    debouncedLoadData()
  })
  ws.on('player_quit', (data: unknown) => {
    const playerData = data as { playerName?: string }
    notificationStore.addNotification('info', t('notification.playerLeft'), t('notification.playerLeftMessage', { player: playerData.playerName }))
    debouncedLoadData()
  })
})

onUnmounted(() => {
  if (refreshInterval) clearInterval(refreshInterval)

})
</script>
