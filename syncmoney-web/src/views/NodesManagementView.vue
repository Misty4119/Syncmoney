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
      <Button @click="openAddDialog">
        {{ t('nodes.addNode') }}
      </Button>
    </div>

    <!-- Nodes Table -->
    <Card variant="glass" padding="none">
      <div v-if="loading" class="p-6 space-y-4">
        <Skeleton v-for="i in 3" :key="i" height="60px" />
      </div>

      <Table
        v-else-if="nodesStore.nodes.length > 0"
        :columns="columns"
        :data="tableData"
        :loading="loading"
      >
        <template #cell-status="{ row }">
          <StatusDot :status="getNodeStatus(String(row.url))" />
        </template>
        <template #cell-enabled="{ row }">
          <span
            :class="row.enabled ? 'text-success' : 'text-surface-400'"
            class="text-sm font-medium"
          >
            {{ row.enabled ? t('common.enabled') : t('common.disabled') }}
          </span>
        </template>
      </Table>

      <!-- Actions row outside Table for index tracking -->
      <div v-if="!loading && nodesStore.nodes.length > 0" class="border-t border-surface-200 dark:border-surface-700">
        <div
          v-for="(node, index) in nodesStore.nodes"
          :key="node.url"
          class="flex items-center justify-between px-4 py-3 hover:bg-surface-100 dark:hover:bg-surface-800/50 transition-colors border-b border-surface-200 dark:border-surface-700 last:border-b-0"
        >
          <div class="flex items-center gap-4">
            <StatusDot :status="getNodeStatus(node.url)" />
            <span class="text-sm font-medium text-surface-900 dark:text-surface-100">{{ node.name }}</span>
            <span class="text-xs text-surface-500 truncate max-w-[200px]">{{ node.url }}</span>
          </div>
          <div class="flex items-center gap-2">
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
              variant="ghost"
              @click="openEditDialog(index)"
            >
              {{ t('common.edit') }}
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
        >
          <template #action>
            <Button @click="openAddDialog">
              {{ t('nodes.addFirstNode') }}
            </Button>
          </template>
        </EmptyState>
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

    <!-- Add/Edit Dialog -->
    <Teleport to="body">
      <Transition name="modal">
        <div
          v-if="dialogOpen"
          class="fixed inset-0 z-50 flex items-center justify-center"
        >
          <!-- Backdrop -->
          <div
            class="absolute inset-0 bg-black/50 backdrop-blur-sm"
            @click="closeDialog"
          />

          <!-- Dialog Content -->
          <div
            class="relative w-full max-w-md mx-4 bg-surface-50 dark:bg-surface-900 rounded-2xl shadow-2xl border border-surface-200 dark:border-surface-700 overflow-hidden"
          >
            <!-- Header -->
            <div class="flex items-center justify-between px-6 py-4 border-b border-surface-200 dark:border-surface-700">
              <h2 class="text-lg font-semibold text-surface-900 dark:text-surface-100">
                {{ editingIndex === null ? t('nodes.addNode') : t('nodes.editNode') }}
              </h2>
              <button
                class="p-2 rounded-lg hover:bg-surface-200 dark:hover:bg-surface-800 text-surface-500 transition-colors"
                @click="closeDialog"
              >
                <X class="w-5 h-5" />
              </button>
            </div>

            <!-- Form -->
            <form @submit.prevent="saveNode" class="p-6 space-y-4">
              <Input
                v-model="formData.name"
                :label="t('nodes.nodeName')"
                :placeholder="t('nodes.nodeNamePlaceholder')"
                :error="errors.name"
                required
              />

              <Input
                v-model="formData.url"
                type="url"
                :label="t('nodes.nodeUrl')"
                :placeholder="t('nodes.nodeUrlPlaceholder')"
                :error="errors.url"
                required
              />

              <Input
                v-model="formData.apiKey"
                type="password"
                :label="t('nodes.apiKey')"
                :placeholder="t('nodes.apiKeyPlaceholder')"
                :error="errors.apiKey"
                required
              />

              <div class="flex items-center gap-3">
                <input
                  id="node-enabled"
                  v-model="formData.enabled"
                  type="checkbox"
                  class="w-4 h-4 rounded border-surface-300 text-primary focus:ring-primary"
                />
                <label for="node-enabled" class="text-sm text-surface-700 dark:text-surface-300">
                  {{ t('nodes.enabled') }}
                </label>
              </div>

              <!-- Actions -->
              <div class="flex justify-end gap-3 pt-4">
                <Button variant="ghost" type="button" @click="closeDialog">
                  {{ t('common.cancel') }}
                </Button>
                <Button type="submit" :loading="saving">
                  {{ t('common.save') }}
                </Button>
              </div>
            </form>
          </div>
        </div>
      </Transition>
    </Teleport>

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
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Server, X } from 'lucide-vue-next'
import { useNodesStore, type NodeInfo } from '@/stores/nodes'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Table from '@/components/common/Table.vue'
import StatusDot from '@/components/common/StatusDot.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import Input from '@/components/common/Input.vue'
import Skeleton from '@/components/common/Skeleton.vue'
import { useNotificationStore } from '@/stores/notification'

const { t } = useI18n()
const nodesStore = useNodesStore()
const notificationStore = useNotificationStore()

const loading = ref(false)
const dialogOpen = ref(false)
const deleteDialogOpen = ref(false)
const editingIndex = ref<number | null>(null)
const deletingIndex = ref<number | null>(null)
const pingingIndex = ref<number | null>(null)
const pingResult = ref<boolean | null>(null)
const saving = ref(false)

const formData = reactive({
  name: '',
  url: '',
  apiKey: '',
  enabled: true,
})

const errors = reactive({
  name: '',
  url: '',
  apiKey: '',
})

const columns = [
  { key: 'name', label: 'nodes.nodeName', sortable: true },
  { key: 'url', label: 'nodes.nodeUrl' },
  { key: 'status', label: 'system.status' },
  { key: 'enabled', label: 'common.status' },
  { key: 'actions', label: 'common.actions', class: 'text-right' },
]

const tableData = computed(() =>
  nodesStore.nodes.map((node) => ({
    ...node,
    status: nodesStore.nodeStatuses.get(node.url) || 'unknown',
  }))
)

function getNodeStatus(url: string): 'connected' | 'disconnected' | 'warning' | 'loading' {
  const status = nodesStore.nodeStatuses.get(url) || 'unknown'
  switch (status) {
    case 'online': return 'connected'
    case 'offline': return 'disconnected'
    default: return 'loading'
  }
}

function resetForm() {
  formData.name = ''
  formData.url = ''
  formData.apiKey = ''
  formData.enabled = true
  errors.name = ''
  errors.url = ''
  errors.apiKey = ''
}

function openAddDialog() {
  resetForm()
  editingIndex.value = null
  dialogOpen.value = true
}

function openEditDialog(index: number) {
  const node = nodesStore.nodes[index]
  if (node) {
    formData.name = node.name
    formData.url = node.url
    formData.apiKey = node.apiKey
    formData.enabled = node.enabled
    editingIndex.value = index
    dialogOpen.value = true
  }
}

function closeDialog() {
  dialogOpen.value = false
  editingIndex.value = null
}

function validateForm(): boolean {
  let valid = true
  errors.name = ''
  errors.url = ''
  errors.apiKey = ''

  if (!formData.name.trim()) {
    errors.name = t('nodes.errors.nameRequired')
    valid = false
  }

  if (!formData.url.trim()) {
    errors.url = t('nodes.errors.urlRequired')
    valid = false
  } else {
    try {
      new URL(formData.url)
    } catch {
      errors.url = t('nodes.errors.urlInvalid')
      valid = false
    }
  }

  if (!formData.apiKey.trim()) {
    errors.apiKey = t('nodes.errors.apiKeyRequired')
    valid = false
  }

  return valid
}

async function saveNode() {
  if (!validateForm()) return

  saving.value = true
  try {
    const nodeData: NodeInfo = {
      name: formData.name.trim(),
      url: formData.url.trim(),
      apiKey: formData.apiKey.trim(),
      enabled: formData.enabled,
    }

    if (editingIndex.value !== null) {
      await nodesStore.updateNode(editingIndex.value, nodeData)
      notificationStore.addNotification('success', t('nodes.updateSuccess'), '')
    } else {
      await nodesStore.addNode(nodeData)
      notificationStore.addNotification('success', t('nodes.addSuccess'), '')
    }

    closeDialog()
  } catch (error) {
    if (error instanceof Error && error.message === 'SSRF_BLOCKED') {
      errors.url = t('nodes.errors.ssrfBlocked')
    } else {
      notificationStore.addNotification('error', t('nodes.saveFailed'), '')
    }
  } finally {
    saving.value = false
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
      const status = await nodesStore.checkNodeHealth(node)
      pingResult.value = status === 'online'
    }
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
