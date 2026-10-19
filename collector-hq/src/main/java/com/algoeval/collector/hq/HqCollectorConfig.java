package com.algoeval.collector.hq;

import com.algoeval.source.sdk.MarketDataCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HqCollectorConfig {

    @Bean
    public MarketDataCache marketDataCache() {
        return new MarketDataCache();
    }
}
