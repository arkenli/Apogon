package com.company.aiops.analysis.context;

import com.company.aiops.alert.model.AlertEvent;

/**
 * Builds the stable execution context used by provider collection and later analysis phases.
 */
public interface AnalysisContextFactory {

    AnalysisContext create(AlertEvent alertEvent);
}
