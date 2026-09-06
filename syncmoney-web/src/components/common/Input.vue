<template>
  <div class="flex flex-col gap-1">
    <label v-if="label" :for="inputId" class="text-sm text-surface-600 dark:text-surface-400">
      {{ label }}
    </label>
    <input
      :id="inputId"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      class="w-full px-4 py-2.5 rounded-xl text-sm shadow-sm transition-all focus:outline-none focus:ring-2"
      :style="[
        {
          background: disabled ? 'var(--ctrl-bg-disabled)' : 'var(--ctrl-bg)',
          color: 'var(--ctrl-text)',
          borderColor: error ? '#fb7185' : 'var(--ctrl-border)',
          border: '1px solid',
        },
        disabled ? { cursor: 'not-allowed', opacity: '0.6' } : {}
      ]"
      :class="[
        'focus:ring-[var(--color-primary-glow)]',
        error ? 'border-error focus:border-error focus:ring-error/30' : ''
      ]"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @blur="$emit('blur', $event)"
    />
    <span v-if="error" class="text-xs text-error">{{ error }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  modelValue?: string | number
  type?: string
  placeholder?: string
  label?: string
  disabled?: boolean
  error?: string
  id?: string
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  disabled: false,
})

defineEmits<{
  'update:modelValue': [value: string]
  blur: [event: FocusEvent]
}>()

const inputId = computed(() => {
  const shortId = (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
    ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).substring(2, 10)
  return props.id || `input-${shortId}`
})
</script>
