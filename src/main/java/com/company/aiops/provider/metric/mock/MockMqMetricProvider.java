package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.MqMetricProvider;
import com.company.aiops.provider.model.MqMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic MQ metric provider for local development and tests.
 */
@Component
public class MockMqMetricProvider implements MqMetricProvider {

    @Override
    public MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window) {
        return new MqMetrics(
                mqInstanceIds.stream()
                        .map(instanceId -> new MqMetrics.InstanceMetrics(instanceId, 12L, 18L, 22L, 0L))
                        .toList()
        );
    }
}
