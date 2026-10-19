package com.algoeval.ai.llm;

/**
 * 未接入 LLM 的降级实现：默认零依赖轨道。
 * 所有 AI 能力（解读报告/NL2SQL）在该轨道下由本地规则引擎完成"结构化数据 → 受限生成"，
 * 输出确定性文本——功能等价、可解释、可审计。
 */
public class UnavailableLlmClient implements LlmClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String complete(String system, String user) {
        return null;
    }
}
