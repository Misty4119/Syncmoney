<template>
  <div class="not-found">
    <div class="content">
      <h1 class="code">404</h1>
      <p class="message">{{ t('notFound.message') }}</p>
      <router-link to="/" class="home-link">
        {{ t('notFound.goHome') }}
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
}

:global(.light) .not-found {
  background: linear-gradient(135deg, #f3f4f6 0%, #e5e7eb 100%);
}

.content {
  text-align: center;
  color: white;
}

:global(.light) .content {
  color: #1f2937;
}

.code {
  font-size: 8rem;
  font-weight: 700;
  margin: 0;
  background: linear-gradient(135deg, #ef4444 0%, #f97316 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.message {
  font-size: 1.5rem;
  margin: 1rem 0 2rem;
  opacity: 0.8;
}

:global(.light) .message {
  color: #4b5563;
}

.home-link {
  display: inline-block;
  padding: 0.75rem 1.5rem;
  background: #3b82f6;
  color: white;
  text-decoration: none;
  border-radius: 0.5rem;
  transition: background 0.2s;
}

.home-link:hover {
  background: #2563eb;
}

:global(.light) .home-link {
  background: #3b82f6;
}

:global(.light) .home-link:hover {
  background: #2563eb;
}
</style>
