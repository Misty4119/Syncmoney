<template>
  <div class="space-y-6">
    <!-- Config sections -->
    <div
      v-for="(section, index) in configSections"
      :key="section.key"
      class="animate-fade-slide-up opacity-0"
      :style="{ animationDelay: `${index * 80}ms` }"
    >
      <Card variant="glass" hoverable class="group hover:border-cyan-500/40 hover:shadow-glow-cyan transition-all duration-300">
        <template #header>
          <button class="flex items-center justify-between w-full px-2 py-1 outline-none" @click="toggleSection(section.key)">
            <div class="flex items-center gap-3">
              <div class="p-2 bg-cyan-500/10 rounded-lg group-hover:bg-cyan-500/20 transition-colors">
                <component :is="section.icon" class="w-5 h-5 text-cyan-400" />
              </div>
              <span class="text-base font-bold text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-lavender-400 uppercase tracking-wide">{{ getSectionTitle(section.key) }}</span>
            </div>
            <div class="flex items-center gap-4">
              <Badge v-if="hasChanges(section.key)" variant="warning" size="sm" class="shadow-sm">
                {{ t('config.unsavedChanges') }}
              </Badge>
              <Badge v-else-if="section.status" :variant="section.status === 'enabled' ? 'success' : 'error'" size="sm" class="shadow-sm">
                {{ section.status === 'enabled' ? t('config.enabled') : t('config.disabled') }}
              </Badge>
              <ChevronDown
                class="w-5 h-5 text-surface-500 group-hover:text-cyan-400 transition-all duration-300"
                :class="{ 'rotate-180': expanded[section.key] }"
              />
            </div>
          </button>
        </template>

        <Transition name="collapse">
          <div v-if="expanded[section.key]" class="space-y-4">
            <!-- Config fields -->
            <div
              v-for="field in section.fields"
              :key="field.key"
              class="flex justify-between items-center py-2 border-b border-surface-700/50 last:border-0"
            >
              <div class="flex-1 mr-4">
                <div class="text-sm text-surface-700 dark:text-surface-300">{{ getFieldLabel(section.key, field.key, field.fullKey) }}</div>
                <div v-if="getFieldDescription(section.key, field.key, field.fullKey)" class="text-xs text-surface-500">
                  {{ getFieldDescription(section.key, field.key, field.fullKey) }}
                </div>
              </div>
              <div class="w-64">
                <!-- Boolean field -->
                <Switch
                  v-if="field.type === 'boolean'"
                  :model-value="Boolean(getFieldValue(section.key, field.key, field.fullKey))"
                  :disabled="!field.editable"
                  @update:model-value="updateField(section.key, field.key, $event, field.fullKey)"
                />

                <!-- Select field (with allowed values) -->
                <Select
                  v-else-if="field.allowedValues && field.allowedValues.length > 0"
                  :model-value="String(getFieldValue(section.key, field.key, field.fullKey) ?? '')"
                  :options="getSelectOptions(field.allowedValues)"
                  :disabled="!field.editable"
                  @update:model-value="updateField(section.key, field.key, $event, field.fullKey)"
                />

                <!-- Number field -->
                <Input
                  v-else-if="field.type === 'number'"
                  type="number"
                  :model-value="Number(getFieldValue(section.key, field.key, field.fullKey))"
                  :disabled="!field.editable"
                  :min="field.min"
                  :max="field.max"
                  @update:model-value="updateField(section.key, field.key, Number($event), field.fullKey)"
                />

                <!-- String field -->
                <Input
                  v-else
                  type="text"
                  :model-value="String(getFieldValue(section.key, field.key, field.fullKey) ?? '')"
                  :disabled="!field.editable"
                  @update:model-value="updateField(section.key, field.key, $event, field.fullKey)"
                />
              </div>
            </div>
          </div>
        </Transition>

        <template v-if="hasChanges(section.key)" #footer>
          <div class="flex justify-end gap-2">
            <Button variant="ghost" @click="resetSection(section.key)">
              {{ t('common.cancel') }}
            </Button>
            <Button variant="primary" :loading="saving" @click="saveSection(section.key)">
              {{ t('common.save') }}
            </Button>
            <Button
              v-if="canSyncToNodes"
              variant="outline"
              :icon="Globe"
              :loading="syncing"
              class="border-cyan-500/50 text-cyan-400 hover:bg-cyan-500/10"
              @click="syncToNodes(section.key)"
            >
              {{ t('config.syncToNodes') }}
            </Button>
          </div>
        </template>
      </Card>
    </div>

    <!-- Global Reload -->
    <div class="flex justify-end pt-4">
      <Button variant="outline" :icon="RefreshCw" :loading="reloading" class="hover:bg-cyan-500/10 hover:text-cyan-400 hover:border-cyan-500/50 transition-all duration-300 shadow-glow-sm" @click="reloadConfig">
        {{ t('config.reload') }}
      </Button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { configService, groupConfigBySection, type PluginConfig, type ConfigField } from '@/services/configService'
import { useNotificationStore } from '@/stores/notification'
import { useNodesStore } from '@/stores/nodes'
import { Server, Shield, FileText, Coins, ChevronDown, RefreshCw, Settings, Bell, Users, MessageCircle, Clock, Globe } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Badge from '@/components/common/Badge.vue'
import Button from '@/components/common/Button.vue'
import Input from '@/components/common/Input.vue'
import Switch from '@/components/common/Switch.vue'
import Select from '@/components/common/Select.vue'

const { t } = useI18n()
const notificationStore = useNotificationStore()
const nodesStore = useNodesStore()

const config = ref<PluginConfig | null>(null)
const originalConfig = ref<PluginConfig | null>(null)
const changes = reactive<Record<string, Record<string, unknown>>>({})
const reloading = ref(false)
const saving = ref(false)
const syncing = ref(false)

const sectionIcons: Record<string, typeof Server> = {
  core: Settings,
  economy: Coins,
  display: Coins,
  pay: Coins,
  baltop: Coins,
  'circuit-breaker': Shield,
  'discord-webhook': Bell,
  audit: FileText,
  'admin-permissions': Users,
  messages: MessageCircle,
  'cross-server-notifications': Bell,
  'shadow-sync': Clock,
  'web-admin.ui': Settings
}

const expanded = ref<Record<string, boolean>>({
  core: false,
  economy: false,
  display: false,
  pay: false,
  baltop: false,
  'circuit-breaker': false,
  'discord-webhook': false,
  audit: false,
  'admin-permissions': false,
  messages: false,
  'cross-server-notifications': false,
  'shadow-sync': false,
})

function toggleSection(key: string) {
  expanded.value[key] = !expanded.value[key]
}



function toFullCamelCase(key: string): string {
  return key.replace(/\./g, '-').replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase())
}

function getSectionTitle(key: string): string {

  const titleKey = `config.sections.${key}.title`
  let translated = t(titleKey)
  if (translated !== titleKey) return translated


  const camelKey = key.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase())
  const camelTitleKey = `config.sections.${camelKey}.title`
  translated = t(camelTitleKey)
  if (translated !== camelTitleKey) return translated


  const fullCamelKey = toFullCamelCase(key)
  if (fullCamelKey !== camelKey) {
    const fullCamelTitleKey = `config.sections.${fullCamelKey}.title`
    translated = t(fullCamelTitleKey)
    if (translated !== fullCamelTitleKey) return translated
  }

  return key
}

function getFieldLabel(sectionKey: string, fieldKey: string, fullKey?: string): string {
  const camelSection = sectionKey.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase())
  const fullCamelSection = toFullCamelCase(sectionKey)
  const hasDistinctFullCamel = fullCamelSection !== camelSection

  if (fullKey && fullKey !== fieldKey) {

    const sectionNestedKey = `config.fields.${sectionKey}.${fullKey}.label`
    let translated = t(sectionNestedKey)
    if (translated !== sectionNestedKey) return translated


    const camelNestedKey = `config.fields.${camelSection}.${fullKey}.label`
    translated = t(camelNestedKey)
    if (translated !== camelNestedKey) return translated


    if (hasDistinctFullCamel) {
      const fullCamelNestedKey = `config.fields.${fullCamelSection}.${fullKey}.label`
      translated = t(fullCamelNestedKey)
      if (translated !== fullCamelNestedKey) return translated
    }



    const directFullKeyLookup = `config.fields.${fullKey}.label`
    translated = t(directFullKeyLookup)
    if (translated !== directFullKeyLookup) return translated
  }


  const sectionFieldKey = `config.fields.${sectionKey}.${fieldKey}.label`
  let translated = t(sectionFieldKey)
  if (translated !== sectionFieldKey) return translated


  const camelFieldKey = `config.fields.${camelSection}.${fieldKey}.label`
  translated = t(camelFieldKey)
  if (translated !== camelFieldKey) return translated


  if (hasDistinctFullCamel) {
    const fullCamelFieldKey = `config.fields.${fullCamelSection}.${fieldKey}.label`
    translated = t(fullCamelFieldKey)
    if (translated !== fullCamelFieldKey) return translated
  }


  const genericKey = `config.fields.${fieldKey}.label`
  translated = t(genericKey)
  return translated !== genericKey ? translated : fieldKey
}

function getFieldDescription(sectionKey: string, fieldKey: string, fullKey?: string): string {
  const camelSection = sectionKey.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase())
  const fullCamelSection = toFullCamelCase(sectionKey)
  const hasDistinctFullCamel = fullCamelSection !== camelSection

  if (fullKey && fullKey !== fieldKey) {

    const sectionNestedKey = `config.fields.${sectionKey}.${fullKey}.description`
    let translated = t(sectionNestedKey)
    if (translated !== sectionNestedKey) return translated


    const camelNestedKey = `config.fields.${camelSection}.${fullKey}.description`
    translated = t(camelNestedKey)
    if (translated !== camelNestedKey) return translated


    if (hasDistinctFullCamel) {
      const fullCamelNestedKey = `config.fields.${fullCamelSection}.${fullKey}.description`
      translated = t(fullCamelNestedKey)
      if (translated !== fullCamelNestedKey) return translated
    }


    const directFullKeyLookup = `config.fields.${fullKey}.description`
    translated = t(directFullKeyLookup)
    if (translated !== directFullKeyLookup) return translated
  }


  const sectionFieldKey = `config.fields.${sectionKey}.${fieldKey}.description`
  let translated = t(sectionFieldKey)
  if (translated !== sectionFieldKey) return translated


  const camelFieldKey = `config.fields.${camelSection}.${fieldKey}.description`
  translated = t(camelFieldKey)
  if (translated !== camelFieldKey) return translated


  if (hasDistinctFullCamel) {
    const fullCamelFieldKey = `config.fields.${fullCamelSection}.${fieldKey}.description`
    translated = t(fullCamelFieldKey)
    if (translated !== fullCamelFieldKey) return translated
  }


  const genericKey = `config.fields.${fieldKey}.description`
  translated = t(genericKey)
  return translated !== genericKey ? translated : ''
}

function getFieldValue(sectionKey: string, fieldKey: string, fullKey?: string): unknown {

  if (changes[sectionKey]?.[fieldKey] !== undefined) {
    return changes[sectionKey][fieldKey]
  }


  const section = config.value?.[sectionKey as keyof PluginConfig]
  if (!section || typeof section !== 'object') return null


  if (fullKey && fullKey !== fieldKey) {
    const keys = fullKey.split('.')
    let current: unknown = section
    
    for (const k of keys) {
      if (current && typeof current === 'object') {
        current = (current as Record<string, unknown>)[k]
      } else {
        return null
      }
    }
    
    if (current && typeof current === 'object') {
      const field = current as Record<string, ConfigField>
      return field?.value ?? null
    }
  }


  const field = (section as Record<string, ConfigField>)[fieldKey]
  return field?.value ?? null
}

function updateField(section: string, key: string, value: unknown, fullKey?: string) {

  const fieldKey = fullKey && fullKey !== key ? fullKey : key
  if (!changes[section]) {
    changes[section] = {}
  }
  changes[section][fieldKey] = value
}

function hasChanges(section: string): boolean {
  return !!(changes[section] && Object.keys(changes[section]).length > 0)
}

function resetSection(section: string) {
  delete changes[section]
}

function getSelectOptions(allowedValues: string[]): Array<{ value: string; label: string }> {
  return allowedValues.map(value => {

    const translated = t(`config.fields.${value}`)
    return {
      value,
      label: translated !== `config.fields.${value}` ? translated : value
    }
  })
}

const configSections = computed(() => {
  if (!config.value) return []

  const grouped = groupConfigBySection(config.value)

  return grouped.map(group => {

    let status: 'enabled' | 'disabled' | undefined


    const enabledField = group.fields.find(f => f.key === 'enabled')
    if (enabledField) {
      status = enabledField.value === true ? 'enabled' : 'disabled'
    }

    return {
      key: group.key,
      icon: sectionIcons[group.key] || Settings,
      title: group.key,
      status,
      fields: group.fields
    }
  })
})

const canSyncToNodes = computed(() => {
  return nodesStore.centralMode && nodesStore.enabledNodes.length > 0
})

async function loadConfig() {
  try {
    const data = await configService.getConfig()
    config.value = data
    originalConfig.value = JSON.parse(JSON.stringify(data))


    Object.keys(changes).forEach(key => delete changes[key])
  } catch (error) {
    console.error('Failed to load config:', error)
    notificationStore.addNotification('error', t('config.loadError'), t('config.loadErrorDesc'))
  }
}

async function saveSection(section: string) {
  if (!changes[section] || Object.keys(changes[section]).length === 0) return

  saving.value = true
  try {
    const sectionChanges = Object.entries(changes[section]).map(([key, value]) => ({
      section,
      key,
      value
    }))

    await configService.saveConfig(sectionChanges, true)

    notificationStore.addNotification('success', t('config.saveSuccess'), t('config.saveSuccessDesc'))


    delete changes[section]


    await loadConfig()
  } catch (error) {
    console.error('Failed to save config:', error)
    notificationStore.addNotification('error', t('config.saveError'), t('config.saveErrorDesc'))
  } finally {
    saving.value = false
  }
}

async function reloadConfig() {
  reloading.value = true
  try {
    await configService.reloadConfig()
    notificationStore.addNotification('success', t('config.reloadSuccess'), t('config.reloadSuccessDesc'))
    await loadConfig()
  } catch (error) {
    notificationStore.addNotification('error', t('config.reloadError'), t('config.reloadErrorDesc'))
  } finally {
    reloading.value = false
  }
}

async function syncToNodes(section: string) {
  if (!changes[section] || Object.keys(changes[section]).length === 0) return

  syncing.value = true
  try {
    const sectionChanges = Object.entries(changes[section]).map(([key, value]) => ({
      section,
      key,
      value
    }))

    const result = await nodesStore.syncConfigToNodes(sectionChanges, true)

    const message = result.failed === 0
      ? t('config.syncSuccess', { count: result.succeeded })
      : t('config.syncPartialSuccess', { succeeded: result.succeeded, failed: result.failed })
    const type = result.failed === 0 ? 'success' : 'warning'

    notificationStore.addNotification(type, message, '')

    delete changes[section]
    await loadConfig()
  } catch (error) {
    console.error('Failed to sync config:', error)
    notificationStore.addNotification('error', t('config.syncError'), t('config.syncErrorDesc'))
  } finally {
    syncing.value = false
  }
}

onMounted(async () => {
  
  await nodesStore.fetchNodes().catch(() => {})
  loadConfig()
})
</script>

<style scoped>
.collapse-enter-active,
.collapse-leave-active { transition: all 0.2s ease; overflow: hidden; }
.collapse-enter-from,
.collapse-leave-to { opacity: 0; max-height: 0; }
.collapse-enter-to,
.collapse-leave-from { opacity: 1; max-height: 500px; }
</style>
