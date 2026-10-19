package com.algoeval.recommender;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 智能路由推荐 API（对应真实系统菜单「智能推荐」入口）。
 * <ul>
 *   <li>POST /api/recommend/route     新母单路由决策（可解释推荐）</li>
 *   <li>GET  /api/recommend/matrix    算法能力画像矩阵</li>
 *   <li>GET  /api/recommend/feedback  闭环回流：推荐质量校准报告</li>
 *   <li>GET  /api/recommend/symbols   可路由标的清单（前端下拉）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    private final RoutingDecisionService routingService;
    private final CapabilityMatrixService matrixService;
    private final RouteDecisionDao decisionDao;

    public RecommendController(RoutingDecisionService routingService,
                               CapabilityMatrixService matrixService,
                               RouteDecisionDao decisionDao) {
        this.routingService = routingService;
        this.matrixService = matrixService;
        this.decisionDao = decisionDao;
    }

    /** 路由请求体 */
    public record RouteRequest(String symbol, BigDecimal amount, String side, String date) {
    }

    /** 新母单路由：输出推荐算法 + 理由 + 备选，并落决策表 */
    @PostMapping("/route")
    public ResponseEntity<?> route(@RequestBody RouteRequest req) {
        if (req.symbol() == null || req.symbol().isBlank() || req.amount() == null
                || req.amount().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 与 amount(>0) 必填"));
        }
        String side = req.side() == null ? "BUY" : req.side().toUpperCase();
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            return ResponseEntity.badRequest().body(Map.of("error", "side 仅支持 BUY/SELL"));
        }
        LocalDate date = req.date() == null || req.date().isBlank() ? LocalDate.now() : LocalDate.parse(req.date());
        try {
            return ResponseEntity.ok(routingService.route(req.symbol().trim(), req.amount(), side, date));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    /** 算法能力画像矩阵（近 lookback 天） */
    @GetMapping("/matrix")
    public Map<String, Object> matrix(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        LocalDate fromDate = from == null || from.isBlank() ? toDate.minusDays(90) : LocalDate.parse(from);
        return routingService.matrixView(fromDate, toDate);
    }

    /** 闭环回流报告：当日决策 vs 实际评价结果（命中率 / 期望误差） */
    @GetMapping("/feedback")
    public RouteDecisionDao.FeedbackReport feedback(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return decisionDao.evaluate(d);
    }

    /** 候选算法实例清单 */
    @GetMapping("/algos")
    public List<String> algos() {
        return matrixService.algoInstances();
    }

    /** 可路由标的（取当日已有评价数据的标的，前端下拉用） */
    @GetMapping("/symbols")
    public List<Map<String, Object>> symbols(@RequestParam(required = false) String date) {
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return matrixService.symbolsWithTurnover(d);
    }
}
