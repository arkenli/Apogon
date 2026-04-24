package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.MqMetrics;

import java.util.List;

/**
 * Abstracts infrastructure and platform metric retrieval without exposing any concrete HTTP
 * transport to the analysis flow.
 */
public interface MetricProvider {

    ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window);

    DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window);

    DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window);

    MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window);

    CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window);
}
