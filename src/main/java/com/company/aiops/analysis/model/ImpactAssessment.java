package com.company.aiops.analysis.model;

/**
 * High-level impact statement that accompanies the analysis summary.
 */
public record ImpactAssessment(
        String level,
        String description
) {
}
