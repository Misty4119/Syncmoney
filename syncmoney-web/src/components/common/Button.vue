<template>
  <button
    :type="type"
    :class="btnClasses"
    :disabled="disabled || loading"
    @click="$emit('click', $event)"
  >
    <Loader2 v-if="loading" class="w-4 h-4 animate-spin" />
    <component v-if="icon && !loading && iconPosition === 'left'" :is="icon" class="w-4 h-4" />
    <span v-if="$slots.default"><slot /></span>
    <component v-if="icon && !loading && iconPosition === 'right'" :is="icon" class="w-4 h-4" />
  </button>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Loader2 } from 'lucide-vue-next'

interface Props {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'outline'
  size?: 'sm' | 'md' | 'lg'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
  icon?: Component
  iconPosition?: 'left' | 'right'
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  disabled: false,
  loading: false,
  iconPosition: 'left',
})

defineEmits<{
  click: [event: MouseEvent]
}>()

const sizeClass = computed(() => {
  const map: Record<string, string> = {
    sm: 'px-3 py-1.5 text-xs gap-1.5',
    md: 'px-4 py-2 text-sm gap-2',
    lg: 'px-6 py-3 text-base gap-2',
  }
  return map[props.size]
})

const variantClass = computed(() => {
  const map: Record<string, string> = {
    primary:   'bg-cyan-500 text-surface-950 hover:bg-cyan-400 shadow-glow-sm hover:shadow-glow font-semibold dark:bg-cyan-500 dark:text-surface-950 dark:hover:bg-cyan-400',
    secondary: 'bg-gray-200 text-gray-700 hover:bg-gray-300 border border-gray-300 dark:bg-surface-700 dark:text-surface-200 dark:hover:bg-surface-600 dark:border-surface-600',
    ghost:     'text-gray-600 hover:text-gray-900 hover:bg-gray-100 dark:text-surface-300 dark:hover:text-surface-100 dark:hover:bg-surface-800',
    danger:    'bg-error text-white hover:bg-red-600 shadow-glow-error dark:bg-error dark:hover:bg-red-600',
    outline:   'border border-gray-300 text-gray-700 hover:border-cyan-500 hover:text-cyan-600 dark:border-surface-600 dark:text-surface-300 dark:hover:border-cyan-500/50 dark:hover:text-cyan-400',
  }
  return map[props.variant]
})

const btnClasses = computed(() => [
  'inline-flex items-center justify-center font-medium rounded-lg transition-all duration-200 btn-ripple',
  sizeClass.value,
  variantClass.value,
  { 'opacity-50 cursor-not-allowed pointer-events-none': props.disabled || props.loading },
])
</script>
