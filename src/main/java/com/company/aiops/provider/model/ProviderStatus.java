package com.company.aiops.provider.model;

/**
 * Common execution status returned by provider calls.
 */
public enum ProviderStatus {
    SUCCESS,
    TIMEOUT,
    FAILED,
    PARTIAL_SUCCESS,
    SKIPPED
}
