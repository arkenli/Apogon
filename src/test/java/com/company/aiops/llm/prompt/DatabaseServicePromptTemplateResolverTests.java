package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseServicePromptTemplateResolverTests {

    @Test
    void resolveReturnsEmptyForBlankServiceName() {
        ServicePromptTemplateRepository repository = mock(ServicePromptTemplateRepository.class);
        DatabaseServicePromptTemplateResolver resolver = new DatabaseServicePromptTemplateResolver(repository);

        assertThat(resolver.resolve("  ", AlertCategory.RPC_ERROR_RATE)).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void resolveDelegatesToRepositoryForNonBlankServiceName() {
        ServicePromptTemplateRepository repository = mock(ServicePromptTemplateRepository.class);
        ServicePromptTemplateRecord record = new ServicePromptTemplateRecord(
                1L,
                "ams_front",
                AlertCategory.RPC_ERROR_RATE,
                "system",
                "user",
                true,
                "v1",
                null,
                "seed",
                "seed",
                Instant.parse("2026-06-16T00:00:00Z"),
                Instant.parse("2026-06-16T00:00:00Z")
        );
        when(repository.findEnabledByServiceNameAndAlertCategory("ams_front", AlertCategory.RPC_ERROR_RATE))
                .thenReturn(Optional.of(record));
        DatabaseServicePromptTemplateResolver resolver = new DatabaseServicePromptTemplateResolver(repository);

        assertThat(resolver.resolve("  ams_front  ", AlertCategory.RPC_ERROR_RATE)).contains(record);

        verify(repository).findEnabledByServiceNameAndAlertCategory("ams_front", AlertCategory.RPC_ERROR_RATE);
    }
}
