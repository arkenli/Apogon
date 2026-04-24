package com.company.aiops.provider.trace;

import com.company.aiops.provider.model.InterfaceLogEntry;

import java.util.List;

/**
 * Loads all interface-log-shaped spans for one trace id.
 */
public interface TraceProvider {

    List<InterfaceLogEntry> queryTraceInterfaces(String traceId);
}
