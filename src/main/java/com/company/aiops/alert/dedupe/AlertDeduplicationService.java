package com.company.aiops.alert.dedupe;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.HistoricalReference;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 根据 dedupeKey 判断告警是否需要复用已有分析任务，或返回历史参考结果。
 */
@Service
public class AlertDeduplicationService {

    private static final Set<AnalysisJobStatus> DUPLICATE_STATUSES = EnumSet.of(
            AnalysisJobStatus.PENDING,
            AnalysisJobStatus.RUNNING,
            AnalysisJobStatus.COLLECTING_EVIDENCE,
            AnalysisJobStatus.RULE_ANALYZING,
            AnalysisJobStatus.COMPRESSING_EVIDENCE,
            AnalysisJobStatus.LLM_ANALYZING,
            AnalysisJobStatus.RULE_FALLBACK_ANALYZING,
            AnalysisJobStatus.GENERATING_RESULT,
            AnalysisJobStatus.SUCCESS,
            AnalysisJobStatus.DEGRADED_SUCCESS
    );

    private final AiopsProperties aiopsProperties;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisResultRepository analysisResultRepository;

    public AlertDeduplicationService(
            AiopsProperties aiopsProperties,
            AnalysisTaskRepository analysisTaskRepository,
            AnalysisResultRepository analysisResultRepository
    ) {
        this.aiopsProperties = aiopsProperties;
        this.analysisTaskRepository = analysisTaskRepository;
        this.analysisResultRepository = analysisResultRepository;
    }

    /**
     * 先判断去重窗口内是否存在可复用任务，再查询历史参考结果。
     */
    public AlertDeduplicationResult deduplicate(String dedupeKey, Instant now) {
        Instant duplicateSince = now.minus(aiopsProperties.analysis().dedupeWindow());
        Optional<AnalysisJob> duplicateTask = analysisTaskRepository.findLatestByDedupeKeyAndStatusesSince(
                dedupeKey,
                DUPLICATE_STATUSES,
                duplicateSince
        );
        if (duplicateTask.isPresent()) {
            return new AlertDeduplicationResult(duplicateTask, Optional.empty());
        }

        Instant historicalSince = now.minus(aiopsProperties.analysis().historicalReferenceLookback());
        Optional<HistoricalReference> historicalReference = analysisResultRepository.findLatestHistoricalReference(
                dedupeKey,
                historicalSince,
                duplicateSince
        );
        return new AlertDeduplicationResult(Optional.empty(), historicalReference);
    }
}
