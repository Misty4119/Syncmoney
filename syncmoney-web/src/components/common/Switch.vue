<template>
  <div class="inline-flex items-center gap-3 select-none">
    <div class="relative inline-flex items-center">
      <button
        type="button"
        role="switch"
        :aria-checked="modelValue"
        :disabled="disabled"
        :title="disabled ? (disabledReason || t('common.disabledOption')) : (modelValue ? t('common.enabled') : t('common.disabled'))"
        :class="[
          'relative inline-flex h-6 w-11 shrink-0 rounded-full border-2 transition-all duration-300 ease-in-out focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
          disabled
            ? 'cursor-not-allowed opacity-50'
            : ''
        ]"
        :style="disabled
          ? { background: 'var(--ctrl-bg-off)', borderColor: 'var(--surface-border)' }
          : modelValue
            ? {
                background: 'var(--color-primary)',
                borderColor: 'var(--color-primary)',
                boxShadow: '0 0 10px var(--color-primary-glow)'
              }
            : {
                background: 'var(--ctrl-bg-off)',
                borderColor: 'var(--ctrl-border)'
              }"
        @click="toggle"
      >
        <!-- Thumb / Slider Knob -->
        <span
          :class="[
            'pointer-events-none flex items-center justify-center h-5 w-5 transform rounded-full bg-white shadow-md',
            'transition-transform duration-300 ease-in-out mt-[1px] ml-[1px]',
            modelValue ? 'translate-x-5' : 'translate-x-0',
            disabled && 'bg-surface-100 dark:bg-surface-400'
          ]"
        >
          <!-- Disabled mini lock indicator on the knob if disabled -->
          <Lock v-if="disabled" class="w-2.5 h-2.5 text-surface-500 dark:text-surface-700" />
        </span>
      </button>
    </div>

    <!-- Label -->
    <label
      v-if="label"
      :class="[
        'text-sm transition-colors',
        disabled
          ? 'cursor-not-allowed text-muted'
          : 'cursor-pointer hover:text-primary'
      ]"
      :style="{ color: disabled ? 'var(--text-muted)' : 'var(--text-secondary)' }"
      @click="toggle"
    >
      {{ label }}
    </label>
    <span v-if="description" class="text-xs" :style="{ color: 'var(--text-muted)' }">{{ description }}</span>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Lock } from 'lucide-vue-next'

const { t } = useI18n()

interface Props {
  modelValue?: boolean
  label?: string
  description?: string
  disabled?: boolean
  disabledReason?: string
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: false,
  disabled: false,
  disabledReason: ''
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

