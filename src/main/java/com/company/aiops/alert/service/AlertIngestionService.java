package com.company.aiops.alert.service;

import com.company.aiops.alert.classifier.AlertClassifier;
import com.company.aiops.alert.dedupe.AlertDeduplicationResult;
import com.company.aiops.alert.dedupe.AlertDeduplicationService;
import com.company.aiops.alert.dedupe.DedupeKeyGenerator;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertWebhookResponse;
import com.company.aiops.alert.parser.AlertParser;
import com.company.aiops.alert.support.TaskIdGenerator;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.orchestrator.worker.AnalysisJobExecutionRequestedEvent;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import com.company.aiops.persistence.repository.DuplicateAlertLinkRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Coordinates alert ingestion, parsing, dedupe handling, and initial task creation.
 */
@Service
public class AlertIngestionService {

    private final AlertParser alertParser;
    private final AlertClassifier alertClassifier;
    private final AlertEventRepository alertEventRepository;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final DuplicateAlertLinkRepository duplicateAlertLinkRepository;
    private final AlertDeduplicationService alertDeduplicationService;
    private final DedupeKeyGenerator dedupeKeyGenerator;
    private final TaskIdGenerator taskIdGenerator;
    private final ApplicationEventPublisher applicationEventPublisher;

    public AlertIngestionService(
            AlertParser alertParser,
            AlertClassifier alertClassifier,
            AlertEventRepository alertEventRepository,
            AnalysisTaskRepository analysisTaskRepository,
            DuplicateAlertLinkRepository duplicateAlertLinkRepository,
            AlertDeduplicationService alertDeduplicationService,
            DedupeKeyGenerator dedupeKeyGenerator,
            TaskIdGenerator taskIdGenerator,
            ApplicationEventPublisher applicationEventPublisher
    ) {
        this.alertParser = alertParser;
        this.alertClassifier = alertClassifier;
        this.alertEventRepository = alertEventRepository;
        this.analysisTaskRepository = analysisTaskRepository;
        this.duplicateAlertLinkRepository = duplicateAlertLinkRepository;
        this.alertDeduplicationService = alertDeduplicationService;
        this.dedupeKeyGenerator = dedupeKeyGenerator;
        this.taskIdGenerator = taskIdGenerator;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Stores the alert and either reuses an existing task or creates a new pending task.
     */
    @Transactional
    public AlertWebhookResponse ingest(String rawText) {
        AlertEvent parsedAlert = alertParser.parse(rawText);
        AlertCategory category = alertClassifier.classify(parsedAlert);
        AlertEvent alert = parsedAlert.withCategory(category);
        validateAlert(alert);

        alertEventRepository.save(alert);

        Instant now = Instant.now();
        String dedupeKey = dedupeKeyGenerator.generate(alert);
        AlertDeduplicationResult dedupeResult = alertDeduplicationService.deduplicate(dedupeKey, now);
        if (dedupeResult.duplicateTask().isPresent()) {
            AnalysisJob duplicateTask = dedupeResult.duplicateTask().get();
            duplicateAlertLinkRepository.save(alert.alertId(), duplicateTask.taskId(), dedupeKey, now);
            return new AlertWebhookResponse(
                    alert.alertId(),
                    duplicateTask.taskId(),
                    duplicateTask.status().name(),
                    true,
                    null,
                    "duplicate alert merged into existing analysis task"
            );
        }

        AnalysisJob analysisJob = new AnalysisJob(
                taskIdGenerator.nextTaskId(),
                alert.alertId(),
                alert.category(),
                dedupeKey,
                AnalysisJobStatus.PENDING,
                null,
                "analysis task accepted",
                0,
                0L,
                now,
                null,
                null,
                null
        );
        analysisTaskRepository.save(analysisJob);
        applicationEventPublisher.publishEvent(new AnalysisJobExecutionRequestedEvent(analysisJob.taskId()));

        return new AlertWebhookResponse(
                alert.alertId(),
                analysisJob.taskId(),
                analysisJob.status().name(),
                false,
                dedupeResult.historicalReference().orElse(null),
                "analysis task accepted"
        );
    }

    static void validateAlert(AlertEvent alert) {
        if (alert.alertId() == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "Missing Alert ID");
        }
        if (alert.category() == null || alert.category() == AlertCategory.UNKNOWN) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "Unsupported alert category");
        }
        switch (alert.category()) {
            case RPC_ERROR_RATE, RPC_LATENCY -> validateRpcAlert(alert);
            case SERVICE_ERROR_LOG -> validateServiceErrorLogAlert(alert);
            case UNKNOWN -> throw new AiopsException(ErrorCode.BAD_REQUEST, "Unsupported alert category");
        }
        if (alert.metricOccurTime() == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "Missing metric occurrence time");
        }
    }

    private static void validateRpcAlert(AlertEvent alert) {
        if (!hasText(alert.serviceName()) || !hasText(alert.interfaceName()) || !hasText(alert.cluster())) {
            throw new AiopsException(
                    ErrorCode.BAD_REQUEST,
                    "Alert must contain serviceName, interfaceName, and cluster"
            );
        }
    }

    private static void validateServiceErrorLogAlert(AlertEvent alert) {
        if (!hasText(alert.serviceName()) || !hasText(alert.cluster())) {
            throw new AiopsException(
                    ErrorCode.BAD_REQUEST,
                    "Service error log alert must contain serviceName and cluster"
            );
        }
        if (alert.triggerValues() == null
                || (!hasText(alert.triggerValues().normalizedLogQuery())
                && !hasText(alert.triggerValues().logMessage()))) {
            throw new AiopsException(
                    ErrorCode.BAD_REQUEST,
                    "Service error log alert must contain log query text"
            );
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
