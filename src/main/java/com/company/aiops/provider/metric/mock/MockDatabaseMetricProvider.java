package com.company.aiops.provider.metric.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.metric.DatabaseMetricProvider;
import com.company.aiops.provider.model.DatabaseMetrics;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic database metric provider for local development and tests.
 */
@Component
public class MockDatabaseMetricProvider implements DatabaseMetricProvider {

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
                        .map(instanceId -> new DatabaseMetrics.SlowSqlSample(
                                instanceId,
                                "select * from activity where id = ?",
                                180L
                        ))
                        .toList()
        );
    }
}
