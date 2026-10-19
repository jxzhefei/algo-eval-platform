package com.algoeval.ingest;

import com.algoeval.ingest.model.ChildOrderMsg;
import com.algoeval.ingest.model.ParentOrderMsg;
import com.algoeval.ingest.model.TradeMsgCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 交易数据接入层（对应真实系统 atx-restful-acco）：
 * 券商侧交易系统/算法网关按此 RESTful 契约推送母单、子单、成交与撤单回报；
 * 接入层完成字段校验与归一化后发布 Kafka 交易 topic。
 * 第三方算法商经 /third-party 通道接入（对应 atx-third-guolian，异构报文归一化）。
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final ThirdPartyNormalizer thirdPartyNormalizer;
    private final String topic;

    public IngestController(KafkaTemplate<String, byte[]> kafkaTemplate,
                            ThirdPartyNormalizer thirdPartyNormalizer,
                            @Value("${algo.kafka.topic.trade:trade-order}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.thirdPartyNormalizer = thirdPartyNormalizer;
        this.topic = topic;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "topic", topic);
    }

    /** 母单上报（算法任务） */
    @PostMapping("/parent")
    public ResponseEntity<?> parent(@RequestBody ParentOrderMsg parent) {
        if (parent.orderId() <= 0 || parent.algoInstance() == null || parent.symbol() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId/algoInstance/symbol 必填"));
        }
        kafkaTemplate.send(topic, "P:" + parent.orderId(), TradeMsgCodec.encodeParent(parent));
        return ResponseEntity.ok(Map.of("accepted", parent.orderId()));
    }

    /** 子单/回报批量上报 */
    @PostMapping("/children")
    public ResponseEntity<?> children(@RequestBody List<ChildOrderMsg> children) {
        if (children == null || children.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "children 不能为空"));
        }
        for (ChildOrderMsg child : children) {
            kafkaTemplate.send(topic, "C:" + child.childId(), TradeMsgCodec.encodeChild(child));
        }
        return ResponseEntity.ok(Map.of("accepted", children.size()));
    }

    /**
     * 第三方算法商母单上报（异构报文 → 平台统一模型归一化）。
     * 报文形态由第三方接入模块定义，字段映射集中在 ThirdPartyNormalizer。
     */
    @PostMapping("/third-party/order")
    public ResponseEntity<?> thirdParty(@RequestBody ThirdPartyNormalizer.ThirdPartyOrder order) {
        ParentOrderMsg normalized = thirdPartyNormalizer.normalize(order);
        kafkaTemplate.send(topic, "P:" + normalized.orderId(), TradeMsgCodec.encodeParent(normalized));
        log.info("[trade-ingest] 第三方母单归一化入库: {} → algo={} vendor={}",
                order.taskNo(), normalized.algoInstance(), normalized.algoVendor());
        return ResponseEntity.ok(Map.of("accepted", normalized.orderId(),
                "algoInstance", normalized.algoInstance()));
    }
}
