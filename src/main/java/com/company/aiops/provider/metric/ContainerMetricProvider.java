package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.ContainerMetrics;

/**
 * Retrieves service-level container metrics for a specific alert window.
 */
public interface ContainerMetricProvider {

    ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window);
}
