package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;

import java.util.Optional;

@FunctionalInterface
public interface ServicePromptTemplateResolver {

    Optional<ServicePromptTemplateRecord> resolve(String serviceName, AlertCategory alertCategory);

    static ServicePromptTemplateResolver none() {
        return (serviceName, alertCategory) -> Optional.empty();
    }
}
