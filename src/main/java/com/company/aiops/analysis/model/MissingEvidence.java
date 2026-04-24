package com.company.aiops.analysis.model;

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
