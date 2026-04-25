package com.company.aiops.llm.client;

/**
 * Raised when a transport adapter cannot obtain a usable model response.
 */
public class LlmClientException extends RuntimeException {

    private final LlmCallStatus status;

    public LlmClientException(LlmCallStatus status, String message) {
        super(message);
        this.status = status;
    }

    public LlmClientException(LlmCallStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public LlmCallStatus status() {
        return status;
    }
}
