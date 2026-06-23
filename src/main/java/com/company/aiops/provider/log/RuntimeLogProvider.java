package com.company.aiops.provider.log;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.RuntimeLogEntry;

import java.util.List;

/**
 * Reads WARN/ERROR runtime log samples associated with a trace or service.
 */
public interface RuntimeLogProvider {

    List<RuntimeLogEntry> queryWarnAndErrorLogs(
            String traceId,
            String serviceName,
            TimeWindow window,
            int limit
    );

    List<RuntimeLogEntry> queryByMessage(
            String serviceName,
            String cluster,
            String level,
            String messageQuery,
            TimeWindow window,
            int limit
    );
}
