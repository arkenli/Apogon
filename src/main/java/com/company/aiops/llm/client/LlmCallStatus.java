package com.company.aiops.llm.client;

/**
 * Canonical status values reused by runtime results, fallback routing, and LLM call audit logs.
 */
public enum LlmCallStatus {
    SUCCESS,
    TIMEOUT,
    UNAVAILABLE,
    FAILED,
    INVALID_JSON,
    SCHEMA_VALIDATION_FAILED,
    INVALID_EVIDENCE_REFERENCE
}
