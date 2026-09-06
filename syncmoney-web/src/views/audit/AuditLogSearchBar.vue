<template>
  <div class="relative w-[180px] group">
    <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-primary/50 group-focus-within:text-primary transition-colors duration-300" />
    <input
      :value="modelValue"
      type="text"
      class="w-full pl-10 pr-4 py-2 rounded-lg text-sm focus:outline-none shadow-sm transition-all duration-300"
      :style="{
        background: 'var(--ctrl-bg)',
        border: '1px solid var(--ctrl-border)',
        color: 'var(--ctrl-text)',
      }"
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
