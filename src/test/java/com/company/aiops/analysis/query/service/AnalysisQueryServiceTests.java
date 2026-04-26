package com.company.aiops.analysis.query.service;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.query.model.AnalysisQueryProjection;
import com.company.aiops.analysis.query.model.AnalysisTaskQueryResponse;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AnalysisQueryRepository;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisQueryServiceTests {

    @Test
    void getTaskBackfillsHistoricalReferenceWhenStoredResultDoesNotContainIt() {
        AnalysisQueryRepository queryRepository = mock(AnalysisQueryRepository.class);
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisQueryService service = new AnalysisQueryService(properties(), queryRepository, resultRepository);

        Instant currentCreatedAt = Instant.parse("2026-04-26T02:00:00Z");
        AnalysisQueryProjection projection = new AnalysisQueryProjection(
                "task-current",
                "alert-current",
                AnalysisJobStatus.SUCCESS,
                AlertCategory.RPC_ERROR_RATE,
                "ams_front",
                "vmall.Interface.query",
                "cluster1",
                "RPC_ERROR_RATE|ams_front|vmall.Interface.query|cluster1",
                currentCreatedAt,
                currentCreatedAt.plusSeconds(1),
                currentCreatedAt.plusSeconds(30),
                "analysis.complete",
                "completed",
                null,
                analysisResult("task-current", "alert-current", currentCreatedAt.plusSeconds(30), null)
        );
        HistoricalReference historicalReference = new HistoricalReference(
                true,
                "alert-history",
                "task-history",
                AnalysisMode.LLM_ASSISTED,
                "historical summary",
                0.82,
                currentCreatedAt.minus(Duration.ofMinutes(15))
        );

        when(queryRepository.findByTaskId("task-current")).thenReturn(Optional.of(projection));
        when(resultRepository.findLatestHistoricalReference(
                projection.dedupeKey(),
                currentCreatedAt.minus(Duration.ofDays(1)),
                currentCreatedAt.minus(Duration.ofMinutes(5))
        )).thenReturn(Optional.of(historicalReference));

        AnalysisTaskQueryResponse response = service.getTask("task-current");

        assertThat(response.result()).isNotNull();
        assertThat(response.result().historicalReference()).isEqualTo(historicalReference);
        assertThat(response.result().analysisMode()).isEqualTo(AnalysisMode.LLM_ASSISTED);
    }

    @Test
    void getTaskDoesNotLookupHistoricalReferenceWhenTaskIsStillRunning() {
        AnalysisQueryRepository queryRepository = mock(AnalysisQueryRepository.class);
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisQueryService service = new AnalysisQueryService(properties(), queryRepository, resultRepository);

        AnalysisQueryProjection projection = new AnalysisQueryProjection(
                "task-running",
                "alert-running",
                AnalysisJobStatus.RUNNING,
                AlertCategory.RPC_LATENCY,
                "vmall_ingresscache_ica",
                "persistent://demo",
                "cluster2",
                "RPC_LATENCY|vmall_ingresscache_ica|persistent://demo|cluster2",
                Instant.parse("2026-04-26T03:00:00Z"),
                Instant.parse("2026-04-26T03:00:02Z"),
                null,
                "analysis.collect-evidence",
                "collecting provider evidence",
                null,
                null
        );

        when(queryRepository.findByTaskId("task-running")).thenReturn(Optional.of(projection));

        AnalysisTaskQueryResponse response = service.getTask("task-running");

        assertThat(response.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(response.result()).isNull();
        verifyNoInteractions(resultRepository);
    }

    @Test
    void getLatestByAlertIdThrowsNotFoundWhenAlertDoesNotExist() {
        AnalysisQueryRepository queryRepository = mock(AnalysisQueryRepository.class);
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisQueryService service = new AnalysisQueryService(properties(), queryRepository, resultRepository);

        when(queryRepository.findLatestByAlertId("missing-alert")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLatestByAlertId("missing-alert"))
                .isInstanceOf(AiopsException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getHistoryRejectsLimitAboveConfiguredMaximum() {
        AnalysisQueryRepository queryRepository = mock(AnalysisQueryRepository.class);
        AnalysisResultRepository resultRepository = mock(AnalysisResultRepository.class);
        AnalysisQueryService service = new AnalysisQueryService(properties(), queryRepository, resultRepository);

        assertThatThrownBy(() -> service.getHistory("history-service", "history.Interface.query", 6))
                .isInstanceOf(AiopsException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(queryRepository, resultRepository);
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

    private AnalysisResult analysisResult(
            String taskId,
            String alertId,
            Instant generatedAt,
            HistoricalReference historicalReference
    ) {
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
                generatedAt
        );
    }
}
