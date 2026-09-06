<template>
  <div class="flex flex-col gap-1">
    <label v-if="label" :for="selectId" class="text-sm text-surface-600 dark:text-surface-400">
      {{ label }}
    </label>
    <div class="relative">
      <select
        :id="selectId"
        :value="modelValue"
        :disabled="disabled"
        class="w-full px-4 py-2.5 rounded-xl text-sm appearance-none shadow-sm transition-all focus:outline-none focus:ring-2"
        :style="{
          background: disabled ? 'var(--ctrl-bg-disabled)' : 'var(--ctrl-bg)',
          color: disabled ? 'var(--text-muted)' : 'var(--ctrl-text)',
          border: '1px solid',
          borderColor: error ? '#fb7185' : 'var(--ctrl-border)',
          cursor: disabled ? 'not-allowed' : 'pointer',
          opacity: disabled ? '0.6' : '1',
        }"
        @change="handleChange"
      >
        <option v-for="option in options" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>
      <ChevronDown
        class="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-500 pointer-events-none"
      />
    </div>
    <span v-if="error" class="text-xs text-error">{{ error }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

interface Option {
  value: string | number
  label: string
}

interface Props {
  modelValue?: string | number
  options?: Option[]
  label?: string
  disabled?: boolean
  error?: string
}

withDefaults(defineProps<Props>(), {
  options: () => [],
  disabled: false
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
}>()

const selectId = computed(() => {
  const shortId = (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
    ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).slice(2, 10)
  return `select-${shortId}`
})

function handleChange(event: Event) {
  const target = event.target as HTMLSelectElement
  emit('update:modelValue', target.value)
}
</script>
