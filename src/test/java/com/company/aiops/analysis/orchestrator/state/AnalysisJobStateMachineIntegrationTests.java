package com.company.aiops.analysis.orchestrator.state;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStateLog;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.worker.AnalysisJobWorker;
import com.company.aiops.analysis.orchestrator.worker.AnalysisWorkerScenario;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.AnalysisJobStateLogRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AnalysisJobStateMachineIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    private AnalysisJobStateLogRepository analysisJobStateLogRepository;

    @Autowired
    private AnalysisJobStateMachine analysisJobStateMachine;

    @Autowired
    private AnalysisJobWorker analysisJobWorker;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM aiops_analysis_job_state_log");
        jdbcTemplate.update("DELETE FROM aiops_duplicate_alert_link");
        jdbcTemplate.update("DELETE FROM aiops_analysis_result");
        jdbcTemplate.update("DELETE FROM aiops_analysis_task");
        jdbcTemplate.update("DELETE FROM aiops_alert_event");
    }

    @Test
    void workerDrivesSuccessfulLifecycleForSimulatedLlmBranch() {
        String taskId = "task_success_" + System.nanoTime();
        seedPendingTask(taskId, "alert_success_" + System.nanoTime(), AlertCategory.RPC_ERROR_RATE);

        analysisJobWorker.processTask(taskId, AnalysisWorkerScenario.LLM_ASSISTED_SUCCESS);

        AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.SUCCESS);
        assertThat(task.stateVersion()).isEqualTo(7L);
        assertThat(task.startedAt()).isNotNull();
        assertThat(task.finishedAt()).isNotNull();

        List<AnalysisJobStateLog> logs = analysisJobStateLogRepository.findByTaskId(taskId);
        assertThat(logs).extracting(AnalysisJobStateLog::toStatus).containsExactly(
                AnalysisJobStatus.RUNNING,
                AnalysisJobStatus.COLLECTING_EVIDENCE,
                AnalysisJobStatus.RULE_ANALYZING,
                AnalysisJobStatus.COMPRESSING_EVIDENCE,
                AnalysisJobStatus.LLM_ANALYZING,
                AnalysisJobStatus.GENERATING_RESULT,
                AnalysisJobStatus.SUCCESS
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT analysis_mode FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("LLM_ASSISTED");
    }

    @Test
    void workerDrivesDegradedLifecycleWhenLlmTimesOut() {
        String taskId = "task_degraded_" + System.nanoTime();
        seedPendingTask(taskId, "alert_degraded_" + System.nanoTime(), AlertCategory.RPC_LATENCY);

        analysisJobWorker.processTask(taskId, AnalysisWorkerScenario.LLM_FALLBACK_TIMEOUT);

        AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);
        assertThat(task.stateVersion()).isEqualTo(8L);

        List<AnalysisJobStateLog> logs = analysisJobStateLogRepository.findByTaskId(taskId);
        assertThat(logs).extracting(AnalysisJobStateLog::toStatus).containsExactly(
                AnalysisJobStatus.RUNNING,
                AnalysisJobStatus.COLLECTING_EVIDENCE,
                AnalysisJobStatus.RULE_ANALYZING,
                AnalysisJobStatus.COMPRESSING_EVIDENCE,
                AnalysisJobStatus.LLM_ANALYZING,
                AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
                AnalysisJobStatus.GENERATING_RESULT,
                AnalysisJobStatus.DEGRADED_SUCCESS
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT analysis_mode FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT degraded FROM aiops_analysis_result WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
    }

    @Test
    void stateMachineRejectsIllegalTransition() {
        String taskId = "task_illegal_" + System.nanoTime();
        seedPendingTask(taskId, "alert_illegal_" + System.nanoTime(), AlertCategory.RPC_ERROR_RATE);

        assertThatThrownBy(() -> analysisJobStateMachine.transition(
                taskId,
                AnalysisJobStatus.SUCCESS,
                "analysis.complete",
                "skip directly to success"
        )).isInstanceOf(IllegalAnalysisJobStateTransitionException.class);

        AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(task.stateVersion()).isZero();
        assertThat(analysisJobStateLogRepository.findByTaskId(taskId)).isEmpty();
    }

    private void seedPendingTask(String taskId, String alertId, AlertCategory category) {
        Instant now = Instant.now();
        alertEventRepository.save(new AlertEvent(
                alertId,
                category,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.query",
                "cluster1",
                "cn_product_default",
                "tenant",
                "application",
                "demo alert",
                category == AlertCategory.RPC_LATENCY ? "rpc_latency" : "rpc_error_rate",
                category == AlertCategory.RPC_LATENCY ? "AIOps@delay" : "AIOps@rate",
                "raw alert",
                now,
                now,
                new AlertTriggerValues(20.0, 1.0, 3000L, null, null, "demo condition", "demo values")
        ));

        analysisTaskRepository.save(new AnalysisJob(
                taskId,
                alertId,
                category,
                category.name() + "|demo-service|demo.Interface.query|cluster1",
                AnalysisJobStatus.PENDING,
                null,
                "analysis task accepted",
                0,
                0L,
                now,
                null,
                null,
                null
        ));
    }
}
