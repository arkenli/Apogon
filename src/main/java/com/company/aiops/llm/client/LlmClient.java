package com.company.aiops.llm.client;

/**
 * Abstracts one model-completion provider.
 *
 * <p>The worker and LLM orchestration service depend on this interface so stage 8 can swap between
 * mock, OpenAI-compatible, or later internal adapters without changing task lifecycle code.</p>
 */
public interface LlmClient {

    /**
     * Executes one completion request and returns the raw model response.
     */
    LlmResponse complete(LlmRequest request);
}
