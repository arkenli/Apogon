package com.company.aiops.analysis.rule.model;

/**
 * Failed-request concentration for one uid in the alert window.
 */
public record UidAggregate(
        String evidenceId,
        String uid,
        long count,
        double share
) {
}
