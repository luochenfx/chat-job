package com.chatjob.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 控制台静态资源托管。
 *
 * <p>单 jar + 静态产物：双击一个 bat 就能跑，比"先起后端再起 nuxt dev"现实得多。
 * Nuxt 的构建产物落在 {@code classpath:/public/}，找不到的路径回落到 index.html（SPA 路由）。
 *
 * <p>CORS 只放开本机开发端口，不做 {@code *} —— 参考项目那条 {@code @CrossOrigin("*")} +
 * 明文返回 API Key 的组合是个反面教材。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/public/", "classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 接口路径不参与回落，否则前端会拿到一段 HTML 而不是 404
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("/public/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://127.0.0.1:3000", "http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
