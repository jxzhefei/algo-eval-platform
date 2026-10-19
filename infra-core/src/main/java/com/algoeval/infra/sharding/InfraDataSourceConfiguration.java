package com.algoeval.infra.sharding;

import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 数据源装配（对齐设计文档 §6.3 按交易日分表）：
 * <ul>
 *   <li>rawDataSource：直连数据库，仅供建表/运维 DDL 使用</li>
 *   <li>dataSource（Primary）：ShardingSphere-JDBC 分表数据源，路由键 trade_date，
 *       自定义 DateShardingAlgorithm → {逻辑表}_{yyyyMMdd}；未分表语句透传单表</li>
 *   <li>应用启动时预建交易日窗口内的物理表（盘前预建表语义），并每日滚动补建</li>
 * </ul>
 * 注意：应用全部 SQL 均写逻辑表名；非分表上下文（单测）由各自 schema 直接建逻辑表。
 */
@Configuration
public class InfraDataSourceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InfraDataSourceConfiguration.class);

    /** 直连数据源持有者（建表/运维 DDL 专用；不以 DataSource 类型暴露，避开 Seata 自动代理） */
    @Bean
    public RawDataSourceHolder rawDataSourceHolder(DataSourceProperties properties) {
        return new RawDataSourceHolder(properties.initializeDataSourceBuilder().build());
    }

    /** 直连 JdbcTemplate（分析查询通道）：绕开 ShardingSphere 的 GROUP BY 重写——
     *  跨物理分表的聚合分析在 SS 上有损，分析类查询按物理表名直查（生产对位 ClickHouse 分析库） */
    @Bean(name = "rawJdbcTemplate")
    public org.springframework.jdbc.core.JdbcTemplate rawJdbcTemplate(RawDataSourceHolder holder) {
        return new org.springframework.jdbc.core.JdbcTemplate(holder.dataSource());
    }

    @Bean
    @Primary
    public DataSource dataSource(RawDataSourceHolder holder,
                                 @Value("${algo.sharding.back-days:7}") int backDays,
                                 @Value("${algo.sharding.forward-days:30}") int forwardDays) throws Exception {
        DataSource raw = holder.dataSource();
        createSingleTables(raw);
        List<LocalDate> window = createPhysicalTables(raw, backDays, forwardDays);
        DataSource sharding = buildShardingDataSource(raw, window);
        log.info("[infra] ShardingSphere 分表数据源就绪：{} 逻辑表 × {} 天窗口（{} ~ {}）",
                ShardedTables.SHARDED.size(), window.size(), window.get(0), window.get(window.size() - 1));
        return sharding;
    }

    private DataSource buildShardingDataSource(DataSource raw, List<LocalDate> window) throws Exception {
        Map<String, DataSource> dataSourceMap = new LinkedHashMap<>();
        dataSourceMap.put("ds0", raw);

        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();
        Properties algoProps = new Properties();
        algoProps.setProperty("strategy", "STANDARD");
        algoProps.setProperty("algorithmClassName", DateShardingAlgorithm.class.getName());
        rule.getShardingAlgorithms().put("date-suffix", new AlgorithmConfiguration("CLASS_BASED", algoProps));
        for (String logical : ShardedTables.SHARDED) {
            String actualDataNodes = window.stream()
                    .map(d -> "ds0." + ShardedTables.physicalName(logical, d))
                    .collect(Collectors.joining(","));
            ShardingTableRuleConfiguration tableRule =
                    new ShardingTableRuleConfiguration(logical, actualDataNodes);
            // 表级分片策略（单库多表：路由到 {逻辑表}_{yyyyMMdd}；库级无分片）
            tableRule.setTableShardingStrategy(
                    new StandardShardingStrategyConfiguration("trade_date", "date-suffix"));
            rule.getTables().add(tableRule);
        }
        // 单表静态注册（显式声明路由节点，不依赖运行时元数据发现——
        // H2 兼容模式下 SS 的单表元数据扫描不可靠，静态规则在任何方言下行为一致）
        for (String single : ShardedTables.SINGLE) {
            rule.getTables().add(new ShardingTableRuleConfiguration(single, "ds0." + single));
        }
        Properties props = new Properties();
        props.setProperty("sql-show", "false");
        // 无 Mode 重载：默认 Standalone 内存元数据（无外部持久化仓库，私有化单机调度场景足够）
        return ShardingSphereDataSourceFactory.createDataSource(
                "algo_sharding", dataSourceMap, List.of(rule), props);
    }

    private void createSingleTables(DataSource raw) {
        executeDdl(raw, ShardedTables.SINGLE.stream()
                .map(ShardedTables::ddl)
                .collect(Collectors.toList()));
    }

    /** 分表开启：按窗口预建物理表（盘前建表语义；窗口覆盖 +30 天，滚动任务每日补建） */
    private List<LocalDate> createPhysicalTables(DataSource raw, int backDays, int forwardDays) {
        LocalDate today = LocalDate.now();
        List<LocalDate> window = new ArrayList<>();
        for (int i = -backDays; i <= forwardDays; i++) {
            window.add(today.plusDays(i));
        }
        List<String> ddl = new ArrayList<>();
        for (LocalDate date : window) {
            for (String logical : ShardedTables.SHARDED) {
                ddl.add(String.format(ShardedTables.ddl(logical),
                        ShardedTables.physicalName(logical, date)));
            }
        }
        executeDdl(raw, ddl);
        return window;
    }

    /** 分表关闭（单测/极简部署）：直接建不带后缀的逻辑表 */

    private void executeDdl(DataSource raw, List<String> statements) {
        try (Connection conn = raw.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("建表失败: " + e.getMessage(), e);
        }
    }
}
