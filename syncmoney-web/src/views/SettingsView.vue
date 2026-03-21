<template>
  <div class="space-y-6 max-w-2xl">
    <!-- Theme -->
    <Card variant="glass" class="border-primary/20 shadow-glow-sm">
      <template #header>
        <div class="flex items-center gap-3 px-2">
          <div class="p-2 bg-primary/10 rounded-xl">
            <Palette class="w-5 h-5 text-primary" />
          </div>
          <span class="text-base font-bold gradient-text uppercase tracking-wide">{{ t('settings.theme') }}</span>
        </div>
      </template>
      <div class="grid grid-cols-2 gap-4">
        <button
          v-for="opt in themeOptions"
          :key="opt.value"
          class="relative p-5 rounded-2xl border-2 transition-all duration-300 text-left overflow-hidden group"
          :class="theme === opt.value
            ? 'border-primary bg-primary/10 shadow-glow-primary scale-[1.02]'
            : 'border-surface-200 dark:border-surface-700 hover:border-primary/30 hover:bg-surface-50/50 dark:hover:bg-surface-950/50'"
          @click="handleThemeChange(opt)"
        >
          <div class="absolute inset-0 bg-gradient-to-br from-primary/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
          <div class="relative z-10 flex items-center gap-3 mb-4">
            <component :is="opt.icon" class="w-6 h-6" :class="theme === opt.value ? 'text-primary group-hover:scale-110 transition-transform' : 'text-surface-400 group-hover:text-primary transition-colors'" />
            <span class="font-bold tracking-wide" :class="theme === opt.value ? 'text-primary drop-shadow-md' : 'text-surface-600 dark:text-surface-300 group-hover:text-surface-900 dark:group-hover:text-surface-200'">
              {{ t(opt.label) }}
            </span>
          </div>
          <div class="space-y-1.5">
            <div class="h-2 rounded-full" :class="theme === 'dark' ? 'bg-surface-700' : 'bg-surface-300'" style="width:80%" />
            <div class="h-2 rounded-full" :class="theme === 'dark' ? 'bg-surface-700' : 'bg-surface-300'" style="width:60%" />
          </div>
          <div v-if="theme === opt.value" class="absolute top-3 right-3">
            <CheckCircle class="w-5 h-5 text-primary" />
          </div>
        </button>
      </div>
    </Card>

    <!-- Language -->
    <Card variant="glass" class="border-primary/20 shadow-glow-sm mt-6">
      <template #header>
        <div class="flex items-center gap-3 px-2">
          <div class="p-2 bg-primary/10 rounded-xl">
            <Globe class="w-5 h-5 text-primary" />
          </div>
          <span class="text-base font-bold gradient-text uppercase tracking-wide">{{ t('settings.language') }}</span>
        </div>
      </template>
      <select
        v-model="language"
        class="w-full max-w-xs px-4 py-3 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-xl text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300 appearance-none cursor-pointer"
      >
        <option value="zh-TW" class="bg-surface-bg dark:bg-surface-900">{{ t('settings.languageOptions.zh-TW') }}</option>
        <option value="en-US" class="bg-surface-bg dark:bg-surface-900">{{ t('settings.languageOptions.en-US') }}</option>
      </select>
    </Card>

    <!-- Timezone -->
    <Card variant="glass" class="border-primary/20 shadow-glow-sm mt-6">
      <template #header>
        <div class="flex items-center gap-3 px-2">
          <div class="p-2 bg-primary/10 rounded-xl">
            <Clock class="w-5 h-5 text-primary" />
          </div>
          <span class="text-base font-bold gradient-text uppercase tracking-wide">{{ t('settings.timezone') }}</span>
        </div>
      </template>
      <select
        v-model="timezone"
        class="w-full max-w-xs px-4 py-3 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-xl text-surface-900 dark:text-surface-200 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300 appearance-none cursor-pointer"
      >
        <option v-for="tz in timezoneOptions" :key="tz" :value="tz" class="bg-surface-bg dark:bg-surface-900">{{ tz }}</option>
      </select>
    </Card>

    <!-- Save -->
    <div class="flex justify-end pt-4">
      <Button variant="primary" :icon="Save" :loading="saving" class="bg-primary text-white font-bold border-transparent hover:shadow-glow-primary hover:scale-105 transition-all duration-300" @click="saveSettings">
        {{ t('settings.save') }}
      </Button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import { useNotificationStore } from '@/stores/notification'
import { useNodesStore } from '@/stores/nodes'
import { Palette, Globe, Moon, Sun, Clock, CheckCircle, Save } from 'lucide-vue-next'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'

const { t, locale } = useI18n()
const settingsStore = useSettingsStore()
const notificationStore = useNotificationStore()
const nodesStore = useNodesStore()

const theme = ref(settingsStore.theme)
const language = ref(settingsStore.locale)
const timezone = ref(settingsStore.timezone)
const saving = ref(false)


watch(theme, (newTheme) => {
  settingsStore.setTheme(newTheme)
  applyThemePreview(newTheme)
})

const themeOptions = [
  { value: 'light', label: 'settings.themeOptions.light', icon: Sun },
  { value: 'dark', label: 'settings.themeOptions.dark', icon: Moon }
]


function applyThemePreview(themeValue: string) {
  if (themeValue === 'dark') {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

function handleThemeChange(opt: { value: string }) {
  theme.value = opt.value as 'dark' | 'light'
  applyThemePreview(opt.value)
}


const timezoneOptions = [
  'UTC',
  'UTC+1', 'UTC+2', 'UTC+3', 'UTC+4', 'UTC+5', 'UTC+6', 'UTC+7', 'UTC+8', 'UTC+9', 'UTC+10', 'UTC+11', 'UTC+12',
  'UTC-1', 'UTC-2', 'UTC-3', 'UTC-4', 'UTC-5', 'UTC-6', 'UTC-7', 'UTC-8', 'UTC-9', 'UTC-10', 'UTC-11'
]

async function saveSettings() {
  saving.value = true
  try {

    settingsStore.setTheme(theme.value as 'dark' | 'light')
    settingsStore.setLocale(language.value as 'zh-TW' | 'en-US')
    settingsStore.setTimezone(timezone.value)


    locale.value = language.value


    settingsStore.applyTimezone(timezone.value)


    notificationStore.success(t('settings.saveSuccess'), 'Settings')
  } catch (e) {
    notificationStore.error(t('settings.saveFailed'), 'Error')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  
  await nodesStore.fetchNodes().catch(() => {})
  theme.value = settingsStore.theme
  language.value = settingsStore.locale
  timezone.value = settingsStore.timezone
})
</script>
