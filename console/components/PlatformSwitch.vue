<template>
  <!--
    平台分段控件。四处复用（任务页头 / 岗位筛选 / 配置的平台配置 tab / 系统页行内），
    保证同一个动作在各页看起来是同一个东西。

    刻意不做成「侧边栏全局切换器」：控件固定在侧边栏、却只对部分页面与部分 tab 生效，
    用户切完发现没反应，会判断成「坏了」。位置跟着作用域走，不是跟着统一感走。
  -->
  <div class="seg" role="tablist">
    <button
      v-for="p in platforms"
      :key="p.platform"
      type="button"
      role="tab"
      class="seg-item"
      :class="{ active: p.platform === modelValue }"
      :aria-selected="p.platform === modelValue"
      :title="p.note || p.label"
      @click="emit('update:modelValue', p.platform)"
    >
      {{ p.label }}
      <span v-if="p.enabled === false" class="seg-off">已停用</span>
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string
  platforms: { platform: string; label: string; note?: string; enabled?: boolean }[]
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()
</script>

<style scoped>
.seg {
  display: inline-flex;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
}

.seg-item {
  padding: 7px 15px;
  font-size: 13px;
  line-height: 1.4;
  color: var(--muted);
  background: var(--surface);
  border: 0;
  border-right: 1px solid var(--line);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: background 120ms ease, color 120ms ease;
}

.seg-item:last-child {
  border-right: 0;
}

.seg-item:hover {
  background: var(--surface-2);
  color: var(--ink-2);
}

.seg-item.active {
  background: var(--ink);
  color: #fff;
}

.seg-item.active:hover {
  background: #302b26;
}

.seg-off {
  font-size: 10.5px;
  padding: 0 4px;
  border: 1px solid currentColor;
  border-radius: 2px;
  opacity: 0.7;
}
</style>
