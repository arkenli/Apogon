package com.company.aiops.provider.model;

import java.time.Duration;

/**
 * Unified provider result wrapper used by the orchestrator to track data, latency, and
 * failure/skip reasons without leaking transport details into later analysis stages.
 */
public record ProviderResult<T>(
        String providerName,
        ProviderStatus status,
        T data,
        String errorCode,
        String errorMessage,
        Duration latency
) {

    public static <T> ProviderResult<T> success(String providerName, T data, Duration latency) {
        return new ProviderResult<>(providerName, ProviderStatus.SUCCESS, data, null, null, latency);
    }

    public static <T> ProviderResult<T> partialSuccess(
            String providerName,
            T data,
            String errorCode,
            String errorMessage,
            Duration latency
    ) {
        return new ProviderResult<>(
                providerName,
                ProviderStatus.PARTIAL_SUCCESS,
                data,
                errorCode,
                errorMessage,
                latency
        );
    }

    public static <T> ProviderResult<T> failure(
            String providerName,
            String errorCode,
            String errorMessage,
            Duration latency
    ) {
        return new ProviderResult<>(providerName, ProviderStatus.FAILED, null, errorCode, errorMessage, latency);
    }

    public static <T> ProviderResult<T> timeout(
            String providerName,
            String errorMessage,
            Duration latency
    ) {
        return new ProviderResult<>(providerName, ProviderStatus.TIMEOUT, null, "TIMEOUT", errorMessage, latency);
    }

    public static <T> ProviderResult<T> skipped(String providerName, String errorMessage) {
        return new ProviderResult<>(
                providerName,
                ProviderStatus.SKIPPED,
                null,
                "SKIPPED",
                errorMessage,
                Duration.ZERO
        );
    }

    /**
     * Success and partial-success both mean later stages can consume the returned data.
     */
    public boolean isUsable() {
        return status == ProviderStatus.SUCCESS || status == ProviderStatus.PARTIAL_SUCCESS;
    }

    public boolean isFailure() {
        return status == ProviderStatus.FAILED || status == ProviderStatus.TIMEOUT;
    }
}
