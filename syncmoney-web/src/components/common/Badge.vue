<template>
  <span :class="badgeClasses">
    <span v-if="dot" class="status-dot mr-1.5" :class="variant" />
    <slot />
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  variant?: 'success' | 'warning' | 'error' | 'info' | 'default'
  size?: 'sm' | 'md'
  dot?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'default',
  size: 'md',
  dot: false,
})

const sizeClass = computed(() =>
  props.size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-0.5 text-xs'
)

const variantClass = computed(() => {
  const map: Record<string, string> = {
    success: 'bg-success/15 text-success border border-success/20',
    warning: 'bg-warning/15 text-warning border border-warning/20',
    error:   'bg-error/15 text-error border border-error/20',
    info:    'bg-info/15 text-info border border-info/20',
    default: 'bg-transparent dark:bg-surface-700/50 text-surface-600 dark:text-surface-300 border border-surface-300 dark:border-surface-600',
  }
  return map[props.variant]
})

const badgeClasses = computed(() => [
  'inline-flex items-center rounded-full font-medium',
  sizeClass.value,
  variantClass.value,
])
</script>
