package com.company.aiops.llm.client.mock;

/**
 * Service-name markers that let tests drive mock-LLM success, retry, and fallback branches without
 * replacing Spring beans.
 */
public final class MockLlmTokens {

    public static final String TIMEOUT = "mock-llm-timeout";
    public static final String INVALID_JSON = "mock-llm-invalid-json";
    public static final String INVALID_SCHEMA = "mock-llm-invalid-schema";
    public static final String INVALID_EVIDENCE = "mock-llm-invalid-evidence";
    public static final String RETRY_SUCCESS_INVALID_JSON = "mock-llm-retry-success-invalid-json";

    private MockLlmTokens() {
    }
}
