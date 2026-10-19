package com.algoeval.offline.write;

import com.algoeval.offline.dao.ResultDao;
import com.algoeval.offline.domain.IndicatorResultRow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果写入器：幂等 upsert（INSERT ... ON DUPLICATE KEY UPDATE / H2 MERGE）。
 */
@Component
public class ResultWriter {

    private final ResultDao resultDao;

    public ResultWriter(ResultDao resultDao) {
        this.resultDao = resultDao;
    }

    public void upsertBatch(List<IndicatorResultRow> rows) {
        if (!rows.isEmpty()) {
            resultDao.batchUpsert(rows);
        }
    }

    /** 分片内各母单的当前计算版本（重算时 +1，批量一次查出） */
    public Map<Long, Long> currentVersions(LocalDate tradeDate, List<Long> orderIds) {
        return new HashMap<>(resultDao.loadCalcVersions(tradeDate, orderIds));
    }
}
