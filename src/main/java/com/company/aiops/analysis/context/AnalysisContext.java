package com.company.aiops.analysis.context;

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

    /**
     * Replaces the resolved resource mapping after the provider step without rebuilding the rest of
     * the execution context.
     */
    public AnalysisContext withResourceMapping(ResourceMapping nextResourceMapping) {
        return new AnalysisContext(alert, alarmWindow, baselineWindow, nextResourceMapping, options);
    }
}
