package com.algoeval.storer;

import com.algoeval.source.sdk.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class StorerConfig {

    private static final Logger log = LoggerFactory.getLogger(StorerConfig.class);

    /** upsert 方言：生产走 MySQL（ShardingSphere 亦按 MySQL 语法解析），统一 ON DUPLICATE KEY */
    @Bean
    @ConditionalOnMissingBean(SqlDialect.class)
    public SqlDialect sqlDialect(DataSource dataSource) {
        SqlDialect dialect = SqlDialect.detect(dataSource);
        log.info("[data-storer] upsert 方言: {}", dialect.isMysql() ? "MySQL(ON DUPLICATE KEY)" : "H2(MERGE INTO)");
        return dialect;
    }
}
