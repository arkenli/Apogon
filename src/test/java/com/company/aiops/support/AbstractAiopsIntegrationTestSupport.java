package com.company.aiops.support;

import com.company.aiops.alert.classifier.AlertClassifier;
import com.company.aiops.alert.dedupe.DedupeKeyGenerator;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.parser.AlertParser;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared database-backed Spring Boot integration helpers for the phase-10 smoke, ingress, and
 * replay suites.
 *
 * <p>The helper centralizes database cleanup and the webhook/query polling flow so the replay
 * tests can focus on one scenario each instead of re-implementing infrastructure code.</p>
 */
public abstract class AbstractAiopsIntegrationTestSupport {

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected AiopsProperties aiopsProperties;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected AlertEventRepository alertEventRepository;

    @Autowired
    protected AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    protected AnalysisResultRepository analysisResultRepository;

    @Autowired
    protected AlertParser alertParser;

    @Autowired
    protected AlertClassifier alertClassifier;

    @Autowired
    protected DedupeKeyGenerator dedupeKeyGenerator;

    @Autowired
    protected ResourceMappingProvider resourceMappingProvider;

    @org.junit.jupiter.api.BeforeEach
    protected void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM aiops_analysis_job_state_log");
        jdbcTemplate.update("DELETE FROM aiops_llm_call_log");
        jdbcTemplate.update("DELETE FROM aiops_provider_call_log");
        jdbcTemplate.update("DELETE FROM aiops_duplicate_alert_link");
        jdbcTemplate.update("DELETE FROM aiops_evidence_bundle");
        jdbcTemplate.update("DELETE FROM aiops_analysis_result");
        jdbcTemplate.update("DELETE FROM aiops_analysis_task");
        jdbcTemplate.update("DELETE FROM aiops_alert_event");
    }

    protected ResponseEntity<JsonNode> postWebhook(String rawAlert) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return restTemplate.postForEntity("/api/v1/alerts/webhook", new HttpEntity<>(rawAlert, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> getTask(String taskId) {
        return restTemplate.getForEntity("/api/v1/analysis/tasks/" + taskId, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> getLatest(String alertId) {
        return restTemplate.getForEntity("/api/v1/analysis/latest?alertId=" + alertId, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> getHistory(String serviceName, String interfaceName, int limit) {
        return restTemplate.getForEntity(
                "/api/v1/analysis/history?serviceName={serviceName}&interfaceName={interfaceName}&limit={limit}",
                JsonNode.class,
                serviceName,
                interfaceName,
                limit
        );
    }

    protected JsonNode loadResultJson(String taskId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT result_json FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        );
        return objectMapper.readTree(json);
    }

    protected String nextNumericAlertId() {
        return Long.toUnsignedString(System.nanoTime());
    }

    protected AnalysisJob waitForTerminalTask(String taskId) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElse(null);
            if (task != null && task.status().isTerminal()) {
                return task;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        return analysisTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new AssertionError("Task not found: " + taskId));
    }

    protected JsonNode requireData(ResponseEntity<JsonNode> response) {
        if (response.getBody() == null) {
            throw new AssertionError("Response body is null");
        }
        return response.getBody().path("data");
    }

    /**
     * Seeds one previously completed task/result pair so replay tests can verify the phase-9
     * historical-reference backfill path without mutating production schema.
     */
    protected void seedHistoricalSuccessResult(
            String rawAlert,
            String historicalAlertId,
            String historicalTaskId,
            Instant createdAt,
            String summary
    ) {
        String historicalRawAlert = AlertSamples.withAlertId(rawAlert, historicalAlertId);
        AlertEvent parsed = alertParser.parse(historicalRawAlert);
        AlertEvent historicalAlert = parsed.withCategory(alertClassifier.classify(parsed));
        String dedupeKey = dedupeKeyGenerator.generate(historicalAlert);

        alertEventRepository.save(historicalAlert);
        analysisTaskRepository.save(new AnalysisJob(
                historicalTaskId,
                historicalAlertId,
                historicalAlert.category(),
                dedupeKey,
                AnalysisJobStatus.SUCCESS,
                "analysis.complete",
                "completed",
                0,
                1L,
                createdAt,
                createdAt.plusSeconds(1),
                createdAt.plusSeconds(60),
                null
        ));
        analysisResultRepository.save(new AnalysisResult(
                historicalTaskId,
                historicalAlertId,
                historicalAlert.category(),
                AnalysisMode.LLM_ASSISTED,
                summary,
                new ImpactAssessment("LOW", "historical replay reference"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                "SUCCESS",
                false,
                false,
                null,
                null,
                0.78,
                createdAt.plusSeconds(60)
        ));
    }
}
