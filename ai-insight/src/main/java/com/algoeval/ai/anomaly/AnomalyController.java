package com.algoeval.ai.anomaly;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 绩效离群母单检测 API（对应真实系统「离群值分析」页的自动化升级）。
 */
@RestController
@RequestMapping("/api/anomaly")
public class AnomalyController {

    private final AnomalyDetector detector;

    public AnomalyController(AnomalyDetector detector) {
        this.detector = detector;
    }

    /** 离群母单：MAD 稳健 Z 分数多维检测，按异常分降序 */
    @GetMapping("/outliers")
    public AnomalyDetector.AnomalyReport outliers(@RequestParam(required = false) String date,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String algo) {
        LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate;
        if (from != null && !from.isBlank()) {
            fromDate = LocalDate.parse(from);
        } else {
            fromDate = date == null || date.isBlank() ? toDate.minusDays(90) : LocalDate.parse(date);
        }
        return detector.detect(fromDate, toDate, algo);
    }
}
