<template>
  <div :class="skeletonClasses" :style="skeletonStyle" />
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  variant?: 'text' | 'circle' | 'rect'
  width?: string
  height?: string
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'text',
})

const skeletonClasses = computed(() => [
  'skeleton',
  { 'rounded-full': props.variant === 'circle' },
])

const skeletonStyle = computed(() => ({
  width: props.width ?? (props.variant === 'circle' ? '40px' : '100%'),
  height: props.height ?? (props.variant === 'text' ? '16px' : props.variant === 'circle' ? '40px' : '80px'),
}))
</script>
