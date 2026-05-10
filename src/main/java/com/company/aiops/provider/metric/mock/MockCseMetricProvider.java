package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.CseMetricProvider;
import com.company.aiops.provider.model.CseMetrics;
import org.springframework.stereotype.Component;

/**
 * Deterministic CSE metric provider for local development and tests.
 */
@Component
public class MockCseMetricProvider implements CseMetricProvider {

    @Override
    public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
        return new CseMetrics(0.36, 0.29);
    }
}
