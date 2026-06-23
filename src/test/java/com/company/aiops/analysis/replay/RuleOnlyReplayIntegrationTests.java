package com.company.aiops.analysis.replay;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.support.AbstractAiopsIntegrationTestSupport;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated phase-10 replay coverage for the configuration-driven RULE_BASED_ONLY branch.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.llm.enabled=false"
)
class RuleOnlyReplayIntegrationTests extends AbstractAiopsIntegrationTestSupport {

    @Test
    void webhookReplayUsesRuleBasedOnlyWhenLlmIsDisabled() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("result").path("analysisMode").asText()).isEqualTo("RULE_BASED_ONLY");
        assertThat(data.path("result").path("llmUsed").asBoolean()).isFalse();
        assertThat(data.path("result").path("llmStatus").asText()).isEqualTo("DISABLED");
        assertThat(data.path("result").path("rootCauses").toString()).contains("BUSINESS_ERROR_CODE_NOT_MASKED");
    }

    @Test
    void serviceErrorLogWebhookReplayUsesRuleEvidenceWhenLlmIsDisabled() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.SERVICE_ERROR_LOG_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        JsonNode result = data.path("result");
        assertThat(data.path("category").asText()).isEqualTo("SERVICE_ERROR_LOG");
        assertThat(result.path("analysisMode").asText()).isEqualTo("RULE_BASED_ONLY");
        assertThat(result.path("summary").asText()).contains("service error log");
        assertThat(result.path("rootCauses").toString()).contains("CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(result.path("evidenceRefs").toString()).contains("runtime.query.attempt.1");
    }
}
