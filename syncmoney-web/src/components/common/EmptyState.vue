<template>
  <div class="flex flex-col items-center justify-center py-12 text-center">
    <div class="p-4 rounded-2xl bg-gray-100 dark:bg-surface-800/50 mb-4">
      <component v-if="icon" :is="icon" class="w-10 h-10 text-gray-400 dark:text-surface-500" />
      <Inbox v-else class="w-10 h-10 text-gray-400 dark:text-surface-500" />
    </div>
    <h3 v-if="title" class="text-lg font-medium text-gray-700 dark:text-surface-300 mb-1">{{ title }}</h3>
    <p v-if="description" class="text-sm text-gray-500 dark:text-surface-500 max-w-sm">{{ description }}</p>
    <div v-if="$slots.action" class="mt-4">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import { Inbox } from 'lucide-vue-next'

interface Props {
  icon?: Component
  title?: string
  description?: string
}

defineProps<Props>()
</script>
