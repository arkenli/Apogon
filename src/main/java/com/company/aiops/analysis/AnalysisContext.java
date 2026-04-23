package com.company.aiops.analysis;

import com.company.aiops.alert.AlertEvent;
import com.company.aiops.provider.ResourceMapping;

public record AnalysisContext(
        AlertEvent alert,
        TimeWindow alarmWindow,
        TimeWindow baselineWindow,
        ResourceMapping resourceMapping,
        AnalysisOptions options
) {
}
