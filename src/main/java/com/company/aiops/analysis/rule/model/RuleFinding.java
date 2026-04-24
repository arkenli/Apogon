package com.company.aiops.analysis.rule.model;

import java.util.List;

/**
 * Deterministic rule finding with evidence links.
 */
public record RuleFinding(
        String type,
        double confidence,
        String evidence,
        List<String> evidenceIds
) {

    public RuleFinding {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
