package com.company.aiops.analysis.result;

import java.util.List;

/**
 * Candidate root cause with confidence and evidence links.
 */
public record RootCauseHypothesis(
        String causeType,
        String description,
        double confidence,
        List<String> evidenceIds,
        String source,
        String reasoning
) {
}
