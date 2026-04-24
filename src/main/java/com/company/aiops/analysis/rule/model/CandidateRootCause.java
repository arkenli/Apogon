package com.company.aiops.analysis.rule.model;

import java.util.List;

/**
 * Candidate root-cause score derived from deterministic rule evidence.
 */
public record CandidateRootCause(
        String causeType,
        int score,
        double confidence,
        List<String> evidenceIds,
        String reasoning
) {

    public CandidateRootCause {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
