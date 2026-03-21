<template>
  <div class="p-6 space-y-6">
    <!-- Error Banner -->
    <div v-if="hasError" class="bg-error/10 border border-error/20 rounded-lg p-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <span class="text-error">{{ errorMessage }}</span>
      </div>
      <button
        class="text-sm text-error hover:text-error/80 transition-colors"
        @click="dismissError"
      >
        Dismiss
      </button>
    </div>

    <!-- Aggregate Stats -->
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

    <!-- Node Cards -->
    <div>
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-surface-900 dark:text-surface-100">
          {{ t('central.nodes') }}
        </h2>
        <button
          class="text-sm text-primary hover:text-primary/80 transition-colors"
          @click="refreshNodes"
        >
          {{ t('common.refresh') }}
        </button>
      </div>

      <div v-if="nodesStore.nodes.length === 0" class="col-span-full">
        <EmptyState
          :icon="Server"
          :title="t('central.noNodes.title')"
          :description="t('central.noNodes.description')"
        />
      </div>

      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <Card
          v-for="node in nodesStore.nodes"
          :key="node.url"
          variant="glass"
          hoverable
          class="group transition-all duration-300 hover:border-primary/40"
        >
          <div class="flex items-start justify-between">
            <div class="flex items-center gap-3">
              <div
                class="w-10 h-10 rounded-xl flex items-center justify-center"
                :class="getNodeStatusClass(node)"
              >
                <Server class="w-5 h-5" />
              </div>
              <div>
                <h3 class="font-medium text-surface-900 dark:text-surface-100">
                  {{ node.name }}
                </h3>
                <p class="text-xs text-surface-500 dark:text-surface-400 truncate max-w-[180px]">
                  {{ node.url }}
                </p>
              </div>
            </div>
            <StatusDot :status="getStatusForNode(node)" />
          </div>

          <div class="mt-4 grid grid-cols-2 gap-3">
            <div class="flex items-center gap-2 text-sm">
              <Users class="w-4 h-4 text-surface-400" />
              <span class="text-surface-600 dark:text-surface-400">
                {{ nodeStatuses[node.url]?.onlinePlayers ?? '-' }}
                <span class="text-surface-400">/</span>
                {{ nodeStatuses[node.url]?.maxPlayers ?? '-' }}
              </span>
            </div>
            <div class="flex items-center gap-2 text-sm">
              <Activity class="w-4 h-4 text-surface-400" />
              <span class="text-surface-600 dark:text-surface-400">
                {{ nodeStatuses[node.url]?.economyMode ?? '-' }}
              </span>
            </div>
          </div>

          <div v-if="nodeStatuses[node.url]?.serverName" class="mt-3 pt-3 border-t border-surface-200 dark:border-surface-700">
            <p class="text-xs text-surface-500 dark:text-surface-400">
              {{ t('central.server') }}: {{ nodeStatuses[node.url]?.serverName }}
            </p>
          </div>
        </Card>
      </div>
    </div>

    <!-- Last updated -->
    <p class="text-xs text-surface-600 dark:text-surface-400 text-right">
      {{ t('dashboard.lastUpdated') }}: {{ lastUpdated }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Wallet, Users, ArrowUpDown, TrendingUp, Server, Activity } from 'lucide-vue-next'
import { useNodesStore, type NodeInfo } from '@/stores/nodes'
import { fetchCrossServerStats, fetchAllNodesStatus } from '@/services/systemService'
import { useSSE } from '@/composables/useSSE'
import type { AggregatedStats, NodeStatus } from '@/api/types'
import Card from '@/components/common/Card.vue'
import StatCard from '@/components/common/StatCard.vue'
import StatusDot from '@/components/common/StatusDot.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const { t, locale } = useI18n()
const nodesStore = useNodesStore()
const sse = useSSE()

const loading = ref(true)
const crossServerStats = ref<AggregatedStats | null>(null)
const nodeStatuses = ref<Record<string, NodeStatus>>({})
const lastUpdated = ref<string>('')
const errorMessage = ref<string>('')
const hasError = ref(false)

const statCards = computed(() => [
  {
    label: t('dashboard.totalSupply'),
    value: crossServerStats.value?.totalSupply ?? '-',
    icon: Wallet,
    color: 'primary' as const,
  },
  {
    label: t('dashboard.totalPlayers'),
    value: crossServerStats.value?.totalPlayers ?? '-',
    icon: Users,
    color: 'secondary' as const,
  },
  {
    label: t('dashboard.onlinePlayers'),
    value: crossServerStats.value?.totalOnlinePlayers ?? '-',
    icon: TrendingUp,
    color: 'green' as const,
  },
  {
    label: t('dashboard.todayTransactions'),
    value: crossServerStats.value?.todayTransactions ?? '-',
    icon: ArrowUpDown,
    color: 'amber' as const,
  },
])

function getStatusForNode(node: NodeInfo): 'connected' | 'disconnected' | 'warning' | 'loading' {
  const status = nodesStore.nodeStatuses.get(node.url) || 'unknown'
  switch (status) {
    case 'online': return 'connected'
    case 'offline': return 'disconnected'
    default: return 'loading'
  }
}

function getNodeStatusClass(node: NodeInfo): string {
  const status = nodesStore.nodeStatuses.get(node.url) || 'unknown'
  switch (status) {
    case 'online': return 'bg-success/10 text-success'
    case 'offline': return 'bg-error/10 text-error'
    default: return 'bg-surface-200 dark:bg-surface-700 text-surface-400'
  }
}

async function refreshNodes() {
  loading.value = true
  hasError.value = false
  errorMessage.value = ''
  try {
    await Promise.all([
      loadCrossServerStats(),
      loadNodeStatuses(),
    ])
    lastUpdated.value = new Date().toLocaleTimeString(locale.value)
  } finally {
    loading.value = false
  }
}

async function loadCrossServerStats() {
  try {
    const stats = await fetchCrossServerStats()
    if (stats) {
      crossServerStats.value = stats
    }
  } catch (error) {
    console.error('Failed to load cross-server stats:', error)
    hasError.value = true
    errorMessage.value = t('central.statsError') || 'Failed to load cross-server statistics'
  }
}

async function loadNodeStatuses() {
  try {
    const statuses = await fetchAllNodesStatus()
    nodeStatuses.value = statuses
  } catch (error) {
    console.error('Failed to load node statuses:', error)
    hasError.value = true
    errorMessage.value = t('central.nodesError') || 'Failed to load node statuses'
  }
}

function dismissError() {
  hasError.value = false
  errorMessage.value = ''
}


function handlePlayerJoin(_data: unknown) {
  console.log('[CentralDashboard] Player join event received')
  
  loadNodeStatuses()
  
  loadCrossServerStats()
}


function handlePlayerQuit(_data: unknown) {
  console.log('[CentralDashboard] Player quit event received')
  
  loadNodeStatuses()
  
  loadCrossServerStats()
}

let refreshInterval: ReturnType<typeof setInterval>

onMounted(async () => {
  
  sse.connect()

  
  sse.on('player_join', handlePlayerJoin)
  sse.on('player_quit', handlePlayerQuit)

  
  
  await nodesStore.fetchNodes().catch(() => {})

  await refreshNodes()

  
  refreshInterval = setInterval(refreshNodes, 30000)

  
  nodesStore.startHealthCheck(30000)
})

onUnmounted(() => {
  
  sse.off('player_join', handlePlayerJoin)
  sse.off('player_quit', handlePlayerQuit)

  if (refreshInterval) {
    clearInterval(refreshInterval)
  }
  nodesStore.stopHealthCheck()
})
</script>
