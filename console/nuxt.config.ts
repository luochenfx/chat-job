export default defineNuxtConfig({
  // SPA 模式：产物是一堆静态文件，直接丢给 Spring Boot 托管，不需要 Node 运行时
  ssr: false,
  devtools: { enabled: false },
  app: {
    head: {
      title: 'chat-job 控制台',
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }]
    }
  },
  css: ['~/assets/css/main.css'],
  nitro: { preset: 'static' },
  devServer: { port: 3000 },
  vite: {
    server: {
      // 开发期把 /api 转给 Spring Boot，避免每次都要先打一次包
      proxy: {
        '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, ws: false }
      }
    }
  }
})
