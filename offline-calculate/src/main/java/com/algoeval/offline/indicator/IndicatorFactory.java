package com.algoeval.offline.indicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指标工厂：Spring 自动收集所有 IndicatorStrategy 实现注入 List。
 * 新增指标 = 新增一个 @Component 实现类，主流程零改动。
 */
@Component
public class IndicatorFactory {

    private static final Logger log = LoggerFactory.getLogger(IndicatorFactory.class);

    private final List<IndicatorStrategy> strategies;

    public IndicatorFactory(List<IndicatorStrategy> strategies) {
        this.strategies = strategies;
        log.info("[offline-calculate] 指标工厂注册 {} 个策略: {}",
                strategies.size(), strategies.stream().map(IndicatorStrategy::name).toList());
    }

    public List<IndicatorStrategy> all() {
        return strategies;
    }
}
