package com.company.aiops.alert;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.support.AbstractAiopsIntegrationTestSupport;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-10 ingress coverage for raw alert parsing and webhook dedupe behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertIngressIntegrationTests extends AbstractAiopsIntegrationTestSupport {

    @Test
    void parserParsesRpcErrorRateSample() {
        AlertEvent parsed = alertParser.parse(AlertSamples.RATE_ALERT);
        AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));

        assertThat(alert.alertId()).isEqualTo("521600500");
        assertThat(alert.category()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
        assertThat(alert.serviceName()).isEqualTo("ams_front");
        assertThat(alert.interfaceName()).isEqualTo("vmall-ams.PrizeActivityService.queryPrizeResult");
        assertThat(alert.cluster()).isEqualTo("cluster1");
        assertThat(alert.rawCategory()).isEqualTo("AIOps@rate");
        assertThat(alert.triggerValues().requestCount()).isEqualTo(3.0);
        assertThat(alert.triggerValues().failRate()).isEqualTo(1.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-04-20T10:09:00Z"));
    }

    @Test
    void parserParsesRpcLatencySample() {
        AlertEvent parsed = alertParser.parse(AlertSamples.LATENCY_ALERT);
        AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));

        assertThat(alert.alertId()).isEqualTo("521932116");
        assertThat(alert.category()).isEqualTo(AlertCategory.RPC_LATENCY);
        assertThat(alert.serviceName()).isEqualTo("vmall_ingresscache_ica");
        assertThat(alert.interfaceName())
                .isEqualTo("persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2");
        assertThat(alert.cluster()).isEqualTo("cluster2");
        assertThat(alert.rawCategory()).isEqualTo("AIOps@delay");
        assertThat(alert.triggerValues().requestCount()).isEqualTo(49.0);
        assertThat(alert.triggerValues().avgLatencyMs()).isEqualTo(3067L);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-04-21T02:33:00Z"));
    }

    @Test
    void duplicateWebhookWithinFiveMinutesReturnsExistingTask() {
        String firstAlertId = nextNumericAlertId();
        String secondAlertId = nextNumericAlertId();

        String firstTaskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, firstAlertId)))
                .path("taskId")
                .asText();
        ResponseEntity<JsonNode> duplicateResponse = postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, secondAlertId));

        JsonNode duplicateData = requireData(duplicateResponse);
        assertThat(duplicateData.path("duplicate").asBoolean()).isTrue();
        assertThat(duplicateData.path("taskId").asText()).isEqualTo(firstTaskId);
        assertThat(secondAlertId).isNotEqualTo(firstAlertId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_analysis_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_duplicate_alert_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void webhookReturnsHistoricalReferenceOutsideDedupeWindowAndCreatesNewTask() {
        String historicalAlertId = "history-" + nextNumericAlertId();
        String historicalTaskId = "task_history_" + nextNumericAlertId();
        seedHistoricalSuccessResult(
                AlertSamples.RATE_ALERT,
                historicalAlertId,
                historicalTaskId,
                Instant.now().minus(10, ChronoUnit.MINUTES),
                "historical replay summary"
        );

        String newAlertId = nextNumericAlertId();
        ResponseEntity<JsonNode> response = postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, newAlertId));

        JsonNode data = requireData(response);
        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(data.path("taskId").asText()).isNotEqualTo(historicalTaskId);
        assertThat(data.path("historicalReference").path("matched").asBoolean()).isTrue();
        assertThat(data.path("historicalReference").path("taskId").asText()).isEqualTo(historicalTaskId);
        assertThat(data.path("historicalReference").path("alertId").asText()).isEqualTo(historicalAlertId);
        assertThat(data.path("historicalReference").path("summary").asText()).contains("historical replay");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_analysis_task", Integer.class)).isEqualTo(2);
    }
}
