<template>
  <div class="flex items-center gap-3">
    <button
      type="button"
      role="switch"
      :aria-checked="modelValue"
      :disabled="disabled"
      :class="[
        'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent',
        'transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-cyan-500/30',
        modelValue ? 'bg-cyan-500' : 'bg-surface-600',
        disabled && 'opacity-50 cursor-not-allowed'
      ]"
      @click="toggle"
    >
      <span
        :class="[
          'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-lg',
          'ring-0 transition duration-200 ease-in-out',
          modelValue ? 'translate-x-5' : 'translate-x-0'
        ]"
      />
    </button>
    <label v-if="label" class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer" @click="toggle">
      {{ label }}
    </label>
    <span v-if="description" class="text-xs text-surface-500">{{ description }}</span>
  </div>
</template>

<script setup lang="ts">
interface Props {
  modelValue?: boolean
  label?: string
  description?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: false,
  disabled: false
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

function toggle() {
  if (!props.disabled) {
    emit('update:modelValue', !props.modelValue)
  }
}
</script>
