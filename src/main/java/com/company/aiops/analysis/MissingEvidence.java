package com.company.aiops.analysis;

public record MissingEvidence(
        String providerName,
        String reason,
        boolean critical,
        String impact
) {
}
