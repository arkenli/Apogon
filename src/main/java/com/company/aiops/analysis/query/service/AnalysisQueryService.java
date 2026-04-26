package com.company.aiops.analysis.query.service;

import com.company.aiops.analysis.query.model.AnalysisQueryProjection;
import com.company.aiops.analysis.query.model.AnalysisResultView;
import com.company.aiops.analysis.query.model.AnalysisTaskQueryResponse;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AnalysisQueryRepository;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Read-only facade for the phase-9 query endpoints.
 *
 * <p>The service keeps transport-facing payload assembly out of the JDBC layer. Newly generated
 * results now persist {@code historicalReference} directly at completion time; the lookup fallback
 * remains only for older rows created before that change.</p>
 */
@Service
public class AnalysisQueryService {

    private final AiopsProperties aiopsProperties;
    private final AnalysisQueryRepository analysisQueryRepository;
    private final AnalysisResultRepository analysisResultRepository;

    public AnalysisQueryService(
            AiopsProperties aiopsProperties,
            AnalysisQueryRepository analysisQueryRepository,
            AnalysisResultRepository analysisResultRepository
    ) {
        this.aiopsProperties = aiopsProperties;
        this.analysisQueryRepository = analysisQueryRepository;
        this.analysisResultRepository = analysisResultRepository;
    }

    /**
     * Returns one task snapshot by task id.
     */
    public AnalysisTaskQueryResponse getTask(String taskId) {
        return analysisQueryRepository.findByTaskId(taskId)
                .map(this::toResponse)
                .orElseThrow(() -> notFound("analysis task not found: " + taskId));
    }

    /**
     * Returns the newest task associated with the alert id. Duplicate alerts are resolved back to
     * the reused original task.
     */
    public AnalysisTaskQueryResponse getLatestByAlertId(String alertId) {
        return analysisQueryRepository.findLatestByAlertId(alertId)
                .map(this::toResponse)
                .orElseThrow(() -> notFound("analysis task not found for alertId: " + alertId));
    }

    /**
     * Returns completed historical results ordered by {@code generated_at desc}.
     */
    public List<AnalysisTaskQueryResponse> getHistory(String serviceName, String interfaceName, int limit) {
        validateHistoryLimit(limit);
        return analysisQueryRepository.findHistoryByServiceAndInterface(serviceName, interfaceName, limit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AnalysisTaskQueryResponse toResponse(AnalysisQueryProjection projection) {
        return new AnalysisTaskQueryResponse(
                projection.taskId(),
                projection.alertId(),
                projection.status(),
                projection.category(),
                projection.serviceName(),
                projection.interfaceName(),
                projection.cluster(),
                projection.createdAt(),
                projection.startedAt(),
                projection.finishedAt(),
                projection.currentStep(),
                projection.statusReason(),
                projection.failureReason(),
                toResultView(projection)
        );
    }

    private AnalysisResultView toResultView(AnalysisQueryProjection projection) {
        AnalysisResult analysisResult = projection.analysisResult();
        if (analysisResult == null) {
            return null;
        }

        HistoricalReference historicalReference = analysisResult.historicalReference();
        if (historicalReference == null) {
            historicalReference = lookupHistoricalReference(projection.dedupeKey(), projection.createdAt());
        }
        return AnalysisResultView.from(analysisResult, historicalReference);
    }

    private HistoricalReference lookupHistoricalReference(String dedupeKey, Instant taskCreatedAt) {
        if (dedupeKey == null || dedupeKey.isBlank() || taskCreatedAt == null) {
            return null;
        }

        Instant historicalSince = taskCreatedAt.minus(aiopsProperties.analysis().historicalReferenceLookback());
        Instant duplicateBefore = taskCreatedAt.minus(aiopsProperties.analysis().dedupeWindow());
        return analysisResultRepository.findLatestHistoricalReference(dedupeKey, historicalSince, duplicateBefore)
                .orElse(null);
    }

    private AiopsException notFound(String message) {
        return new AiopsException(ErrorCode.NOT_FOUND, message);
    }

    private void validateHistoryLimit(int limit) {
        int maxLimit = aiopsProperties.analysis().historyQueryMaxLimit();
        if (limit > maxLimit) {
            throw new AiopsException(
                    ErrorCode.VALIDATION_FAILED,
                    "history limit exceeds configured maximum: " + maxLimit
            );
        }
    }
}
