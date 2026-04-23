package com.company.aiops.persistence;

import com.company.aiops.analysis.AnalysisJob;
import com.company.aiops.analysis.AnalysisJobStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface AnalysisTaskRepository {

    void save(AnalysisJob analysisJob);

    Optional<AnalysisJob> findByTaskId(String taskId);

    Optional<AnalysisJob> findLatestByDedupeKeyAndStatusesSince(
            String dedupeKey,
            Collection<AnalysisJobStatus> statuses,
            Instant createdSince
    );
}
