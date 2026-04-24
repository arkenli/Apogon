package com.company.aiops.provider.log;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.InterfaceLogEntry;

import java.util.List;

/**
 * Reads normalized interface logs for one service and interface within the alert window.
 */
public interface InterfaceLogProvider {

    List<InterfaceLogEntry> queryByInterface(
            String serviceName,
            String interfaceName,
            TimeWindow window,
            int limit
    );
}
