package com.company.aiops.provider.trace.mock;

import com.company.aiops.provider.mock.MockProviderTokens;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.trace.TraceProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Mock trace provider that returns a tiny three-span topology for each trace id.
 */
@Component
public class MockTraceProvider implements TraceProvider {

    @Override
    public List<InterfaceLogEntry> queryTraceInterfaces(String traceId) {
        if (traceId != null && traceId.contains(MockProviderTokens.FAIL_TRACE)) {
            throw new IllegalStateException("mock trace provider failure for trace " + traceId);
        }

        Instant now = Instant.now();
        return List.of(
                new InterfaceLogEntry("mock-upstream", "entry.interface", traceId, "root", null, "uid-trace", true, null, null, 40L, now.minusSeconds(2)),
                new InterfaceLogEntry("mock-service", "business.interface", traceId, "mid", "root", "uid-trace", true, null, null, 160L, now.minusSeconds(1)),
                new InterfaceLogEntry("mock-downstream", "downstream.interface", traceId, "leaf", "mid", "uid-trace", true, null, null, 980L, now)
        );
    }
}
