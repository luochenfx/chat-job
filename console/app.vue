<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <strong>chat-job</strong>
        <!-- 这里列的是「有哪些平台」，不是「当前在看哪个」：全站没有当前平台的概念，
             各页自己带切换器（位置跟着作用域走） -->
        <span>{{ subtitle }}</span>
      </div>
      <NuxtLink
        v-for="item in nav"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: route.path === item.to }"
      >
        {{ item.label }}
      </NuxtLink>
    </aside>
    <main class="main">
      <NuxtPage />
    </main>
    <div v-if="toast.text" class="toast" :class="{ error: toast.error }">{{ toast.text }}</div>
  </div>
</template>

<script setup lang="ts">
const route = useRoute()
const { toast } = useToast()
const { ensure, platforms } = usePlatform()

// 平台清单来自服务端（实际装配了适配器的那些），失败时保持空串，不阻塞页面
onMounted(() => {
  ensure().catch(() => {})
})

const subtitle = computed(() => {
  const names = platforms.value.map((p) => p.label)
  if (!names.length) return '自动投递简历'
  if (names.length === 1) return `${names[0]} 自动投递`
  return names.join(' · ')
})

const nav = [
  { to: '/', label: '概览' },
  { to: '/task', label: '任务' },
  { to: '/jobs', label: '岗位' },
  { to: '/config', label: '配置' },
  { to: '/system', label: '系统' }
]
</script>
