package com.company.aiops.provider.baseline;

import com.company.aiops.provider.model.InterfaceBaseline;

import java.time.Duration;
import java.time.Instant;

/**
 * Reads the previous comparable baseline window for one interface.
 */
public interface BaselineProvider {

    InterfaceBaseline getInterfaceBaseline(
            String serviceName,
            String interfaceName,
            String cluster,
            Instant occurTime,
            Duration compareDuration,
            int baselineOffsetDays
    );
}
