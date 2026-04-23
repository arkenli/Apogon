package com.company.aiops.analysis;

import java.util.List;

public record RootCauseHypothesis(
        String causeType,
        String description,
        double confidence,
        List<String> evidenceIds,
        String source,
        String reasoning
) {
}
