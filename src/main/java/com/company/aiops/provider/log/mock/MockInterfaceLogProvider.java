package com.company.aiops.provider.log.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.log.InterfaceLogProvider;
import com.company.aiops.provider.mock.MockProviderTokens;
import com.company.aiops.provider.model.InterfaceLogEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mock interface-log provider that returns deterministic samples and supports token-based failures.
 */
@Component
public class MockInterfaceLogProvider implements InterfaceLogProvider {

    @Override
    public List<InterfaceLogEntry> queryByInterface(
            String serviceName,
            String interfaceName,
            TimeWindow window,
            int limit
    ) {
        if (serviceName != null && serviceName.contains(MockProviderTokens.FAIL_INTERFACE_LOG)) {
            throw new IllegalStateException("mock interface-log provider failure for service " + serviceName);
        }

        Instant baseTime = window.endTime().minusSeconds(30);
        if (interfaceName != null && interfaceName.contains("persistent://")) {
            String tracePrefix = serviceName != null && serviceName.contains(MockProviderTokens.FAIL_TRACE)
                    ? MockProviderTokens.FAIL_TRACE + "-"
                    : "latency-";
            return List.of(
                    new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-1", "1", null, "uid-101", true, null, null, 3200L, baseTime.minusSeconds(8)),
                    new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-2", "2", null, "uid-102", true, null, null, 2900L, baseTime.minusSeconds(6)),
                    new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-3", "3", null, "uid-103", true, null, null, 2600L, baseTime.minusSeconds(4))
            );
        }

        String tracePrefix = serviceName != null && serviceName.contains(MockProviderTokens.FAIL_TRACE)
                ? MockProviderTokens.FAIL_TRACE + "-"
                : "error-";
        return List.of(
                new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-1", "1", null, "uid-201", false, "ACTIVITY_NOT_ELIGIBLE", "activity validation failed", 120L, baseTime.minusSeconds(5)),
                new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-2", "2", null, "uid-202", false, "ACTIVITY_NOT_ELIGIBLE", "activity validation failed", 118L, baseTime.minusSeconds(3)),
                new InterfaceLogEntry(serviceName, interfaceName, tracePrefix + "trace-3", "3", null, "uid-203", true, null, null, 95L, baseTime.minusSeconds(1))
        );
    }
}
