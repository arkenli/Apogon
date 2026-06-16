package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JdbcServicePromptTemplateRepositoryIntegrationTests {

    private static final String SERVICE_NAME = "it-service-prompt";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServicePromptTemplateRepository repository;

    @BeforeEach
    @AfterEach
    void cleanTemplates() {
        jdbcTemplate.update("DELETE FROM aiops_service_prompt_template WHERE service_name = ?", SERVICE_NAME);
    }

    @Test
    void findEnabledByServiceNameAndAlertCategoryReturnsEnabledMatchingTemplate() {
        insertTemplate(AlertCategory.RPC_ERROR_RATE, true, "错误率 system", "错误率 user");
        insertTemplate(AlertCategory.RPC_LATENCY, true, "时延 system", "时延 user");

        Optional<ServicePromptTemplateRecord> record = repository.findEnabledByServiceNameAndAlertCategory(
                SERVICE_NAME,
                AlertCategory.RPC_ERROR_RATE
        );

        assertThat(record).isPresent();
        assertThat(record.get().serviceName()).isEqualTo(SERVICE_NAME);
        assertThat(record.get().alertCategory()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
        assertThat(record.get().systemPrompt()).isEqualTo("错误率 system");
        assertThat(record.get().userPrompt()).isEqualTo("错误率 user");
        assertThat(record.get().enabled()).isTrue();
    }

    @Test
    void findEnabledByServiceNameAndAlertCategoryIgnoresDisabledTemplate() {
        insertTemplate(AlertCategory.RPC_ERROR_RATE, false, "disabled system", "disabled user");

        Optional<ServicePromptTemplateRecord> record = repository.findEnabledByServiceNameAndAlertCategory(
                SERVICE_NAME,
                AlertCategory.RPC_ERROR_RATE
        );

        assertThat(record).isEmpty();
    }

    private void insertTemplate(
            AlertCategory category,
            boolean enabled,
            String systemPrompt,
            String userPrompt
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_service_prompt_template (
                            service_name,
                            alert_category,
                            system_prompt,
                            user_prompt,
                            enabled,
                            version,
                            metadata_json,
                            created_by,
                            updated_by,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """,
                SERVICE_NAME,
                category.name(),
                systemPrompt,
                userPrompt,
                enabled,
                "v1",
                "{\"seed\":\"integration-test\"}",
                "integration-test",
                "integration-test"
        );
    }
}
