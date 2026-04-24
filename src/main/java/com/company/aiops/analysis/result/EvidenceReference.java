package com.company.aiops.analysis.result;

/**
 * Lightweight index entry that lets downstream consumers locate a concrete evidence item.
 */
public record EvidenceReference(
        String evidenceId,
        String evidenceType,
        String description
) {
}
