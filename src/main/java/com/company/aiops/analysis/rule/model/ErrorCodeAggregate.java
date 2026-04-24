package com.company.aiops.analysis.rule.model;

import java.util.List;

/**
 * Aggregated failed-interface distribution for one error code.
 */
public record ErrorCodeAggregate(
        String evidenceId,
        String errorCode,
        long count,
        double share,
        long uidCount,
        long traceCount,
        boolean historicalCommon,
        boolean businessSemantic,
        List<String> sampleErrorMsgs
) {

    public ErrorCodeAggregate {
        sampleErrorMsgs = sampleErrorMsgs == null ? List.of() : List.copyOf(sampleErrorMsgs);
    }
}
