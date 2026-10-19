package com.algoeval.offline.tx;

import org.apache.seata.rm.datasource.DataSourceProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seata 数据源代理手工装配（旗舰档，seata.enabled=true 时激活）：
 * ShardingSphereDataSource 为 final 类，Seata 默认的 CGLIB 自动代理无法子类化——
 * 改用组合式 DataSourceProxy 包装（委托而非继承），@GlobalTransactional 的 AT 分支
 * （undo_log）经代理连接生效。
 * 前置：seata.enable-auto-data-source-proxy 必须为 false（关闭自动代理，由本配置接管）。
 */
@Configuration
@ConditionalOnProperty(name = "seata.enabled", havingValue = "true")
public class SeataDataSourceProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(SeataDataSourceProxyConfig.class);

    @Bean
    public static BeanPostProcessor seataManualDataSourceProxy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof javax.sql.DataSource ds
                        && !(bean instanceof DataSourceProxy)) {
                    log.info("[offline-calculate] Seata 组合式 DataSourceProxy 已包装主数据源（AT 模式）");
                    return new DataSourceProxy(ds);
                }
                return bean;
            }
        };
    }
}
