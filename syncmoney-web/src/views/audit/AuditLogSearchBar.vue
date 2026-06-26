<template>
  <div class="relative w-[180px] group">
    <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-primary/50 group-focus-within:text-primary transition-colors duration-300" />
    <input
      :value="modelValue"
      type="text"
      class="w-full pl-10 pr-4 py-2 bg-surface-50/50 dark:bg-surface-950/50 backdrop-blur-md border border-surface-200 dark:border-surface-700 rounded-lg text-sm text-surface-900 dark:text-surface-100 placeholder-surface-500 focus:outline-none focus:border-primary focus:shadow-glow-sm transition-all duration-300"
      :placeholder="placeholder"
      :aria-label="placeholder"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @keydown.enter="$emit('enter')"
    />
  </div>
</template>

<script setup lang="ts">
import { Search } from 'lucide-vue-next'

defineProps<{
  modelValue: string
  placeholder: string
}>()

defineEmits<{
  'update:modelValue': [value: string]
  enter: []
}>()
</script>
