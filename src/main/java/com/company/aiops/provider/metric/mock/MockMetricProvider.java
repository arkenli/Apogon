package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.MetricProvider;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.MqMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic metric provider that simulates mild service pressure and healthy infrastructure.
 */
@Component
public class MockMetricProvider implements MetricProvider {

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

    @Override
    public DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window) {
        return new DatabaseMetrics(
                dbInstanceIds.stream()
                        .map(instanceId -> new DatabaseMetrics.InstanceMetrics(
                                instanceId,
                                0.45,
                                0.52,
                                0.40,
                                83L,
                                2L,
                                0.48,
                                12.0,
                                10.0,
                                22L,
                                4L,
                                0L,
                                1L
                        ))
                        .toList(),
                dbInstanceIds.stream()
                        .map(instanceId -> new DatabaseMetrics.SlowSqlSample(instanceId, "select * from activity where id = ?", 180L))
                        .toList()
        );
    }

    @Override
    public DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window) {
        return new DcsMetrics(
                dcsInstanceIds.stream()
                        .map(instanceId -> new DcsMetrics.InstanceMetrics(instanceId, 0.32, 0.44, 540.0, 0.02, 38L, 19L, 0L))
                        .toList()
        );
    }

    @Override
    public MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window) {
        return new MqMetrics(
                mqInstanceIds.stream()
                        .map(instanceId -> new MqMetrics.InstanceMetrics(instanceId, 12L, 18L, 22L, 0L))
                        .toList()
        );
    }

    @Override
    public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
        return new CseMetrics(0.36, 0.29);
    }
}
