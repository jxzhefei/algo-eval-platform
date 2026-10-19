package com.algoeval.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能算法评价平台 all-in-one 启动器（真实中间件轨道）。
 * 前置：docker compose up -d（MySQL/Kafka/Redis/Nacos/ClickHouse/Seata）。
 */
@SpringBootApplication(scanBasePackages = "com.algoeval")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
