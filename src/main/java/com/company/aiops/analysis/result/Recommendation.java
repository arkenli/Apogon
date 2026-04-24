package com.company.aiops.analysis.result;

/**
 * Suggested next action derived from rule analysis or LLM reasoning.
 */
public record Recommendation(
        String priority,
        String actionType,
        String description,
        String target
) {
}
