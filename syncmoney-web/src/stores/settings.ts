import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { apiClient, type ApiResponse, type Settings } from '@/api/client'
import { handleError } from '@/types/errors'

export type Theme = 'dark' | 'light'
export type Locale = 'zh-TW' | 'en-US'

function getStoredTheme(): Theme {
  const stored = localStorage.getItem('theme')
  return stored === 'light' || stored === 'dark' ? stored : 'dark'
}

function getStoredLocale(): Locale {
  const stored = localStorage.getItem('locale')
  return stored === 'zh-TW' || stored === 'en-US' ? stored : 'zh-TW'
}

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<Theme>(getStoredTheme())
  const locale = ref<Locale>(getStoredLocale())
  const timezone = ref<string>(localStorage.getItem('timezone') || 'UTC')
  const isLoading = ref<boolean>(false)


  const dedupServerWindowSeconds = ref<number>(
    parseInt(localStorage.getItem('dedupServerWindowSeconds') || '3600')
  )
  const dedupClientCacheSize = ref<number>(
    parseInt(localStorage.getItem('dedupClientCacheSize') || '1000')
  )


  watch(theme, (newTheme) => {
    localStorage.setItem('theme', newTheme)


    if (newTheme === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }, { immediate: true })

  function setTheme(newTheme: Theme) {

    theme.value = newTheme

    saveToServer()
  }

  function setLocale(newLocale: Locale) {

    locale.value = newLocale

    applyLocale(newLocale)

    saveToServer()
  }


  function setLocaleDirect(newLocale: Locale) {
    locale.value = newLocale
    applyLocale(newLocale)
  }

  function setTimezone(newTz: string) {

    applyTimezone(newTz)
  }


  function setDedupServerWindow(seconds: number) {
    dedupServerWindowSeconds.value = seconds
    localStorage.setItem('dedupServerWindowSeconds', String(seconds))
  }

  function setDedupClientCacheSize(size: number) {
    dedupClientCacheSize.value = size
    localStorage.setItem('dedupClientCacheSize', String(size))
  }


  function applyLocale(newLocale: Locale) {
    localStorage.setItem('locale', newLocale)
  }


  function applyTimezone(newTz: string) {
    timezone.value = newTz
    localStorage.setItem('timezone', newTz)
  }

  async function saveToServer() {
    if (!localStorage.getItem('apiKey')) {

      return
    }

    try {

      await apiClient.post('/api/settings/theme', { theme: theme.value })
      await apiClient.post('/api/settings/language', { language: locale.value })
      await apiClient.post('/api/settings/timezone', { timezone: timezone.value })
    } catch (error: unknown) {
      const err = handleError(error)


      console.warn('Failed to save settings to server (settings saved locally):', err.message)
    }
  }

  async function loadFromServer() {
    if (!localStorage.getItem('apiKey')) return

    isLoading.value = true
    try {
      const response = await apiClient.get<ApiResponse<Settings>>('/api/settings')
      if (response.data?.success && response.data?.data) {
        if (response.data.data.theme) {
          theme.value = response.data.data.theme
        }
        if (response.data.data.language) {
          locale.value = response.data.data.language
        }
        if (response.data.data.timezone) {
          timezone.value = response.data.data.timezone
        }

        if (response.data.data.dedupServerWindowSeconds) {
          dedupServerWindowSeconds.value = response.data.data.dedupServerWindowSeconds
          localStorage.setItem('dedupServerWindowSeconds', String(response.data.data.dedupServerWindowSeconds))
        }
        if (response.data.data.dedupClientCacheSize) {
          dedupClientCacheSize.value = response.data.data.dedupClientCacheSize
          localStorage.setItem('dedupClientCacheSize', String(response.data.data.dedupClientCacheSize))
        }
      }
    } catch (e) {
      console.warn('Failed to load settings from server:', e)
    } finally {
      isLoading.value = false
    }
  }

  return {
    theme,
    locale,
    timezone,
    isLoading,

    dedupServerWindowSeconds,
    dedupClientCacheSize,
    setTheme,
    setLocale,
    setLocaleDirect,
    setTimezone,
    applyTimezone,

    setDedupServerWindow,
    setDedupClientCacheSize,
    saveToServer,
    loadFromServer
  }
})
