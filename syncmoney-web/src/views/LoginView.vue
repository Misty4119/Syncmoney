<template>
  <div class="min-h-screen flex items-center justify-center p-4 relative overflow-hidden bg-surface-bg transition-colors duration-500">
    <!-- Background effects -->
    <div class="absolute inset-0 bg-surface-bg/80 backdrop-blur-3xl z-0" />
    <div class="absolute top-1/4 -left-32 w-[600px] h-[600px] bg-primary/10 rounded-full blur-[120px] animate-float" />
    <div class="absolute bottom-1/4 -right-32 w-[600px] h-[600px] bg-secondary/10 rounded-full blur-[120px] animate-float-delayed" />
    <div class="absolute inset-0 bg-[radial-gradient(circle_at_center,transparent_0%,var(--surface-bg)_100%)] pointer-events-none z-0" />

    <!-- Login card -->
    <div class="glass-card w-full max-w-md p-10 relative z-10 animate-fade-slide-up border border-primary/20 shadow-[0_0_40px_var(--color-primary-glow)] bg-surface-bg/70 backdrop-blur-xl">
      <!-- Brand -->
      <div class="text-center mb-10">
        <div class="w-20 h-20 rounded-2xl bg-surface-50 border border-primary/40 flex items-center justify-center mx-auto mb-6 shadow-glow focus:outline-none relative overflow-hidden group">
          <div class="absolute inset-0 bg-primary/20 translate-y-full group-hover:translate-y-0 transition-transform duration-500 ease-out"></div>
          <Zap class="w-10 h-10 text-primary group-hover:scale-110 group-hover:text-white transition-all duration-500 relative z-10" />
        </div>
        <h1 class="text-3xl font-bold tracking-wider mb-2 text-surface-900 dark:text-white drop-shadow-sm transition-colors">{{ t('auth.appName') }}</h1>
        <p class="text-xs text-primary/80 tracking-[0.3em] uppercase font-mono">{{ t('auth.subtitle') }}</p>
      </div>

      <form @submit.prevent="handleLogin" class="space-y-5">
        <!-- API Key input -->
        <div>
          <label for="api-key" class="block text-sm text-surface-700 dark:text-surface-400 mb-2 font-medium">{{ t('auth.apiKey') }}</label>
          <div class="relative group mt-1">
            <KeyRound class="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-surface-500 dark:text-surface-400 group-focus-within:text-primary transition-colors duration-300" />
            <input
              id="api-key"
              v-model="apiKey"
              type="password"
              class="w-full pl-11 pr-4 py-3 bg-surface-50 border border-surface-200 rounded-xl text-surface-700 dark:text-surface-100 placeholder-surface-500 focus:outline-none focus:border-primary/50 focus:ring-2 focus:ring-primary/20 transition-all duration-300 shadow-inner"
              :placeholder="t('auth.apiKeyPlaceholder')"
              required
            />
          </div>
        </div>

        <!-- Error -->
        <div v-if="error" class="flex items-center gap-2 text-sm text-error bg-error/10 border border-error/20 rounded-xl p-3">
          <AlertCircle class="w-4 h-4 flex-shrink-0" />
          <span>{{ error }}</span>
        </div>

        <!-- Submit -->
        <Button
          type="submit"
          variant="primary"
          size="lg"
          class="w-full bg-primary text-white border-transparent hover:bg-primary-hover hover:shadow-glow-primary transition-all duration-300 rounded-xl py-3.5 font-bold tracking-wide mt-2"
          :loading="loading"
        >
          {{ t('auth.login') }}
        </Button>
      </form>

      <!-- Version -->
      <p class="text-center text-xs text-surface-500 dark:text-surface-600 mt-6">v{{ appVersion }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNodesStore } from '@/stores/nodes'
import { Zap, KeyRound, AlertCircle } from 'lucide-vue-next'
import Button from '@/components/common/Button.vue'

declare const __APP_VERSION__: string

const { t } = useI18n()
const router = useRouter()
const authStore = useAuthStore()
const nodesStore = useNodesStore()
const appVersion = __APP_VERSION__

const apiKey = ref('')
const loading = computed(() => authStore.isLoading)
const error = computed(() => authStore.error)

async function handleLogin() {
  const success = await authStore.login(apiKey.value)
  if (success) {
    
    
    await nodesStore.fetchNodes().catch(() => {})
    router.push('/dashboard')
  }
}
</script>
