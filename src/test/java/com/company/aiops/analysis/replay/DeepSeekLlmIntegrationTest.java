package com.company.aiops.analysis.replay;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.support.AbstractAiopsIntegrationTestSupport;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM end-to-end replay that exercises the full {@code openai-compatible} provider against
 * DeepSeek while keeping mock provider data for evidence collection.
 *
 * <p>Uses {@code aiops.llm.provider=openai-compatible} with the configured DeepSeek API key at
 * runtime. These tests make live API calls and are tagged {@code llm} so they can be excluded from
 * routine CI runs.</p>
 */
@Tag("llm")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aiops.llm.provider=openai-compatible",
                "aiops.llm.api-key=${AIOPS_LLM_API_KEY:}"
        }
)
class DeepSeekLlmIntegrationTest extends AbstractAiopsIntegrationTestSupport {

    /**
     * Real LLM calls may take 10–30 seconds; the deadline is set to 60 s to accommodate retries.
     */
    private AnalysisJob waitForTerminalTaskLongTimeout(String taskId) {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElse(null);
            if (task != null && task.status().isTerminal()) {
                return task;
            }
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
        return analysisTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new AssertionError("Task not found after deadline: " + taskId));
    }

    @Test
    void errorRateAlertWithRealDeepSeekLlmProducesLlmAssistedResult() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTaskLongTimeout(taskId);
        assertThat(task.status())
                .as("task should reach SUCCESS with real LLM")
                .isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("taskId").asText()).isEqualTo(taskId);
        assertThat(data.path("status").asText()).isEqualTo("SUCCESS");

        JsonNode result = data.path("result");
        assertThat(result.path("analysisMode").asText())
                .as("real LLM call should produce LLM_ASSISTED")
                .isEqualTo("LLM_ASSISTED");
        assertThat(result.path("llmUsed").asBoolean())
                .as("llmUsed must be true when LLM succeeds")
                .isTrue();
        assertThat(result.path("llmStatus").asText())
                .isEqualTo("SUCCESS");
        assertThat(result.path("degraded").asBoolean())
                .isFalse();

        assertThat(result.path("summary").asText())
                .as("LLM summary should not be empty")
                .isNotBlank();

        assertThat(result.path("rootCauses"))
                .as("LLM should produce at least one root cause")
                .isNotEmpty();
        assertThat(result.path("rootCauses").get(0).path("causeType").asText())
                .as("top root cause should reference a known cause type")
                .isNotBlank();
        assertThat(result.path("rootCauses").get(0).path("evidenceIds"))
                .as("every root cause must cite evidence")
                .isNotEmpty();

        assertThat(result.path("recommendations"))
                .as("LLM should produce at least one recommendation")
                .isNotEmpty();

        assertThat(result.path("overallConfidence").asDouble())
                .as("overall confidence must be in [0, 1]")
                .isBetween(0.0, 1.0);

        assertThat(result.path("evidenceRefs"))
                .as("result should cite compressed evidence references")
                .isNotEmpty();

        System.out.println("=== DeepSeek LLM error-rate analysis result ===");
        System.out.println("analysisMode: " + result.path("analysisMode").asText());
        System.out.println("summary:       " + result.path("summary").asText());
        System.out.println("impact:        " + result.path("impact").path("level").asText()
                + " — " + result.path("impact").path("description").asText());
        System.out.println("confidence:    " + result.path("overallConfidence").asDouble());
        for (JsonNode rootCause : result.path("rootCauses")) {
            System.out.println("rootCause:     " + rootCause.path("causeType").asText()
                    + " (conf=" + rootCause.path("confidence").asDouble() + ")"
                    + " — " + rootCause.path("description").asText());
        }
        for (JsonNode rec : result.path("recommendations")) {
            System.out.println("recommend:     [" + rec.path("priority").asText() + "] "
                    + rec.path("actionType").asText()
                    + " → " + rec.path("target").asText()
                    + " — " + rec.path("description").asText());
        }
    }

    @Test
    void latencyAlertWithRealDeepSeekLlmProducesLlmAssistedResult() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.LATENCY_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTaskLongTimeout(taskId);
        assertThat(task.status())
                .as("task should reach SUCCESS with real LLM")
                .isEqualTo(AnalysisJobStatus.SUCCESS);

        JsonNode data = requireData(getTask(taskId));
        assertThat(data.path("status").asText()).isEqualTo("SUCCESS");

        JsonNode result = data.path("result");
        assertThat(result.path("analysisMode").asText())
                .isEqualTo("LLM_ASSISTED");
        assertThat(result.path("llmUsed").asBoolean()).isTrue();
        assertThat(result.path("llmStatus").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("degraded").asBoolean()).isFalse();

        assertThat(result.path("summary").asText()).isNotBlank();
        assertThat(result.path("rootCauses")).isNotEmpty();
        assertThat(result.path("rootCauses").get(0).path("evidenceIds")).isNotEmpty();
        assertThat(result.path("recommendations")).isNotEmpty();
        assertThat(result.path("overallConfidence").asDouble()).isBetween(0.0, 1.0);

        System.out.println("=== DeepSeek LLM latency analysis result ===");
        System.out.println("analysisMode: " + result.path("analysisMode").asText());
        System.out.println("summary:       " + result.path("summary").asText());
        System.out.println("impact:        " + result.path("impact").path("level").asText()
                + " — " + result.path("impact").path("description").asText());
        System.out.println("confidence:    " + result.path("overallConfidence").asDouble());
        for (JsonNode rootCause : result.path("rootCauses")) {
            System.out.println("rootCause:     " + rootCause.path("causeType").asText()
                    + " (conf=" + rootCause.path("confidence").asDouble() + ")"
                    + " — " + rootCause.path("description").asText());
        }
        for (JsonNode rec : result.path("recommendations")) {
            System.out.println("recommend:     [" + rec.path("priority").asText() + "] "
                    + rec.path("actionType").asText()
                    + " → " + rec.path("target").asText()
                    + " — " + rec.path("description").asText());
        }
    }

    @Test
    void llmCallLogIsPersistedAfterSuccessfulDeepSeekCall() {
        String alertId = nextNumericAlertId();
        String taskId = requireData(postWebhook(AlertSamples.withAlertId(AlertSamples.RATE_ALERT, alertId)))
                .path("taskId")
                .asText();

        AnalysisJob task = waitForTerminalTaskLongTimeout(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);

        Integer callCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiops_llm_call_log WHERE task_id = ?",
                Integer.class,
                taskId
        );
        assertThat(callCount)
                .as("at least one LLM call log should be persisted")
                .isPositive();

        String callStatus = jdbcTemplate.queryForObject(
                "SELECT call_status FROM aiops_llm_call_log WHERE task_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class,
                taskId
        );
        assertThat(callStatus).isEqualTo("SUCCESS");

        String provider = jdbcTemplate.queryForObject(
                "SELECT provider FROM aiops_llm_call_log WHERE task_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class,
                taskId
        );
        assertThat(provider).isEqualTo("openai-compatible");
    }
}
