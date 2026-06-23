package com.company.aiops.provider.log.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.log.RuntimeLogProvider;
import com.company.aiops.provider.mock.MockProviderTokens;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mock runtime-log provider. The service-name token allows tests to verify non-critical provider
 * degradation without replacing Spring beans.
 */
@Component
public class MockRuntimeLogProvider implements RuntimeLogProvider {

    @Override
    public List<RuntimeLogEntry> queryWarnAndErrorLogs(
            String traceId,
            String serviceName,
            TimeWindow window,
            int limit
    ) {
        if (serviceName != null && serviceName.contains(MockProviderTokens.FAIL_RUNTIME_LOG)) {
            throw new IllegalStateException("mock runtime-log provider failure for service " + serviceName);
        }

        Instant timestamp = window.endTime().minusSeconds(5);
        return List.of(
                new RuntimeLogEntry(
                        serviceName,
                        null,
                        "mock-instance-1",
                        traceId,
                        null,
                        "WARN",
                        "mock.logger",
                        "mock-thread",
                        "mock cache access slow",
                        null,
                        null,
                        timestamp
                ),
                new RuntimeLogEntry(
                        serviceName,
                        null,
                        "mock-instance-1",
                        traceId,
                        null,
                        "ERROR",
                        "mock.logger",
                        "mock-thread",
                        "mock downstream dependency returned an error",
                        null,
                        null,
                        timestamp.plusSeconds(1)
                )
        );
    }

    @Override
    public List<RuntimeLogEntry> queryByMessage(
            String serviceName,
            String cluster,
            String level,
            String messageQuery,
            TimeWindow window,
            int limit
    ) {
        if (serviceName != null && serviceName.contains(MockProviderTokens.FAIL_RUNTIME_LOG)) {
            throw new IllegalStateException("mock runtime-log provider failure for service " + serviceName);
        }
        if (!"ams_front".equals(serviceName)
                || !"cluster2".equals(cluster)
                || !"ERROR".equals(level)
                || !"loginAppointment failed".equals(messageQuery)
                || limit <= 0) {
            return List.of();
        }

        Instant timestamp = window.endTime().minusSeconds(3);
        return List.of(
                new RuntimeLogEntry(
                        serviceName,
                        cluster,
                        "mock-instance-1",
                        "trace-log-1",
                        "span-log-1",
                        "ERROR",
                        "mock.logger",
                        "mock-thread",
                        "prebook loginAppointment failed for uid=mock-1",
                        "AppointmentLoginException",
                        "AppointmentController.loginAppointment:87",
                        timestamp
                ),
                new RuntimeLogEntry(
                        serviceName,
                        cluster,
                        "mock-instance-1",
                        "trace-log-2",
                        "span-log-2",
                        "ERROR",
                        "mock.logger",
                        "mock-thread",
                        "retry loginAppointment failed for uid=mock-2",
                        "AppointmentLoginException",
                        "AppointmentController.loginAppointment:87",
                        timestamp.plusSeconds(1)
                )
        );
    }
}
