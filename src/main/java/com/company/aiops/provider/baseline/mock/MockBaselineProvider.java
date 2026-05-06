package com.company.aiops.provider.baseline.mock;

import com.company.aiops.provider.baseline.BaselineProvider;
import com.company.aiops.provider.mock.MockProviderTokens;
import com.company.aiops.provider.model.InterfaceBaseline;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mock baseline provider with deterministic values that are stable across test runs.
 */
@Component
public class MockBaselineProvider implements BaselineProvider {

    @Override
    public InterfaceBaseline getInterfaceBaseline(
            String serviceName,
            String interfaceName,
            String cluster,
            Instant occurTime,
            Duration compareDuration,
            int baselineOffsetDays
    ) {
        if (serviceName != null && serviceName.contains(MockProviderTokens.FAIL_BASELINE)) {
            throw new IllegalStateException("mock baseline provider failure for service " + serviceName);
        }

        boolean latencyProfile = interfaceName != null && interfaceName.contains("persistent://");
        return latencyProfile
                ? new InterfaceBaseline(
                60.0,
                0.01,
                220L,
                480L,
                1800L,
                List.of(),
                false
        )
                : new InterfaceBaseline(
                120.0,
                0.02,
                90L,
                150L,
                420L,
                List.of(new InterfaceBaseline.ErrorCodeBaseline(
                        "ACTIVITY_NOT_ELIGIBLE",
                        40L,
                        0.68,
                        List.of("activity validation failed")
                )),
                true
        );
    }
}
