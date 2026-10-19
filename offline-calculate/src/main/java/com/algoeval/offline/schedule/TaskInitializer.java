package com.algoeval.offline.schedule;

import com.algoeval.offline.dao.TaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 任务初始化（幂等，无需协调）：没有任何节点"负责"初始化，
 * 所有节点都尝试初始化，主键唯一约束兜底——消除了"谁来做初始化"的协调问题。
 */
@Component
public class TaskInitializer {

    private static final Logger log = LoggerFactory.getLogger(TaskInitializer.class);

    private final TaskDao taskDao;

    public TaskInitializer(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    /** @return 新创建的分片数（已存在的被幂等忽略） */
    public int initialize(LocalDate tradeDate, int shardCount) {
        int created = 0;
        for (int shard = 0; shard < shardCount; shard++) {
            if (taskDao.insertIgnore(tradeDate, shard)) {
                created++;
            }
        }
        log.info("[offline-calculate] 任务初始化: {} 个逻辑分片，新建 {} 个（交易日 {}）", shardCount, created, tradeDate);
        return created;
    }
}
