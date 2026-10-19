package com.algoeval.offline;

import com.algoeval.offline.node.OfflineCalcOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDate;

/**
 * 离线计算独立启动器：可连同一 DB 启动多个实例，真实演示多节点抢占。
 * <p>
 * 用法：java -jar offline-calculate.jar --algo.run.date=2026-10-18 --algo.run.nodes=3
 * （full profile 下连 MySQL，多进程全对等抢占同一任务表）
 */
@SpringBootApplication
public class OfflineCalculateApplication {

    private static final Logger log = LoggerFactory.getLogger(OfflineCalculateApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(OfflineCalculateApplication.class);
        // 独立 profile：避免模块 jar 内的默认 application.yml 与 platform-app 配置互相污染
        app.setAdditionalProfiles("offlinestd");
        ConfigurableApplicationContext context = app.run(args);
        var env = context.getEnvironment();
        LocalDate date = LocalDate.parse(env.getProperty("algo.run.date", LocalDate.now().toString()));
        int nodes = env.getProperty("algo.run.nodes", Integer.class, 3);
        int shards = env.getProperty("algo.run.shards", Integer.class, 32);
        log.info("[offline-standalone] 启动离线计算: date={} nodes={} shards={}", date, nodes, shards);
        context.getBean(OfflineCalcOrchestrator.class).run(date, shards, nodes);
        log.info("[offline-standalone] 计算完成，进程退出");
        context.close();
        System.exit(0);
    }
}
