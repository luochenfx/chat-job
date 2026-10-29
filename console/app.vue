<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <strong>chat-job</strong>
        <span>BOSS 投递自动化 · 本机</span>
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

const nav = [
  { to: '/', label: '概览' },
  { to: '/task', label: '任务' },
  { to: '/jobs', label: '岗位' },
  { to: '/config', label: '配置' },
  { to: '/system', label: '系统' }
]
</script>
