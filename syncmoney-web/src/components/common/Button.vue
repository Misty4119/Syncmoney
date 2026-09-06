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
    primary:   'bg-primary text-white hover:bg-primary-hover shadow-glow-sm hover:shadow-glow-primary active:scale-[0.98] font-semibold',
    secondary: 'border border-surface-300 text-surface-700 hover:bg-surface-100 dark:border-surface-700 dark:text-surface-300 dark:hover:bg-surface-800/60 active:scale-[0.98]',
    ghost:     'text-surface-600 hover:text-surface-900 hover:bg-surface-100 dark:text-surface-300 dark:hover:text-surface-100 dark:hover:bg-surface-800/60 active:scale-[0.98]',
    danger:    'bg-error text-white hover:bg-red-500 shadow-glow-error active:scale-[0.98] font-semibold',
    outline:   'border border-surface-300 text-surface-700 hover:border-primary hover:text-primary dark:border-surface-700 dark:text-surface-300 dark:hover:border-primary/50 dark:hover:text-primary active:scale-[0.98]',
  }
  return map[props.variant]
})

const btnClasses = computed(() => [
  'inline-flex items-center justify-center font-medium rounded-xl transition-all duration-200 btn-ripple select-none',
  sizeClass.value,
  variantClass.value,
  { 'opacity-50 cursor-not-allowed pointer-events-none': props.disabled || props.loading },
])
</script>
