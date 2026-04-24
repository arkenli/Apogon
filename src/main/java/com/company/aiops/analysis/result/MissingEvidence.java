package com.company.aiops.analysis.result;

/**
 * Captures a provider gap so degraded conclusions remain auditable.
 */
public record MissingEvidence(
        String providerName,
        String reason,
        boolean critical,
        String impact
) {
}
