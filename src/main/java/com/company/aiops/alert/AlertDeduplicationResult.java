package com.company.aiops.alert;

import com.company.aiops.analysis.AnalysisJob;
import com.company.aiops.analysis.HistoricalReference;

import java.util.Optional;

public record AlertDeduplicationResult(
        Optional<AnalysisJob> duplicateTask,
        Optional<HistoricalReference> historicalReference
) {
}
