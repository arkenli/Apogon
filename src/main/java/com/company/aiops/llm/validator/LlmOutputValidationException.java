package com.company.aiops.llm.validator;

import com.company.aiops.llm.client.LlmCallStatus;

/**
 * Raised when a raw LLM response fails JSON/schema/evidence validation.
 */
public class LlmOutputValidationException extends RuntimeException {

    private final LlmCallStatus status;

    public LlmOutputValidationException(LlmCallStatus status, String message) {
        super(message);
        this.status = status;
    }

    public LlmCallStatus status() {
        return status;
    }
}
