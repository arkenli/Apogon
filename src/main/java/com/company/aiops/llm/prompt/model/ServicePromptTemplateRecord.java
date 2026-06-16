package com.company.aiops.llm.prompt.model;

import com.company.aiops.alert.model.AlertCategory;

import java.time.Instant;

/**
 * One stored row from aiops_service_prompt_template.
 */
public record ServicePromptTemplateRecord(
        Long id,
        String serviceName,
        AlertCategory alertCategory,
        String systemPrompt,
        String userPrompt,
        boolean enabled,
        String version,
        String metadataJson,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
