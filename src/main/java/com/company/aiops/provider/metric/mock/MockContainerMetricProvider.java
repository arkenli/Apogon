package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.ContainerMetricProvider;
import com.company.aiops.provider.model.ContainerMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic container metric provider that simulates mild service pressure.
 */
@Component
public class MockContainerMetricProvider implements ContainerMetricProvider {

    @Override
    public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
        return new ContainerMetrics(
                0.42,
                0.61,
                0.56,
                0.72,
                0,
                0,
                3L,
                0L,
                List.of(
                        new ContainerMetrics.InstanceMetrics(serviceName + "-pod-1", 0.39, 0.53, false, false),
                        new ContainerMetrics.InstanceMetrics(serviceName + "-pod-2", 0.61, 0.72, false, false)
                )
        );
    }
}
