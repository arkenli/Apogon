package com.company.aiops.alert;

import com.company.aiops.analysis.AnalysisJob;
import com.company.aiops.analysis.AnalysisJobStatus;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.AlertEventRepository;
import com.company.aiops.persistence.AnalysisTaskRepository;
import com.company.aiops.persistence.DuplicateAlertLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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

    public AlertIngestionService(
            AlertParser alertParser,
            AlertClassifier alertClassifier,
            AlertEventRepository alertEventRepository,
            AnalysisTaskRepository analysisTaskRepository,
            DuplicateAlertLinkRepository duplicateAlertLinkRepository,
            AlertDeduplicationService alertDeduplicationService,
            DedupeKeyGenerator dedupeKeyGenerator,
            TaskIdGenerator taskIdGenerator
    ) {
        this.alertParser = alertParser;
        this.alertClassifier = alertClassifier;
        this.alertEventRepository = alertEventRepository;
        this.analysisTaskRepository = analysisTaskRepository;
        this.duplicateAlertLinkRepository = duplicateAlertLinkRepository;
        this.alertDeduplicationService = alertDeduplicationService;
        this.dedupeKeyGenerator = dedupeKeyGenerator;
        this.taskIdGenerator = taskIdGenerator;
    }

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

        return new AlertWebhookResponse(
                alert.alertId(),
                analysisJob.taskId(),
                analysisJob.status().name(),
                false,
                dedupeResult.historicalReference().orElse(null),
                "analysis task accepted"
        );
    }

    private void validateAlert(AlertEvent alert) {
        if (alert.alertId() == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "告警缺少 Alert ID");
        }
        if (alert.category() == AlertCategory.UNKNOWN) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "不支持的告警类型");
        }
        if (alert.serviceName() == null || alert.interfaceName() == null || alert.cluster() == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "告警缺少 serviceName、interfaceName 或 cluster");
        }
        if (alert.metricOccurTime() == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "告警缺少指标发生时间");
        }
    }
}
