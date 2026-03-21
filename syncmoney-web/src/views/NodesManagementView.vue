<template>
  <div class="p-6 space-y-6">
    <!-- Header -->
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-surface-900 dark:text-surface-100">
          {{ t('nav.nodesManagement') }}
        </h1>
        <p class="text-sm text-surface-500 dark:text-surface-400 mt-1">
          {{ t('nodes.managementDescription') }}
        </p>
      </div>
    </div>

    <!-- Nodes Table -->
    <Card variant="glass" padding="none">
      <div v-if="loading" class="p-6 space-y-4">
        <Skeleton v-for="i in 3" :key="i" height="60px" />
      </div>

      <!-- Custom Table with aligned columns when nodes exist -->
      <div v-else-if="nodesStore.nodes.length > 0" class="border-t border-surface-200 dark:border-surface-700">
        <!-- Table Header -->
        <div class="flex items-center px-4 py-3 bg-surface-100/50 dark:bg-surface-800/50 text-xs font-semibold text-surface-500 uppercase tracking-wider border-b border-surface-200 dark:border-surface-700">
          <div class="w-1/4">{{ t('nodes.nodeName') }}</div>
          <div class="w-1/3">{{ t('nodes.nodeUrl') }}</div>
          <div class="w-24">{{ t('nodes.status') }}</div>
          <div class="w-20">{{ t('nodes.enabled') }}</div>
          <div class="flex-1 text-right">{{ t('common.actions') }}</div>
        </div>
        <!-- Table Rows -->
        <div
          v-for="(node, index) in nodesStore.nodes"
          :key="node.url"
          class="flex items-center px-4 py-3 hover:bg-surface-50 dark:hover:bg-surface-800/30 border-b border-surface-200/50 dark:border-surface-700/50 last:border-b-0"
        >
          <div class="w-1/4 flex items-center gap-3">
            <span class="text-sm font-medium text-surface-900 dark:text-surface-100 truncate">{{ node.name }}</span>
          </div>
          <div class="w-1/3 text-xs text-surface-500 truncate">{{ node.url }}</div>
          <div class="w-24">
            <StatusDot :status="getNodeStatus(node.url)" />
          </div>
          <div class="w-20">
            <span
              :class="node.enabled ? 'text-success' : 'text-surface-400'"
              class="text-sm font-medium"
            >
              {{ node.enabled ? t('common.enabled') : t('common.disabled') }}
            </span>
          </div>
          <div class="flex-1 flex items-center justify-end gap-2">
            <Button
              size="sm"
              variant="ghost"
              :loading="pingingIndex === index"
              @click="pingNode(index)"
            >
              {{ t('nodes.ping') }}
            </Button>
            <Button
              size="sm"
              variant="danger"
              @click="confirmDelete(index)"
            >
              {{ t('common.delete') }}
            </Button>
          </div>
        </div>
      </div>

      <div v-else class="p-12">
        <EmptyState
          :icon="Server"
          :title="t('nodes.noNodes.title')"
          :description="t('nodes.noNodes.description')"
        />
      </div>
    </Card>

    <!-- Ping Results -->
    <Card v-if="pingResult !== null" variant="glass">
      <div class="flex items-center gap-3">
        <StatusDot :status="pingResult ? 'connected' : 'disconnected'" />
        <span class="text-sm font-medium">
          {{ pingResult ? t('nodes.pingSuccess') : t('nodes.pingFailed') }}
        </span>
      </div>
    </Card>

    <!-- Delete Confirmation -->
    <Teleport to="body">
      <Transition name="modal">
        <div
          v-if="deleteDialogOpen"
          class="fixed inset-0 z-50 flex items-center justify-center"
        >
          <div
            class="absolute inset-0 bg-black/50 backdrop-blur-sm"
            @click="deleteDialogOpen = false"
          />
          <div
            class="relative w-full max-w-sm mx-4 bg-surface-50 dark:bg-surface-900 rounded-2xl shadow-2xl border border-surface-200 dark:border-surface-700 p-6"
          >
            <h3 class="text-lg font-semibold text-surface-900 dark:text-surface-100 mb-2">
              {{ t('nodes.deleteConfirm.title') }}
            </h3>
            <p class="text-sm text-surface-500 dark:text-surface-400 mb-6">
              {{ t('nodes.deleteConfirm.description') }}
            </p>
            <div class="flex justify-end gap-3">
              <Button variant="ghost" @click="deleteDialogOpen = false">
                {{ t('common.cancel') }}
              </Button>
              <Button variant="danger" @click="deleteNode">
                {{ t('common.delete') }}
              </Button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Server } from 'lucide-vue-next'
import { useNodesStore } from '@/stores/nodes'
import { apiClient } from '@/api/client'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import StatusDot from '@/components/common/StatusDot.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import Skeleton from '@/components/common/Skeleton.vue'
import { useNotificationStore } from '@/stores/notification'

const { t } = useI18n()
const nodesStore = useNodesStore()
const notificationStore = useNotificationStore()

const loading = ref(false)
const deleteDialogOpen = ref(false)
const deletingIndex = ref<number | null>(null)
const pingingIndex = ref<number | null>(null)
const pingResult = ref<boolean | null>(null)

function getNodeStatus(url: string): 'connected' | 'disconnected' | 'warning' | 'loading' {
  const status = nodesStore.nodeStatuses.get(url) || 'unknown'
  switch (status) {
    case 'online': return 'connected'
    case 'offline': return 'disconnected'
    default: return 'loading'
  }
}

function confirmDelete(index: number) {
  deletingIndex.value = index
  deleteDialogOpen.value = true
}

async function deleteNode() {
  if (deletingIndex.value === null) return

  try {
    await nodesStore.deleteNode(deletingIndex.value)
    notificationStore.addNotification('success', t('nodes.deleteSuccess'), '')
  } catch {
    notificationStore.addNotification('error', t('nodes.deleteFailed'), '')
  } finally {
    deleteDialogOpen.value = false
    deletingIndex.value = null
  }
}

async function pingNode(index: number) {
  pingingIndex.value = index
  pingResult.value = null

  try {
    const node = nodesStore.nodes[index]
    if (node) {
      
      const res = await apiClient.post<{ success: boolean; data?: { status: string } }>(`/api/nodes/${index}/ping`)
      const success = res.status >= 200 && res.status < 300 && res.data?.success

      
      if (success && res.data?.data) {
        const status = res.data.data.status === 'online' ? 'online' :
                       res.data.data.status === 'offline' ? 'offline' : 'unknown'
        nodesStore.setNodeStatus(node.url, status)
        nodesStore.updateNodeStatus(index, status)
        pingResult.value = res.data.data.status === 'online'
      } else {
        nodesStore.setNodeStatus(node.url, 'offline')
        nodesStore.updateNodeStatus(index, 'offline')
        pingResult.value = false
      }

      if (pingResult.value) {
        notificationStore.addNotification('success', t('nodes.pingSuccess'), node.name)
      } else {
        notificationStore.addNotification('error', t('nodes.pingFailed'), node.name)
      }
    }
  } catch {
    pingResult.value = false
    const node = nodesStore.nodes[index]
    notificationStore.addNotification('error', t('nodes.pingFailed'), node?.name || '')
  } finally {
    pingingIndex.value = null
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await nodesStore.fetchNodes()
    nodesStore.startHealthCheck(30000)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-active > div:last-child,
.modal-leave-active > div:last-child {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from > div:last-child,
.modal-leave-to > div:last-child {
  transform: scale(0.95);
  opacity: 0;
}
</style>
