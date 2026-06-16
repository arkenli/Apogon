package com.company.aiops.persistence.repository;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;

import java.util.Optional;

/**
 * Reads service-specific prompt templates used by LLM prompt builders.
 */
public interface ServicePromptTemplateRepository {

    Optional<ServicePromptTemplateRecord> findEnabledByServiceNameAndAlertCategory(
            String serviceName,
            AlertCategory alertCategory
    );
}
