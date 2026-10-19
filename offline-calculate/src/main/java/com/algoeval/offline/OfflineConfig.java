package com.algoeval.offline;

import com.algoeval.source.sdk.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration
public class OfflineConfig {

    private static final Logger log = LoggerFactory.getLogger(OfflineConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public SqlDialect sqlDialect(DataSource dataSource) {
        SqlDialect dialect = SqlDialect.detect(dataSource);
        log.info("[offline-calculate] 数据库方言: {}", dialect.isMysql() ? "MySQL" : "H2(MySQL兼容模式)");
        return dialect;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }
}
