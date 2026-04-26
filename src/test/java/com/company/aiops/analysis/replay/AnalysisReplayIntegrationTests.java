package com.company.aiops.analysis.replay;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.llm.client.mock.MockLlmTokens;
import com.company.aiops.provider.mock.MockProviderTokens;
import com.company.aiops.support.AbstractAiopsIntegrationTestSupport;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end phase-10 replay coverage from webhook ingestion to the phase-9 query APIs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisReplayIntegrationTests extends AbstractAiopsIntegrationTestSupport {

    @Test
    void errorRateReplayRunsThroughTaskQueryAndKeepsBusinessErrorConclusion() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("taskId").asText()).isEqualTo(taskId);
        assertThat(data.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(data.path("result").path("analysisMode").asText()).isEqualTo("LLM_ASSISTED");
        assertThat(data.path("result").path("llmStatus").asText()).isEqualTo("SUCCESS");
        assertThat(data.path("result").path("rootCauses").toString())
                .contains("LOW_TRAFFIC_LIMITED_IMPACT", "BUSINESS_ERROR_CODE_NOT_MASKED");
    }

    @Test
    void latencyReplayAppearsInHistoryQueryAfterWebhookExecution() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.LATENCY_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode history = requireData(getHistory(
                "vmall_ingresscache_ica",
                "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2",
                5
        ));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("taskId").asText()).isEqualTo(taskId);
        assertThat(history.get(0).path("result").path("analysisMode").asText()).isEqualTo("LLM_ASSISTED");
        assertThat(history.get(0).path("result").path("rootCauses").get(0).path("causeType").asText())
                .isEqualTo("DOWNSTREAM_RPC_LATENCY");
    }

    @Test
    void duplicateReplayLetsLatestQueryResolveBackToOriginalTask() {
        String firstAlertId = nextNumericAlertId();
        String secondAlertId = nextNumericAlertId();

        String originalTaskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, firstAlertId)))
                .path("taskId")
                .asText();
        waitForTerminalTask(originalTaskId);

        JsonNode duplicateData = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, secondAlertId)));
        assertThat(duplicateData.path("duplicate").asBoolean()).isTrue();
        assertThat(duplicateData.path("taskId").asText()).isEqualTo(originalTaskId);
        assertThat(secondAlertId).isNotEqualTo(firstAlertId);

        JsonNode latest = requireData(getLatest(secondAlertId));
        assertThat(latest.path("taskId").asText()).isEqualTo(originalTaskId);
        assertThat(latest.path("alertId").asText()).isEqualTo(firstAlertId);
        assertThat(latest.path("result").path("analysisMode").asText()).isEqualTo("LLM_ASSISTED");
    }

    @Test
    void historicalReplayBackfillsHistoricalReferenceDuringTaskQuery() {
        String historicalAlertId = "history-" + nextNumericAlertId();
        String historicalTaskId = "task_history_" + nextNumericAlertId();
        seedHistoricalSuccessResult(
                AlertSamples.RATE_ALERT,
                historicalAlertId,
                historicalTaskId,
                Instant.now().minus(10, ChronoUnit.MINUTES),
                "historical replay summary"
        );

        String currentAlertId = nextNumericAlertId();
        String currentTaskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, currentAlertId)))
                .path("taskId")
                .asText();
        waitForTerminalTask(currentTaskId);

        try {
            JsonNode persisted = loadResultJson(currentTaskId);
            assertThat(persisted.path("historicalReference").path("matched").asBoolean()).isTrue();
            assertThat(persisted.path("historicalReference").path("taskId").asText()).isEqualTo(historicalTaskId);
            assertThat(persisted.path("historicalReference").path("alertId").asText()).isEqualTo(historicalAlertId);
        } catch (Exception exception) {
            throw new AssertionError("Failed to load persisted result json", exception);
        }

        JsonNode data = requireData(getTask(currentTaskId));
        assertThat(data.path("result").path("historicalReference").path("matched").asBoolean()).isTrue();
        assertThat(data.path("result").path("historicalReference").path("taskId").asText()).isEqualTo(historicalTaskId);
        assertThat(data.path("result").path("historicalReference").path("alertId").asText()).isEqualTo(historicalAlertId);
    }

    @Test
    void nonCriticalProviderFailureReplayKeepsLlmResultButMarksDegraded() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(
                AlertSamples.withServiceName(AlertSamples.RATE_ALERT, "ams_front_" + nextNumericAlertId() + "_" + MockProviderTokens.FAIL_RUNTIME_LOG),
                alertId
        ))).path("taskId").asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("result").path("analysisMode").asText()).isEqualTo("LLM_ASSISTED");
        assertThat(data.path("result").path("degraded").asBoolean()).isTrue();
        assertThat(data.path("result").path("degradedByProviderFailure").asBoolean()).isTrue();
        assertThat(data.path("result").path("missingEvidence").toString()).contains("runtimeLog");
    }

    @Test
    void criticalInterfaceLogFailureReplayUsesRuleFallbackAndSkipsLlm() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(
                AlertSamples.withServiceName(AlertSamples.RATE_ALERT, "ams_front_" + nextNumericAlertId() + "_" + MockProviderTokens.FAIL_INTERFACE_LOG),
                alertId
        ))).path("taskId").asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("result").path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(data.path("result").path("llmUsed").asBoolean()).isFalse();
        assertThat(data.path("result").path("llmStatus").asText()).isEqualTo("SKIPPED_BY_PROVIDER_FAILURE");
        assertThat(data.path("result").path("missingEvidence").toString()).contains("interfaceLog");
    }

    @Test
    void llmTimeoutReplayFallsBackToRuleBasedResultAndRemainsQueryable() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(
                AlertSamples.withServiceName(AlertSamples.RATE_ALERT, "ams_front_" + nextNumericAlertId() + "_" + MockLlmTokens.TIMEOUT),
                alertId
        ))).path("taskId").asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("result").path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(data.path("result").path("llmStatus").asText()).isEqualTo("TIMEOUT");
        assertThat(data.path("result").path("degraded").asBoolean()).isTrue();
        assertThat(data.path("result").path("summary").asText()).contains("fallback");
    }
}
