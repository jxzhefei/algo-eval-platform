package com.algoeval.collector.trade.engine;

import com.algoeval.ingest.ThirdPartyNormalizer;
import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 模拟交易终端 → 交易接入层（trade-ingest）的 REST 客户端：
 * 模拟算法引擎像真实券商侧系统一样经 RESTful 契约上报母单/子单/回报，
 * 而非直写消息管道——接入边界（归一化/校验/发布）真实生效。
 */
@Component
public class TradeTerminalClient {

    private static final Logger log = LoggerFactory.getLogger(TradeTerminalClient.class);

    private final RestClient rest;

    public TradeTerminalClient(@Value("${algo.sim.ingest-url:http://localhost:8080}") String baseUrl) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void postParent(ParentOrderMsg parent) {
        retry("parent:" + parent.orderId(),
                () -> rest.post().uri("/api/ingest/parent").body(parent).retrieve().toBodilessEntity());
    }

    public void postChildren(List<ChildOrderMsg> children) {
        if (children == null || children.isEmpty()) {
            return;
        }
        retry("children:" + children.size() + "@" + children.get(0).orderId(),
                () -> rest.post().uri("/api/ingest/children").body(children).retrieve().toBodilessEntity());
    }

    /** 第三方算法商通道：异构报文 → 接入层归一化，返回平台侧母单号 */
    public long postThirdParty(ThirdPartyNormalizer.ThirdPartyOrder order) {
        Map<?, ?> body = retry("third-party:" + order.taskNo(), () ->
                rest.post().uri("/api/ingest/third-party/order").body(order)
                        .retrieve().body(Map.class));
        Object accepted = body == null ? null : body.get("accepted");
        return accepted == null ? -1 : ((Number) accepted).longValue();
    }

    private <T> T retry(String what, java.util.function.Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("[trade-terminal] 上报失败（第 {} 次）{}: {}", attempt, what, e.getMessage());
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
