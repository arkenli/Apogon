package com.company.aiops.provider.baseline;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.InterfaceBaseline;

/**
 * Reads the aggregated result of the previous comparable baseline window for one interface.
 */
public interface BaselineProvider {

    InterfaceBaseline getInterfaceBaseline(
            String serviceName,
            String interfaceName,
            String cluster,
            TimeWindow baselineWindow
    );
}
