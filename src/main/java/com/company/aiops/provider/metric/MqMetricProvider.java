package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.MqMetrics;

import java.util.List;

/**
 * Retrieves MQ metrics for the mapped resource ids within the alert window.
 */
public interface MqMetricProvider {

    MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window);
}
