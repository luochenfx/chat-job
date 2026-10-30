package com.chatjob;

import com.chatjob.config.AppProperties;
import com.chatjob.config.ProjectHome;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@MapperScan("com.chatjob.store.mapper")
public class ChatJobApplication {

    /**
     * 在 Spring 启动之前钉死项目根，供 application.yaml 的 {@code ${chat-job.home}} 展开。
     *
     * <p>为什么不用 EnvironmentPostProcessor：那条路的生效依赖「注册文件被正确打包」与
     * 「执行顺序早于 config data」，两者失效时都<b>静默</b>——实测就落回了 cwd，数据又一次
     * 写进 server/data，而日志里没有任何提示。类静态初始化没有这些不确定性：入口只有
     * main 与 {@code @SpringBootTest}，两者都会在创建 Environment 之前加载本类。
     */
    static {
        System.setProperty(ProjectHome.PROPERTY, ProjectHome.resolve().toString());
    }

    public static void main(String[] args) {
        SpringApplication.run(ChatJobApplication.class, args);
    }
}


