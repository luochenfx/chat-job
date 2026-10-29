package com.chatjob;

import com.chatjob.config.AppProperties;
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

    public static void main(String[] args) {
        SpringApplication.run(ChatJobApplication.class, args);
    }
}
