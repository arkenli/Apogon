package com.company.aiops.analysis.model;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.provider.model.ResourceMapping;

/**
 * Carries the alert, resolved resources, and runtime options used by one analysis execution.
 */
public record AnalysisContext(
        AlertEvent alert,
        TimeWindow alarmWindow,
        TimeWindow baselineWindow,
        ResourceMapping resourceMapping,
        AnalysisOptions options
) {
}
