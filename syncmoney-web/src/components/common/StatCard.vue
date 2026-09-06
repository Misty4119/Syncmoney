<template>
  <Card variant="glass" hoverable class="group relative overflow-hidden transition-all duration-300 hover:border-primary/50 hover:shadow-glow-primary">
    <!-- Cyber Accent Edge -->
    <div class="absolute top-0 left-0 right-0 h-[2px] bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500"></div>

    <template v-if="loading">
      <div class="flex items-center justify-between">
        <div class="space-y-3 flex-1">
          <Skeleton width="60%" height="14px" />
          <Skeleton width="45%" height="32px" />
          <Skeleton width="35%" height="14px" />
        </div>
        <Skeleton variant="circle" width="48px" height="48px" />
      </div>
    </template>
    <template v-else>
      <div class="flex items-center justify-between">
        <div>
          <p class="text-xs font-semibold text-surface-500 dark:text-surface-400 tracking-wider uppercase font-mono">{{ label }}</p>
          <p class="text-3xl font-bold font-mono tabular-nums tracking-tight mt-2 text-surface-900 dark:text-white group-hover:text-primary transition-colors duration-300">{{ value }}</p>
          <div
            v-if="trend"
            class="flex items-center mt-3 text-xs font-mono font-medium"
            :class="trend.direction === 'up' ? 'text-success' : 'text-error'"
          >
            <TrendingUp v-if="trend.direction === 'up'" class="w-3.5 h-3.5 mr-1" />
            <TrendingDown v-else class="w-3.5 h-3.5 mr-1" />
            {{ trend.value }}%
          </div>
        </div>
        <div class="p-3.5 rounded-xl border border-transparent group-hover:border-current/20 transition-all duration-300 shadow-inner group-hover:scale-105" :class="iconBgClass">
          <component :is="icon" class="w-6 h-6 group-hover:scale-110 transition-transform duration-300" :class="iconColorClass" />
        </div>
      </div>
    </template>
  </Card>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { TrendingUp, TrendingDown } from 'lucide-vue-next'
import Card from './Card.vue'
import Skeleton from './Skeleton.vue'

interface Props {
  label: string
  value: string | number
  icon: Component
  trend?: { value: number; direction: 'up' | 'down' }
  color?: 'primary' | 'secondary' | 'green' | 'amber' | 'red'
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  color: 'primary',
  loading: false,
})

const iconBgClass = computed(() => {
  const map: Record<string, string> = {
    primary:   'bg-primary/10',
    secondary: 'bg-secondary/10',
    green:     'bg-success/10',
    amber:     'bg-warning/10',
    red:       'bg-error/10',
  }
  return map[props.color]
})

const iconColorClass = computed(() => {
  const map: Record<string, string> = {
    primary:   'text-primary',
    secondary: 'text-secondary',
    green:     'text-success',
    amber:     'text-warning',
    red:       'text-error',
  }
  return map[props.color]
})
</script>
