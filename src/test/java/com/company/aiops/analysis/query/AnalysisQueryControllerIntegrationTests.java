package com.company.aiops.analysis.query;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.MissingEvidence;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import com.company.aiops.persistence.repository.DuplicateAlertLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-backed verification for phase-9 query endpoints.
 *
 * <p>These tests complement the MVC slice tests by exercising the real JDBC query repository,
 * duplicate-alert lookup path, and runtime historical-reference enrichment against the local MySQL
 * instance documented in {@code LOCAL_MYSQL.md}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisQueryControllerIntegrationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    private AnalysisResultRepository analysisResultRepository;

    @Autowired
    private DuplicateAlertLinkRepository duplicateAlertLinkRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM aiops_analysis_job_state_log");
        jdbcTemplate.update("DELETE FROM aiops_llm_call_log");
        jdbcTemplate.update("DELETE FROM aiops_provider_call_log");
        jdbcTemplate.update("DELETE FROM aiops_duplicate_alert_link");
        jdbcTemplate.update("DELETE FROM aiops_analysis_result");
        jdbcTemplate.update("DELETE FROM aiops_analysis_task");
        jdbcTemplate.update("DELETE FROM aiops_alert_event");
    }

    @Test
    void taskEndpointReturnsRunningStatusBeforeResultIsPersisted() {
        Instant createdAt = Instant.parse("2026-04-26T03:00:00Z");
        saveAlert(alertEvent("alert-running", "demo-service", "demo.Interface.query", "cluster1", createdAt));
        saveTask(task("task-running", "alert-running", "key-running", AnalysisJobStatus.RUNNING, createdAt, null));

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/v1/analysis/tasks/task-running",
                JsonNode.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("taskId").asText()).isEqualTo("task-running");
        assertThat(data.path("status").asText()).isEqualTo("RUNNING");
        assertThat(data.path("serviceName").asText()).isEqualTo("demo-service");
        assertThat(data.get("result")).isNull();
    }

    @Test
    void taskEndpointReturnsCompletedResultAndComputedHistoricalReference() {
        String dedupeKey = "RPC_ERROR_RATE|ams_front|vmall.Interface.query|cluster1";
        Instant historicalCreatedAt = Instant.parse("2026-04-26T01:40:00Z");
        Instant currentCreatedAt = Instant.parse("2026-04-26T02:00:00Z");

        saveAlert(alertEvent("alert-history", "ams_front", "vmall.Interface.query", "cluster1", historicalCreatedAt));
        saveTask(task(
                "task-history",
                "alert-history",
                dedupeKey,
                AnalysisJobStatus.SUCCESS,
                historicalCreatedAt,
                historicalCreatedAt.plusSeconds(50)
        ));
        saveResult(result(
                "task-history",
                "alert-history",
                AnalysisMode.LLM_ASSISTED,
                "historical summary",
                historicalCreatedAt.plusSeconds(50),
                false
        ));

        saveAlert(alertEvent("alert-current", "ams_front", "vmall.Interface.query", "cluster1", currentCreatedAt));
        saveTask(task(
                "task-current",
                "alert-current",
                dedupeKey,
                AnalysisJobStatus.DEGRADED_SUCCESS,
                currentCreatedAt,
                currentCreatedAt.plusSeconds(40)
        ));
        saveResult(result(
                "task-current",
                "alert-current",
                AnalysisMode.RULE_BASED_DEGRADED,
                "current summary",
                currentCreatedAt.plusSeconds(40),
                true
        ));

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/v1/analysis/tasks/task-current",
                JsonNode.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode result = response.getBody().path("data").path("result");
        assertThat(result.path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(result.path("degraded").asBoolean()).isTrue();
        assertThat(result.path("degradedByProviderFailure").asBoolean()).isTrue();
        assertThat(result.path("missingEvidence")).hasSize(1);
        assertThat(result.path("rootCauses")).hasSize(1);
        assertThat(result.path("recommendations")).hasSize(1);
        assertThat(result.path("historicalReference").path("taskId").asText()).isEqualTo("task-history");
        assertThat(result.path("historicalReference").path("alertId").asText()).isEqualTo("alert-history");
    }

    @Test
    void latestEndpointResolvesDuplicateAlertToReusedTask() {
        Instant createdAt = Instant.parse("2026-04-26T04:00:00Z");
        saveAlert(alertEvent("alert-original", "ams_front", "vmall.Interface.query", "cluster1", createdAt));
        saveTask(task(
                "task-original",
                "alert-original",
                "key-duplicate",
                AnalysisJobStatus.SUCCESS,
                createdAt,
                createdAt.plusSeconds(30)
        ));
        saveResult(result(
                "task-original",
                "alert-original",
                AnalysisMode.LLM_ASSISTED,
                "duplicate target summary",
                createdAt.plusSeconds(30),
                false
        ));

        saveAlert(alertEvent("alert-duplicate", "ams_front", "vmall.Interface.query", "cluster1", createdAt.plusSeconds(10)));
        duplicateAlertLinkRepository.save("alert-duplicate", "task-original", "key-duplicate", createdAt.plusSeconds(10));

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/v1/analysis/latest?alertId=alert-duplicate",
                JsonNode.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("taskId").asText()).isEqualTo("task-original");
        assertThat(data.path("alertId").asText()).isEqualTo("alert-original");
        assertThat(data.path("result").path("summary").asText()).isEqualTo("duplicate target summary");
    }

    @Test
    void historyEndpointReturnsResultsSortedByGeneratedAtDescending() {
        Instant olderCreatedAt = Instant.parse("2026-04-26T00:00:00Z");
        Instant newerCreatedAt = Instant.parse("2026-04-26T05:00:00Z");
        String serviceName = "history-service";
        String interfaceName = "history.Interface.query";

        saveAlert(alertEvent("alert-older", serviceName, interfaceName, "cluster1", olderCreatedAt));
        saveTask(task(
                "task-older",
                "alert-older",
                "key-history",
                AnalysisJobStatus.SUCCESS,
                olderCreatedAt,
                olderCreatedAt.plusSeconds(20)
        ));
        saveResult(result(
                "task-older",
                "alert-older",
                AnalysisMode.LLM_ASSISTED,
                "older summary",
                olderCreatedAt.plusSeconds(20),
                false
        ));

        saveAlert(alertEvent("alert-newer", serviceName, interfaceName, "cluster1", newerCreatedAt));
        saveTask(task(
                "task-newer",
                "alert-newer",
                "key-history",
                AnalysisJobStatus.DEGRADED_SUCCESS,
                newerCreatedAt,
                newerCreatedAt.plusSeconds(20)
        ));
        saveResult(result(
                "task-newer",
                "alert-newer",
                AnalysisMode.RULE_BASED_DEGRADED,
                "newer summary",
                newerCreatedAt.plusSeconds(20),
                true
        ));

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/api/v1/analysis/history?serviceName=history-service&interfaceName=history.Interface.query&limit=10",
                JsonNode.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = response.getBody().path("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("taskId").asText()).isEqualTo("task-newer");
        assertThat(data.get(0).path("result").path("generatedAt").asText()).isEqualTo("2026-04-26T05:00:20Z");
        assertThat(data.get(1).path("taskId").asText()).isEqualTo("task-older");
    }

    private void saveAlert(AlertEvent alertEvent) {
        alertEventRepository.save(alertEvent);
    }

    private void saveTask(AnalysisJob analysisJob) {
        analysisTaskRepository.save(analysisJob);
    }

    private void saveResult(AnalysisResult analysisResult) {
        analysisResultRepository.save(analysisResult);
    }

    private AlertEvent alertEvent(
            String alertId,
            String serviceName,
            String interfaceName,
            String cluster,
            Instant occurTime
    ) {
        return new AlertEvent(
                alertId,
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                serviceName,
                interfaceName,
                cluster,
                "cn",
                "tenant",
                "application",
                "alert",
                "metric",
                "AIOps@rate",
                "raw text",
                occurTime,
                occurTime,
                new AlertTriggerValues(10.0, 0.8, null, null, null, "rule", "raw trigger")
        );
    }

    private AnalysisJob task(
            String taskId,
            String alertId,
            String dedupeKey,
            AnalysisJobStatus status,
            Instant createdAt,
            Instant finishedAt
    ) {
        return new AnalysisJob(
                taskId,
                alertId,
                AlertCategory.RPC_ERROR_RATE,
                dedupeKey,
                status,
                status.isTerminal() ? "analysis.complete" : "analysis.collect-evidence",
                status.isTerminal() ? "completed" : "running",
                0,
                1L,
                createdAt,
                createdAt.plusSeconds(1),
                finishedAt,
                null
        );
    }

    private AnalysisResult result(
            String taskId,
            String alertId,
            AnalysisMode analysisMode,
            String summary,
            Instant generatedAt,
            boolean degraded
    ) {
        return new AnalysisResult(
                taskId,
                alertId,
                AlertCategory.RPC_ERROR_RATE,
                analysisMode,
                summary,
                new ImpactAssessment(degraded ? "MEDIUM" : "LOW", "impact summary"),
                List.of(new RootCauseHypothesis(
                        degraded ? "UNKNOWN" : "BUSINESS_ERROR_CODE_NOT_MASKED",
                        degraded ? "provider gap" : "business validation failure",
                        degraded ? 0.51 : 0.74,
                        List.of("evidence-1"),
                        degraded ? "RULE_ENGINE" : "MERGED",
                        "reasoning"
                )),
                List.of(new Recommendation(
                        "P1",
                        "CHECK_LOG",
                        "check logs",
                        "target-1"
                )),
                List.of(new EvidenceReference("evidence-1", "ALERT", "alert evidence")),
                degraded
                        ? List.of(new MissingEvidence("interfaceLog", "timeout", true, "root cause precision reduced"))
                        : List.of(),
                degraded ? List.of("interface log missing") : List.of(),
                null,
                analysisMode == AnalysisMode.LLM_ASSISTED,
                analysisMode == AnalysisMode.LLM_ASSISTED ? "SUCCESS" : "SKIPPED_BY_PROVIDER_FAILURE",
                degraded,
                degraded,
                degraded ? "critical provider missing" : null,
                degraded ? "fallback-rules-v1" : null,
                degraded ? 0.61 : 0.81,
                generatedAt
        );
    }
}
