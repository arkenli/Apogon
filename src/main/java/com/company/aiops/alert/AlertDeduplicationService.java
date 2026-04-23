package com.company.aiops.alert;

import com.company.aiops.analysis.AnalysisJob;
import com.company.aiops.analysis.AnalysisJobStatus;
import com.company.aiops.analysis.HistoricalReference;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.AnalysisResultRepository;
import com.company.aiops.persistence.AnalysisTaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

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
