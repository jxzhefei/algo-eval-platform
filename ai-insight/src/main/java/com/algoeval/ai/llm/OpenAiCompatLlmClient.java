package com.algoeval.ai.llm;

import com.algoeval.ai.AiInsightProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议客户端：对接私有化部署的 Qwen / DeepSeek 蒸馏版（vLLM/Ollama 均兼容该协议）。
 * <p>
 * 私有化合规边界：只访问配置的 base-url（客户内网），不出网、不留痕。
 * 由 {@link AiInsightConfig} 在 algo.ai.llm.base-url 配置存在时装配，默认轨道不注册本 Bean。
 */
public class OpenAiCompatLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmClient.class);

    private final AiInsightProperties.Llm props;
    private final RestClient restClient;

    public OpenAiCompatLlmClient(AiInsightProperties.Llm props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()));
                    setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
                }})
                .build();
        log.info("[ai-insight] 私有化 LLM 已接入：{}（model={}）", props.getBaseUrl(), props.getModel());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String complete(String system, String user) {
        try {
            Map<String, Object> body = Map.of(
                    "model", props.getModel(),
                    "temperature", 0.2, // 低温度：受限生成，压幻觉
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)));
            Map<?, ?> resp = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                            h.setBearerAuth(props.getApiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                return null;
            }
            List<?> choices = (List<?>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            Object content = message == null ? null : message.get("content");
            return content == null ? null : content.toString();
        } catch (Exception e) {
            // AI 是增强不是依赖：LLM 故障降级本地模板，不允许影响主链路
            log.warn("[ai-insight] LLM 调用失败，降级本地受限生成：{}", e.getMessage());
            return null;
        }
    }
}
