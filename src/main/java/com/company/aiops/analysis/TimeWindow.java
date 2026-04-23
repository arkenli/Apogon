package com.company.aiops.analysis;

import java.time.Instant;

public record TimeWindow(
        Instant startTime,
        Instant endTime
) {
}
