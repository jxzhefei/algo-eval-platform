package com.algoeval.source.sdk;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 行情源 SPI：真实系统对接 TCP/JeroMQ 行情协议，demo 由内置生成器实现。
 * 插件化契约——collector-hq 的 SourcePluginLoader 通过 classpath 或外部 jar
 * （URLClassLoader + SPI 扫描）加载实现类，换 jar 不换代码即可切换行情源。
 */
public interface HqSource {

    /** 行情源名称，用于日志与监控 */
    String name();

    /**
     * 注入配置参数（如标的数量、播放节奏、随机种子）。
     * 插件加载后、{@link #start} 之前调用。
     */
    default void configure(Map<String, String> props) {
    }

    /**
     * 启动行情推送。
     *
     * @param tradeDate 模拟交易日
     * @param consumer  tick 消费入口（接入 Disruptor RingBuffer）
     */
    void start(LocalDate tradeDate, Consumer<TickEvent> consumer);

    /** 停止推送（收盘/关闭） */
    void stop();
}
