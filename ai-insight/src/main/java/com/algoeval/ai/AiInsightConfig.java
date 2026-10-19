package com.algoeval.ai;

import com.algoeval.ai.llm.LlmClient;
import com.algoeval.ai.llm.OpenAiCompatLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 能力装配：配置了私有 LLM 端点则接入真 LLM；否则不注册（available()=false 的降级语义
 * 由各服务内联处理，主链路永不依赖 LLM 可用性）。
 */
@Configuration
@EnableConfigurationProperties(AiInsightProperties.class)
public class AiInsightConfig {

    @Bean
    public LlmClient llmClient(AiInsightProperties props) {
        if (props.getLlm().getBaseUrl() != null && !props.getLlm().getBaseUrl().isBlank()) {
            return new OpenAiCompatLlmClient(props.getLlm());
        }
        return new com.algoeval.ai.llm.UnavailableLlmClient();
    }
}
