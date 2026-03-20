<template>
  <div :class="cardClasses">
    <div v-if="$slots.header" class="card-header">
      <slot name="header" />
    </div>
    <div :class="bodyClass">
      <slot />
    </div>
    <div v-if="$slots.footer" class="card-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  variant?: 'glass' | 'solid' | 'outline'
  padding?: 'none' | 'sm' | 'md' | 'lg'
  hoverable?: boolean
  glow?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'glass',
  padding: 'md',
  hoverable: false,
  glow: false,
})

const cardClasses = computed(() => {
  const base =
    props.variant === 'glass'
      ? props.hoverable
        ? 'glass-card-hoverable'
        : 'glass-card'
      : props.variant === 'solid'
        ? 'bg-surface-800 rounded-xl border border-surface-700'
        : 'rounded-xl border border-surface-700'

  return [
    base,
    { 'shadow-glow-sm border-cyan-500/20': props.glow },
    { 'hoverable': props.hoverable && props.variant !== 'glass' },
  ]
})

const bodyClass = computed(() => {
  const map: Record<string, string> = {
    none: '',
    sm: 'p-3',
    md: 'p-5',
    lg: 'p-6',
  }
  return map[props.padding]
})
</script>

<style scoped>
.card-header {
  @apply px-5 py-3 border-b border-gray-200 dark:border-surface-700/50;
}
.card-footer {
  @apply px-5 py-3 border-t border-gray-200 dark:border-surface-700/50;
}
</style>
