package com.algoeval.infra.sharding;

import javax.sql.DataSource;

/**
 * 直连数据源持有者：rawDataSource 不以 javax.sql.DataSource 类型暴露为 Bean——
 * 避免被 Seata 数据源自动代理包装（ShardingSphere 无法从代理对象提取连接属性做元数据装配）。
 * 仅供建表/运维 DDL 使用。
 */
public record RawDataSourceHolder(DataSource dataSource) {
}
