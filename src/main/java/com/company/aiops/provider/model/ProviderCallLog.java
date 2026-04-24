package com.company.aiops.provider.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Persistent audit model for one provider invocation. A call log is recorded for success, failure,
 * timeout, and skip so slow or missing evidence can be traced back to the exact data source.
 */
public record ProviderCallLog(
        String taskId,
        String alertId,
        String providerName,
        ProviderStatus providerStatus,
        boolean critical,
        Duration latency,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {
}
