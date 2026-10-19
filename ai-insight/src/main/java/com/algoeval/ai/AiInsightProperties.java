package com.algoeval.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 能力配置（默认全部关闭——零依赖轨道；接入私有 LLM 后解锁真生成）。
 * <pre>
 * algo:
 *   ai:
 *     llm:
 *       base-url: http://10.10.1.31:8000/v1   # 私有化 Qwen/DeepSeek（OpenAI 兼容）
 *       model: qwen2.5-7b-instruct
 *       api-key: ""                            # 内网部署通常无需鉴权
 *       timeout-ms: 15000
 * </pre>
 */
@ConfigurationProperties(prefix = "algo.ai")
public class AiInsightProperties {

    private final Llm llm = new Llm();

    public Llm getLlm() {
        return llm;
    }

    public static class Llm {
        /** 私有化 LLM 端点（OpenAI 兼容）；留空 = 未接入，走本地受限生成 */
        private String baseUrl = "";
        private String model = "qwen2.5-7b-instruct";
        private String apiKey = "";
        private long timeoutMs = 15000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
