package com.algoeval.platform.sim;

import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 运维触发端点（对齐真实系统"盘后计算运维入口"）：
 * 手动重触发离线计算（幂等，可反复执行）与 CK 同步对账——生产用于补算/对账，验证用于演示。
 */
@RestController
@RequestMapping("/api/sim")
public class SimOpsController {

    private final SimulationOrchestrator orchestrator;

    public SimOpsController(@Lazy SimulationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("tradeDate", String.valueOf(orchestrator.tradeDate()),
                "lastCalcReport", String.valueOf(orchestrator.lastCalcReport()));
    }

    /** 手动重触发盘后离线计算（幂等重算：指标 upsert 覆盖同 key） */
    @PostMapping("/recalc")
    public Map<String, Object> recalc(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? orchestrator.tradeDate() : LocalDate.parse(date);
        orchestrator.runOfflineCalculation(d);
        return Map.of("triggered", String.valueOf(d));
    }

    /** MySQL→ClickHouse 同步 + 对账（手动补跑） */
    @PostMapping("/ck-sync")
    public Map<String, Object> ckSync(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? orchestrator.tradeDate() : LocalDate.parse(date);
        return orchestrator.syncClickHouse(d);
    }
}
