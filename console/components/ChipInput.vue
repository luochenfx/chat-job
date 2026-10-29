<template>
  <div class="chip-input">
    <span v-for="(value, index) in modelValue" :key="value + index" class="chip">
      {{ value }}
      <button type="button" @click="remove(index)">×</button>
    </span>
    <input
      v-model="draft"
      :placeholder="modelValue.length ? '' : placeholder"
      :list="listId"
      @keydown.enter.prevent="commit"
      @blur="commit"
    />
    <datalist v-if="options.length" :id="listId">
      <option v-for="option in options" :key="option" :value="option" />
    </datalist>
  </div>
</template>

<script setup lang="ts">
// 两个数组 prop 都必须有默认值：模板里直接读 modelValue.length / options.length，
// 而 options 是可选的 —— 少传一处就是一次 "Cannot read properties of undefined (reading 'length')"，
// 并且 Vue 的渲染错误会冒到 Nuxt 的错误页，整页变成 500，不是"少一个下拉提示"那么轻。
const props = withDefaults(
  defineProps<{
    modelValue: string[]
    placeholder?: string
    options?: string[]
  }>(),
  { modelValue: () => [], placeholder: '', options: () => [] }
)

const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const draft = ref('')
const listId = `dl-${Math.random().toString(36).slice(2, 8)}`

function commit() {
  const value = draft.value.trim()
  if (!value) return
  if (!props.modelValue.includes(value)) {
    emit('update:modelValue', [...props.modelValue, value])
  }
  draft.value = ''
}

function remove(index: number) {
  const next = [...props.modelValue]
  next.splice(index, 1)
  emit('update:modelValue', next)
}
</script>
