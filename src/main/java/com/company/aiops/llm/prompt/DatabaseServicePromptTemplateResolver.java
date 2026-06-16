package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DatabaseServicePromptTemplateResolver implements ServicePromptTemplateResolver {

    private final ServicePromptTemplateRepository repository;

    public DatabaseServicePromptTemplateResolver(ServicePromptTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ServicePromptTemplateRecord> resolve(String serviceName, AlertCategory alertCategory) {
        if (!hasText(serviceName) || alertCategory == null) {
            return Optional.empty();
        }
        return repository.findEnabledByServiceNameAndAlertCategory(serviceName.trim(), alertCategory);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
