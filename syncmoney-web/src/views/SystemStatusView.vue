<template>
  <div class="space-y-6">
    <!-- Plugin & Connectivity -->
    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <!-- Plugin Info -->
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <div class="flex items-center gap-3 px-2">
            <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
              <Package class="w-5 h-5 text-cyan-400" />
            </div>
            <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.plugin') }}</span>
          </div>
        </template>
        <div class="space-y-3">
          <div class="flex justify-between text-sm">
            <span class="text-surface-600 dark:text-surface-400">{{ t('system.version') }}</span>
            <span class="font-mono text-surface-700 dark:text-surface-200">{{ status.plugin?.version || t('system.version-fallback') }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-surface-600 dark:text-surface-400">{{ t('system.dbVersion') }}</span>
            <span class="font-mono text-surface-700 dark:text-surface-200">{{ status.plugin?.dbVersion || t('system.version-fallback') }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-surface-600 dark:text-surface-400">{{ t('system.mode') }}</span>
            <Badge :variant="status.plugin?.mode === 'REDIS' ? 'info' : 'default'">{{ status.plugin?.mode || t('system.modeLocal') }}</Badge>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-surface-600 dark:text-surface-400">{{ t('system.uptime') }}</span>
            <span class="font-mono text-surface-700 dark:text-surface-200">{{ formatUptime(status.plugin?.uptime) }}</span>
          </div>
        </div>
      </Card>

      <!-- Connectivity -->
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <div class="flex items-center gap-3 px-2">
            <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
              <Wifi class="w-5 h-5 text-cyan-400" />
            </div>
            <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.connectivity') }}</span>
          </div>
        </template>
        <div class="space-y-4">
          <div class="flex items-center justify-between text-sm">
            <span class="text-surface-400">{{ t('system.redis') }}</span>
            <StatusDot :status="status.redis?.connected ? 'connected' : 'disconnected'" :label="status.redis?.connected ? t('system.status.connected') : t('system.status.disconnected')" />
          </div>
          <div class="flex items-center justify-between text-sm">
            <span class="text-surface-400">{{ t('system.database') }}</span>
            <div class="flex items-center gap-2">
              <StatusDot :status="status.database?.connected ? 'connected' : 'disconnected'" :label="status.database?.connected ? t('system.status.connected') : t('system.status.disconnected')" />
              <Badge v-if="status.database?.type" variant="default" size="sm">{{ status.database.type }}</Badge>
            </div>
          </div>
          <div class="flex items-center justify-between text-sm">
            <span class="text-surface-400">{{ t('system.sseStream') }}</span>
            <StatusDot :status="ws.isConnected ? 'connected' : 'disconnected'" :label="ws.isConnected ? t('system.status.connected') : t('system.status.disconnected')" />
          </div>
        </div>
      </Card>
    </div>

    <!-- Circuit Breaker -->
    <Card variant="glass" glow hoverable class="group hover:border-cyan-500/50 hover:shadow-glow-lg transition-all duration-300">
      <template #header>
        <div class="flex items-center gap-3 px-2">
          <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
            <Shield class="w-5 h-5 text-cyan-400" />
          </div>
          <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.circuitBreaker') }}</span>
        </div>
      </template>
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-3">
          <StatusDot :status="circuitBreakerDotStatus" />
          <div>
            <p class="font-semibold" :class="circuitBreakerClass">
              {{ t(`system.status.${status.circuitBreaker?.state?.toLowerCase() || 'normal'}`) }}
            </p>
            <p v-if="status.circuitBreaker?.failCount" class="text-xs text-surface-500 mt-0.5">
              Failures: {{ status.circuitBreaker.failCount }}
            </p>
          </div>
        </div>
        <Badge :variant="circuitBreakerBadgeVariant">
          {{ status.circuitBreaker?.state || 'NORMAL' }}
        </Badge>
      </div>
    </Card>

    <!-- Resources -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <!-- Memory -->
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <div class="flex items-center gap-3 px-2">
            <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
              <HardDrive class="w-5 h-5 text-cyan-400 relative z-10" />
            </div>
            <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.memory') }}</span>
          </div>
        </template>
        <p class="text-2xl font-bold font-mono text-surface-900 dark:text-white mb-3 group-hover:text-cyan-400 transition-colors">
          {{ formatBytes(metrics.memory?.used) }} <span class="text-surface-400 dark:text-surface-500">/</span> {{ formatBytes(metrics.memory?.total) }}
        </p>
        <div class="w-full bg-surface-950/50 rounded-full h-2 border border-surface-700/50 overflow-hidden shadow-inner relative">
          <div
            class="h-full rounded-full transition-all duration-1000 ease-out relative"
            :class="memoryUsagePercent > 80 ? 'bg-error shadow-glow-error' : memoryUsagePercent > 60 ? 'bg-warning' : 'bg-cyan-500 shadow-glow-cyan'"
            :style="{ width: memoryUsagePercent + '%' }"
          >
            <div class="absolute inset-0 bg-white/20 animate-pulse"></div>
          </div>
        </div>
        <p class="text-xs text-surface-500 dark:text-surface-400 mt-2 font-mono">{{ memoryUsagePercent }}% {{ t('system.memoryUsage') }}</p>
      </Card>

      <!-- Threads -->
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <div class="flex items-center gap-3 px-2">
            <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
              <Cpu class="w-5 h-5 text-cyan-400 group-hover:animate-pulse" />
            </div>
            <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.threads') }}</span>
          </div>
        </template>
        <p class="text-4xl font-bold font-mono text-surface-900 dark:text-white mt-2 group-hover:text-cyan-400 transition-colors">{{ metrics.threads || 0 }}</p>
      </Card>

      <!-- TPS -->
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <div class="flex items-center gap-3 px-2">
            <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
              <Zap class="w-5 h-5 text-cyan-400 group-hover:animate-pulse" />
            </div>
            <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ t('system.tps') }}</span>
          </div>
        </template>
        <p class="text-3xl font-bold font-mono" :class="tpsClass">{{ metrics.tps?.toFixed(1) || '20.0' }}</p>
        <Badge :variant="tpsBadgeVariant" class="mt-2">
          {{ metrics.tps >= 18 ? t('system.tpsHealthy') : metrics.tps >= 10 ? t('system.tpsDegraded') : t('system.tpsCritical') }}
        </Badge>
      </Card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { apiClient, type SystemStatus, type SystemMetrics, type ApiResponse } from '@/api/client'
import type { SystemAlertEvent } from '@/types/events'
import { useSSEStore } from '@/stores/sse'
import { useNotificationStore } from '@/stores/notification'
import { Package, Wifi, Shield, HardDrive, Cpu, Zap } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Badge from '@/components/common/Badge.vue'
import StatusDot from '@/components/common/StatusDot.vue'

const { t } = useI18n()
const ws = useSSEStore()
const notificationStore = useNotificationStore()

let refreshInterval: ReturnType<typeof setInterval> | null = null

const status = ref<Partial<SystemStatus>>({})
const metrics = ref<SystemMetrics>({
  memory: { used: 0, total: 0, free: 0, max: 0, percentage: 0 },
  threads: 0,
  tps: 20.0,
})

const circuitBreakerClass = computed(() => {
  switch (status.value.circuitBreaker?.state) {
    case 'NORMAL':  return 'text-success'
    case 'WARNING': return 'text-warning'
    case 'LOCKED':  return 'text-error'
    default:        return 'text-surface-300'
  }
})

const circuitBreakerDotStatus = computed(() => {
  switch (status.value.circuitBreaker?.state) {
    case 'NORMAL':  return 'connected' as const
    case 'WARNING': return 'warning' as const
    default:        return 'disconnected' as const
  }
})

const circuitBreakerBadgeVariant = computed(() => {
  switch (status.value.circuitBreaker?.state) {
    case 'NORMAL':  return 'success' as const
    case 'WARNING': return 'warning' as const
    default:        return 'error' as const
  }
})

const memoryUsagePercent = computed(() => {
  if (!metrics.value.memory?.total || !metrics.value.memory?.used) return 0
  return Math.round((metrics.value.memory.used / metrics.value.memory.total) * 100)
})

const tpsClass = computed(() => {
  const tps = metrics.value.tps || 20
  if (tps >= 18) return 'text-success'
  if (tps >= 10) return 'text-warning'
  return 'text-error'
})

const tpsBadgeVariant = computed(() => {
  const tps = metrics.value.tps || 20
  if (tps >= 18) return 'success' as const
  if (tps >= 10) return 'warning' as const
  return 'error' as const
})

function formatUptime(ms?: number): string {
  if (!ms) return '-'
  const s = Math.floor(ms / 1000)
  const m = Math.floor(s / 60)
  const h = Math.floor(m / 60)
  const d = Math.floor(h / 24)
  if (d > 0) return `${d}d ${h % 24}h`
  if (h > 0) return `${h}h ${m % 60}m`
  if (m > 0) return `${m}m`
  return `${s}s`
}

function formatBytes(bytes?: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = bytes
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${units[i]}`
}

async function loadSystemStatus() {
  try {
    const response = await apiClient.get<ApiResponse<SystemStatus>>('/api/system/status')
    if (response.data?.success && response.data?.data) {
      status.value = response.data.data
    }
    try {
      const metricsResponse = await apiClient.get<ApiResponse<SystemMetrics>>('/api/system/metrics')
      if (metricsResponse.data?.success && metricsResponse.data?.data) {
        metrics.value = metricsResponse.data.data
      }
    } catch {

    }
  } catch (error) {
    console.error('Failed to load system status:', error)
  }
}

onMounted(() => {
  loadSystemStatus()

  refreshInterval = setInterval(loadSystemStatus, 10000)
  

  ws.on('circuit_break', () => {
    notificationStore.addBreakerNotification('WARNING', t('notification.circuitBreakerAlert'))
    loadSystemStatus()
  })
  ws.on('system_alert', (data: unknown) => {
    const evt = data as SystemAlertEvent
    notificationStore.addSystemAlertNotification(evt.message || t('notification.systemError'))
    loadSystemStatus()
  })
})

onUnmounted(() => { 
  if (refreshInterval) clearInterval(refreshInterval)

})
</script>
