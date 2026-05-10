package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.DcsMetricProvider;
import com.company.aiops.provider.model.DcsMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic DCS metric provider for local development and tests.
 */
@Component
public class MockDcsMetricProvider implements DcsMetricProvider {

    @Override
    public DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window) {
        return new DcsMetrics(
                dcsInstanceIds.stream()
                        .map(instanceId -> new DcsMetrics.InstanceMetrics(instanceId, 0.32, 0.44, 540.0, 0.02, 38L, 19L, 0L))
                        .toList()
        );
    }
}
