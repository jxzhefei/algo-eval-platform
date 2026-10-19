package com.algoeval.ai.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 解读报告 API：结构化画像 → 归因分析 → 受限生成叙事。
 */
@RestController
@RequestMapping("/api/ai")
public class AiReportController {

    private final AiReportService reportService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public AiReportController(AiReportService reportService,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.reportService = reportService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** AI 解读报告（可解释：narrative 之外的每个数字都可回溯到母单级指标） */
    @GetMapping("/report/{instance}")
    public Map<String, Object> report(@PathVariable String instance,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null || from.isBlank() ? toDate.minusDays(90) : LocalDate.parse(from);
        return reportService.report(instance, fromDate, toDate);
    }

    /** 可解读的算法实例清单 */
    @GetMapping("/instances")
    public List<Map<String, Object>> instances() {
        return jdbcTemplate.queryForList(
                "SELECT algo_instance, MAX(trade_date) AS last_date FROM algo_daily_summary "
                        + "GROUP BY algo_instance ORDER BY algo_instance");
    }
}
