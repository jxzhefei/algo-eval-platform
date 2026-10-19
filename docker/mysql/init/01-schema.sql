-- ═══ 全量轨道 MySQL 初始化（docker-entrypoint-initdb.d 自动执行）═══
-- 业务表由应用启动时统一创建（infra-core ShardedTables：分表按交易日窗口预建、单表直建），
-- 此处只负责 Seata AT undo_log（Seata 组件自身的表，不随业务 schema 演进）。

USE algodb;

-- ═══ Seata AT undo_log（全局事务回滚的基石）═══
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';
