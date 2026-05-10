package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.DcsMetrics;

import java.util.List;

/**
 * Retrieves DCS or cache metrics for the mapped resource ids within the alert window.
 */
public interface DcsMetricProvider {

    DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window);
}
