package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.CseMetrics;

/**
 * Retrieves CSE or RPC framework metrics for a service within the alert window.
 */
public interface CseMetricProvider {

    CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window);
}
