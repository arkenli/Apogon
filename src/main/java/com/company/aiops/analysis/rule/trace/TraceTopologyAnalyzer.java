package com.company.aiops.analysis.rule.trace;

import com.company.aiops.provider.model.InterfaceLogEntry;

import java.util.List;

/**
 * Builds parent/child topology from span identifiers carried by interface logs.
 */
public interface TraceTopologyAnalyzer {

    /**
     * Converts one trace's interface-log entries into a reusable tree view.
     */
    TraceTree buildTraceTree(List<InterfaceLogEntry> entries);
}
