<template>
  <Teleport to="body">
    <div v-if="open" class="modal-mask" @click.self="close">
      <div class="modal" :style="{ maxWidth: width }" role="dialog" aria-modal="true">
        <div class="modal-head">
          <div>
            <h2>{{ title }}</h2>
            <p v-if="hint">{{ hint }}</p>
          </div>
          <button class="modal-x" type="button" aria-label="关闭" @click="close">×</button>
        </div>
        <div class="modal-body">
          <slot />
        </div>
        <div class="modal-foot">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
// 点遮罩 = 放弃编辑，不做任何保存。@click.self 只在命中遮罩本身时触发，
// 弹层内部（含输入框拖选）不会误关。
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    hint?: string
    width?: string
  }>(),
  { hint: '', width: '560px' }
)

const emit = defineEmits<{ close: [] }>()

function close() {
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      document.addEventListener('keydown', onKeydown)
      document.body.style.overflow = 'hidden'
    } else {
      document.removeEventListener('keydown', onKeydown)
      document.body.style.overflow = ''
    }
  }
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})

// 若挂载时就已经是打开状态，watch 不会触发，这里补一次
onMounted(() => {
  if (props.open) {
    document.addEventListener('keydown', onKeydown)
    document.body.style.overflow = 'hidden'
  }
})
</script>
