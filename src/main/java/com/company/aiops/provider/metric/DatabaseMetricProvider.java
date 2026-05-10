package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.DatabaseMetrics;

import java.util.List;

/**
 * Retrieves database instance metrics for the mapped resource ids within the alert window.
 */
public interface DatabaseMetricProvider {

    DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window);
}
