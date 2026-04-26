package com.company.aiops.analysis.orchestrator.worker;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.state.AnalysisJobStateMachine;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisJobCompletionServiceTests {

    @Test
    void completePersistsHistoricalReferenceIntoResultJsonPayload() {
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisJobStateMachine stateMachine = mock(AnalysisJobStateMachine.class);
        AnalysisJobCompletionService service = new AnalysisJobCompletionService(
                properties(),
                resultRepository,
                taskRepository,
                stateMachine
        );
        Instant createdAt = Instant.parse("2026-04-26T02:00:00Z");
        AnalysisJob task = new AnalysisJob(
                "task-current",
                "alert-current",
                AlertCategory.RPC_ERROR_RATE,
                "RPC_ERROR_RATE|ams_front|vmall.Interface.query|cluster1",
                AnalysisJobStatus.GENERATING_RESULT,
                "analysis.generate-result",
                "persisting final result",
                0,
                6L,
                createdAt,
                createdAt.plusSeconds(1),
                null,
                null
        );
        HistoricalReference historicalReference = new HistoricalReference(
                true,
                "alert-history",
                "task-history",
                AnalysisMode.LLM_ASSISTED,
                "historical summary",
                0.82,
                Instant.parse("2026-04-26T01:40:00Z")
        );

        when(taskRepository.findByTaskId("task-current")).thenReturn(Optional.of(task));
        when(resultRepository.findLatestHistoricalReference(
                task.dedupeKey(),
                createdAt.minus(Duration.ofDays(1)),
                createdAt.minus(Duration.ofMinutes(5))
        )).thenReturn(Optional.of(historicalReference));
        when(stateMachine.transition(anyString(), any(), anyString(), anyString())).thenReturn(task);

        service.complete("task-current", result("task-current", "alert-current", null));

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().historicalReference()).isEqualTo(historicalReference);
    }

    @Test
    void completeKeepsExistingHistoricalReferenceUntouched() {
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisJobStateMachine stateMachine = mock(AnalysisJobStateMachine.class);
        AnalysisJobCompletionService service = new AnalysisJobCompletionService(
                properties(),
                resultRepository,
                taskRepository,
                stateMachine
        );
        HistoricalReference historicalReference = new HistoricalReference(
                true,
                "alert-history",
                "task-history",
                AnalysisMode.LLM_ASSISTED,
                "historical summary",
                0.82,
                Instant.parse("2026-04-26T01:40:00Z")
        );
        AnalysisResult analysisResult = result("task-current", "alert-current", historicalReference);

        when(stateMachine.transition(anyString(), any(), anyString(), anyString())).thenReturn(mock(AnalysisJob.class));

        service.complete("task-current", analysisResult);

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue()).isEqualTo(analysisResult);
        verifyNoInteractions(taskRepository);
    }

    private AiopsProperties properties() {
        return new AiopsProperties(
                new AiopsProperties.Analysis(
                        Duration.ofMinutes(5),
                        Duration.ofDays(1),
                        30,
                        30,
                        Duration.ofMinutes(3),
                        10,
                        1,
                        Duration.ofMinutes(10),
                        2000,
                        20,
                        20,
                        50,
                        500,
                        10,
                        10,
                        5
                ),
                new AiopsProperties.Provider(
                        Duration.ofSeconds(10),
                        1,
                        Duration.ofSeconds(1),
                        true,
                        true
                ),
                new AiopsProperties.Llm(
                        true,
                        "mock",
                        "default-model",
                        0.1,
                        4000,
                        Duration.ofSeconds(45),
                        true,
                        "fallback-rules-v1",
                        null,
                        null
                ),
                new AiopsProperties.Thresholds(0.8, 0.85, 0.8, 0.8, 0.8, 0.8, 0.85)
        );
    }

    private AnalysisResult result(String taskId, String alertId, HistoricalReference historicalReference) {
        return new AnalysisResult(
                taskId,
                alertId,
                AlertCategory.RPC_ERROR_RATE,
                AnalysisMode.LLM_ASSISTED,
                "analysis summary",
                new ImpactAssessment("LOW", "impact"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                historicalReference,
                true,
                "SUCCESS",
                false,
                false,
                null,
                null,
                0.76,
                Instant.parse("2026-04-26T02:00:30Z")
        );
    }
}
