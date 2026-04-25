package com.company.aiops.llm.service;

import com.company.aiops.llm.client.LlmCallStatus;

/**
 * Propagates the normalized LLM failure reason from the stage-8 service back to the analysis
 * worker so the worker can decide whether to fail the task or switch to rule fallback.
 */
public class LlmAnalysisException extends RuntimeException {

    private final LlmCallStatus status;

    public LlmAnalysisException(LlmCallStatus status, String message) {
        super(message);
        this.status = status;
    }

    public LlmCallStatus status() {
        return status;
    }
}
