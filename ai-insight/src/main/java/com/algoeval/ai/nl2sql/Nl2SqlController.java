package com.algoeval.ai.nl2sql;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对话式查数 API（NL2SQL over 评价库）。
 * <ul>
 *   <li>POST /api/ai/nlquery    自然语言 → 白名单校验 SQL → 执行 → 图表数据</li>
 *   <li>GET  /api/ai/nlquery/examples  示例问题（前端引导）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class Nl2SqlController {

    private final NlQueryEngine queryEngine;
    private final SqlGuard sqlGuard;

    public Nl2SqlController(NlQueryEngine queryEngine, SqlGuard sqlGuard) {
        this.queryEngine = queryEngine;
        this.sqlGuard = sqlGuard;
    }

    public record NlQueryRequest(String question) {
    }

    @PostMapping("/nlquery")
    public ResponseEntity<?> ask(@RequestBody NlQueryRequest req) {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 必填"));
        }
        try {
            return ResponseEntity.ok(queryEngine.ask(req.question().trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (org.springframework.dao.DataAccessException e) {
            return ResponseEntity.status(500).body(Map.of("error", "SQL 执行失败：" + e.getMostSpecificCause().getMessage()));
        }
    }

    @GetMapping("/nlquery/examples")
    public Map<String, Object> examples() {
        return Map.of("examples", queryEngine.examples(),
                "whitelistedTables", sqlGuard.whitelistedTables());
    }
}
