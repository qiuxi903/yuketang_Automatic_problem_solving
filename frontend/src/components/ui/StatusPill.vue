<script setup lang="ts">
import type { Tone } from '@/utils/status'

defineProps<{
  tone?: Tone
  dot?: boolean
}>()
</script>

<template>
  <span class="pill" :class="`t-${tone ?? 'neutral'}`">
    <i v-if="dot !== false" class="dot" />
    <slot />
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  border-radius: 99px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.7;
  white-space: nowrap;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.t-ok {
  color: var(--ok);
  background: var(--ok-100);
}
.t-warn {
  color: var(--warn);
  background: var(--warn-100);
}
.t-danger {
  color: var(--danger);
  background: var(--danger-100);
}
.t-info {
  color: var(--info);
  background: var(--info-100);
}
.t-accent {
  color: var(--accent-700);
  background: var(--accent-100);
}
.t-neutral {
  color: var(--ink-500);
  background: var(--surface-3);
}
</style>
