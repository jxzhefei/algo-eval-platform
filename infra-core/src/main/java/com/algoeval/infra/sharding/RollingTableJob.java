package com.algoeval.infra.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 分表滚动预建任务：每日 00:05 预建未来物理表（盘前建表语义）。
 * 独立组件注入 rawDataSource——ShardingSphere 规则在重启后纳入新表，窗口冗余覆盖。
 */
@Component
public class RollingTableJob {

    private static final Logger log = LoggerFactory.getLogger(RollingTableJob.class);

    private final DataSource rawDataSource;

    public RollingTableJob(RawDataSourceHolder holder) {
        this.rawDataSource = holder.dataSource();
    }

    @Scheduled(cron = "0 5 0 * * ?")
    public void preCreateForwardTables() {
        preCreate(LocalDate.now().plusDays(31));
    }

    void preCreate(LocalDate forward) {
        try {
            List<String> ddl = ShardedTables.SHARDED.stream()
                    .map(l -> String.format(ShardedTables.ddl(l), ShardedTables.physicalName(l, forward)))
                    .collect(Collectors.toList());
            try (Connection conn = rawDataSource.getConnection(); Statement st = conn.createStatement()) {
                for (String sql : ddl) {
                    st.execute(sql);
                }
            }
            log.info("[infra] 已预建 {} 物理表", forward);
        } catch (Exception e) {
            log.warn("[infra] 预建表失败（不影响运行）: {}", e.getMessage());
        }
    }
}
