package com.company.aiops.system;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.support.AbstractAiopsIntegrationTestSupport;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke coverage for shared infrastructure that stage-10 replay tests depend on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTests extends AbstractAiopsIntegrationTestSupport {

    @Test
    void contextLoadsWithExpectedDefaults() {
        assertThat(aiopsProperties.analysis().dedupeWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(aiopsProperties.analysis().historyQueryMaxLimit()).isEqualTo(5);
        assertThat(aiopsProperties.llm().enabled()).isTrue();
        assertThat(aiopsProperties.llm().provider()).isEqualTo("openai-compatible");
        assertThat(aiopsProperties.provider().parallelEnabled()).isTrue();
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/api/v1/health", JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("success").asBoolean()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("UP");
    }

    @Test
    void enumsSerializeAsStableNames() throws Exception {
        assertThat(objectMapper.writeValueAsString(AlertCategory.RPC_ERROR_RATE)).isEqualTo("\"RPC_ERROR_RATE\"");
        assertThat(objectMapper.writeValueAsString(AnalysisJobStatus.COLLECTING_EVIDENCE))
                .isEqualTo("\"COLLECTING_EVIDENCE\"");
    }

    @Test
    void jsonFieldSerializationRoundTrips() {
        AlertTriggerValues triggerValues = new AlertTriggerValues(
                49.0,
                1.0,
                3067L,
                null,
                null,
                "avg_latency > 3000ms for 5m",
                "平均时延=3067, 请求总数=49.0"
        );

        String json = jsonMapper.toJson(triggerValues);
        AlertTriggerValues restored = jsonMapper.fromJson(json, AlertTriggerValues.class);

        assertThat(json).contains("\"requestCount\":49.0");
        assertThat(restored).isEqualTo(triggerValues);
    }

    @Test
    void flywayInitializesExpectedTables() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name LIKE 'aiops_%'
                        ORDER BY table_name
                        """,
                String.class
        );

        assertThat(tables).contains(
                "aiops_alert_event",
                "aiops_analysis_task",
                "aiops_analysis_result",
                "aiops_evidence_bundle",
                "aiops_provider_call_log",
                "aiops_llm_call_log",
                "aiops_duplicate_alert_link",
                "aiops_resource_mapping",
                "aiops_analysis_job_state_log"
        );
    }

    @Test
    void alertEventRepositoryPersistsJsonField() {
        Instant now = Instant.now();
        AlertTriggerValues triggerValues = new AlertTriggerValues(
                3.0,
                1.0,
                null,
                null,
                null,
                "fail_rate > 0.5",
                "请求总数=3, 失败率=100%"
        );
        AlertEvent alertEvent = new AlertEvent(
                "test-alert-" + now.toEpochMilli(),
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "ams_front",
                "vmall-ams.PrizeActivityService.queryPrizeResult",
                "cluster1",
                "cn",
                "tenant",
                "vmall",
                "RPC error rate",
                "rpc_fail_rate",
                "AIOps@rate",
                AlertSamples.RATE_ALERT,
                now,
                now,
                triggerValues
        );

        alertEventRepository.save(alertEvent);

        assertThat(alertEventRepository.findByAlertId(alertEvent.alertId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.category()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
                    assertThat(saved.triggerValues()).isEqualTo(triggerValues);
                });
    }

    @Test
    void resourceMappingProviderReadsSeededMappingsFromDatabase() {
        ResourceMapping mapping = resourceMappingProvider.queryResourceMapping("ams_front", "cluster1");

        assertThat(mapping.serviceName()).isEqualTo("ams_front");
        assertThat(mapping.clusterName()).isEqualTo("cluster1");
        assertThat(mapping.resources())
                .extracting("resourceId")
                .contains("db_ams_activity", "dcs_ams_cache");
    }
}
