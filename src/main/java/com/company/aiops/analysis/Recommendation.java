package com.company.aiops.analysis;

public record Recommendation(
        String priority,
        String actionType,
        String description,
        String target
) {
}
