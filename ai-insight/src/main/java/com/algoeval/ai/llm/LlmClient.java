package com.algoeval.ai.llm;

/**
 * 大模型客户端契约（AI 原生化的私有化边界）。
 * <p>
 * V3 设计约束：券商内网出不去，AI 能力全部私有化——本接口是唯一的 LLM 出口，
 * 默认零依赖轨道下 {@link #available()} 返回 false，全链路降级为本地规则引擎
 * （结构化数据 → 受限模板生成），功能不缺失、行为可解释。
 * 配置私有化 LLM 端点（Qwen/DeepSeek 蒸馏版，OpenAI 兼容协议）后自动升级为真 LLM 生成。
 */
public interface LlmClient {

    /** 是否已接入可用 LLM（未配置私有端点时为 false → 走本地受限生成） */
    boolean available();

    /**
     * 受限补全：system 约束生成边界，user 携带结构化数据。
     * 实现方负责超时与异常吞掉（AI 是增强不是依赖，LLM 故障不允许影响主链路）。
     *
     * @return 生成文本；不可用/失败时返回 null（调用方降级本地模板）
     */
    String complete(String system, String user);
}
