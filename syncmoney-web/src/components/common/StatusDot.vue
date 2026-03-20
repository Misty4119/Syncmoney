<template>
  <span class="inline-flex items-center gap-2">
    <span class="status-dot" :class="status" />
    <span v-if="label" class="text-xs" :class="labelClass">{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  status: 'connected' | 'disconnected' | 'warning' | 'loading'
  label?: string
}

const props = defineProps<Props>()

const labelClass = computed(() => {
  const map: Record<string, string> = {
    connected:    'text-success',
    disconnected: 'text-error',
    warning:      'text-warning',
    loading:      'text-surface-400',
  }
  return map[props.status]
})
</script>
