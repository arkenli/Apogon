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
                new RuntimeLogEntry(serviceName, traceId, "WARN", "mock cache access slow", timestamp),
                new RuntimeLogEntry(serviceName, traceId, "ERROR", "mock downstream dependency returned an error", timestamp.plusSeconds(1))
        );
    }
}
