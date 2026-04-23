package com.company.aiops.persistence;

import com.company.aiops.analysis.AnalysisResult;
import com.company.aiops.analysis.HistoricalReference;

import java.time.Instant;
import java.util.Optional;

public interface AnalysisResultRepository {

    void save(AnalysisResult analysisResult);

    Optional<HistoricalReference> findLatestHistoricalReference(
            String dedupeKey,
            Instant historicalSince,
            Instant duplicateBefore
    );
}
