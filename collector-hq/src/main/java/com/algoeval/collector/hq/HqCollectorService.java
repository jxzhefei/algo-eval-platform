package com.algoeval.collector.hq;

import com.algoeval.collector.hq.disruptor.HqDisruptorPipeline;
import com.algoeval.collector.hq.plugin.SourcePluginLoader;
import com.algoeval.source.sdk.HqSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 行情采集编排：插件加载 → Disruptor 管道 → 行情源开始推送。
 */
@Service
public class HqCollectorService {

    private static final Logger log = LoggerFactory.getLogger(HqCollectorService.class);

    private final SourcePluginLoader pluginLoader;
    private final HqDisruptorPipeline pipeline;
    private volatile HqSource source;

    public HqCollectorService(SourcePluginLoader pluginLoader, HqDisruptorPipeline pipeline) {
        this.pluginLoader = pluginLoader;
        this.pipeline = pipeline;
    }

    public void start(LocalDate tradeDate, Map<String, String> sourceProps) {
        pipeline.start();
        source = pluginLoader.load(sourceProps);
        source.start(tradeDate, pipeline::publish);
    }

    @PreDestroy
    public void stop() {
        if (source != null) {
            source.stop();
        }
    }
}
